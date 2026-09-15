package com.gd.rain.jobs

import com.gd.rain.jobs.internal.ledger.HousekeepingLedger
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.HousekeepingFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@Tag("integration")
class RetentionIT {
    private val short = Fixtures.profile("short", retention = Duration.ofDays(1))
    private val long = Fixtures.profile("long", retention = Duration.ofDays(7))
    private val quick = Fixtures.definition("notes.quick", "short")
    private val slow = Fixtures.definition("notes.slow", "long")

    @Test
    fun `each profile is cut at its own retention, and live invocations are never deleted`() {
        val fixture = HousekeepingFixture("retention_profiles", listOf(short, long), listOf(quick, slow))
        val quickOld = fixture.enqueue(quick)
        val slowOld = fixture.enqueue(slow)
        val quickLive = fixture.enqueue(quick)
        val slowRunning = fixture.enqueue(slow)
        fixture.terminal(quickOld, JobState.SUCCEEDED, Fixtures.START)
        fixture.terminal(slowOld, JobState.DEAD, Fixtures.START)
        fixture.running(slowRunning, Fixtures.START.minusSeconds(3600))

        fixture.clock.advance(Duration.ofDays(2))
        val first = fixture.retention().sweep()

        assertThat(first.finished).isTrue()
        assertThat(first.deletedInvocations).containsExactlyInAnyOrderEntriesOf(mapOf("short" to 1L, "long" to 0L))
        assertThat(fixture.state(quickOld)).isNull()
        assertThat(fixture.state(slowOld)).isEqualTo("dead")

        fixture.clock.advance(Duration.ofDays(3650))
        fixture.retention().sweep()

        assertThat(fixture.state(slowOld)).isNull()
        assertThat(fixture.state(quickLive)).isEqualTo("queued")
        assertThat(fixture.state(slowRunning)).isEqualTo("running")
    }

    @Test
    fun `released reservations past retention are deleted and held ones are kept`() {
        val fixture = HousekeepingFixture("retention_intents", listOf(short), listOf(quick))
        val finished = fixture.enqueue(quick, Dedupe.Unique("finished"))
        fixture.enqueue(quick, Dedupe.Unique("held"))
        fixture.terminal(finished, JobState.SUCCEEDED, Fixtures.START)

        fixture.clock.advance(Duration.ofDays(2))
        val report = fixture.retention().sweep()

        assertThat(report.deletedIntents).containsEntry("short", 1L)
        assertThat(
            fixture.database.jdbc.queryForList("SELECT dedupe_key FROM rain_jobs.job_intent", String::class.java),
        ).containsExactly("held")
    }

    @Test
    fun `a pass deletes in batches until a batch comes back short`() {
        val deletes = AtomicInteger()
        val fixture =
            HousekeepingFixture("retention_batches", listOf(short), listOf(quick), decorate = { real ->
                object : HousekeepingLedger by real {
                    override fun deleteTerminal(
                        profile: String,
                        before: Instant,
                        batch: Int,
                    ): Int = real.deleteTerminal(profile, before, batch).also { deletes.incrementAndGet() }
                }
            })
        repeat(5) { fixture.terminal(fixture.enqueue(quick), JobState.SUCCEEDED, Fixtures.START) }
        fixture.clock.advance(Duration.ofDays(2))

        val report = fixture.retention(batch = 2).sweep()

        assertThat(report.deletedInvocations).containsEntry("short", 5L)
        assertThat(deletes.get()).describedAs("2 + 2 + 1").isEqualTo(3)
        assertThat(report.finished).isTrue()
    }

    @Test
    fun `a pass stops starting batches once its run budget is spent, and says it did not finish`() {
        lateinit var fixture: HousekeepingFixture
        fixture =
            HousekeepingFixture("retention_budget", listOf(short), listOf(quick), decorate = { real ->
                object : HousekeepingLedger by real {
                    override fun deleteTerminal(
                        profile: String,
                        before: Instant,
                        batch: Int,
                    ): Int = real.deleteTerminal(profile, before, batch).also { fixture.clock.advance(Duration.ofSeconds(20)) }
                }
            })
        repeat(5) { fixture.terminal(fixture.enqueue(quick), JobState.SUCCEEDED, Fixtures.START) }
        fixture.clock.advance(Duration.ofDays(2))

        val report = fixture.retention(batch = 2, budget = Duration.ofSeconds(30)).sweep()

        assertThat(report.finished).isFalse()
        assertThat(report.deletedInvocations).containsEntry("short", 4L)
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_invocation")).isEqualTo(1)
    }
}
