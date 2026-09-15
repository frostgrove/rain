package com.gd.rain.jobs

import com.gd.rain.jobs.internal.ledger.HousekeepingLedger
import com.gd.rain.jobs.internal.ledger.LapsedInvocation
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.HousekeepingFixture
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
class ReaperIT {
    @Test
    fun `a running invocation past its lease is requeued with one retry charged and the profile's backoff`() {
        val fixture = HousekeepingFixture("reaper_requeue")
        val id = fixture.enqueue(dedupe = Dedupe.Unique("k"))
        fixture.running(id, Fixtures.START.minusSeconds(1))

        val report = fixture.reaper().reap()

        assertThat(report.requeued).containsExactly(id)
        val row = fixture.row(id)
        assertThat(row["state"]).isEqualTo("queued")
        assertThat(row["retry_spent"]).isEqualTo(1)
        assertThat(row["lease_token"]).isNull()
        assertThat(row["failure_code"]).isEqualTo(FailureCode.LEASE_EXPIRED)
        assertThat((row["eligible_at"] as Timestamp).toInstant()).isEqualTo(Fixtures.START.plus(Fixtures.profile().backoff.initial))
        assertThat(
            fixture.database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE released_at IS NULL"),
        ).describedAs("still held").isEqualTo(1)
    }

    @Test
    fun `a lease that has not expired is untouched`() {
        val fixture = HousekeepingFixture("reaper_live")
        val id = fixture.enqueue()
        fixture.running(id, Fixtures.START.plusSeconds(1))

        assertThat(fixture.reaper().reap().requeued).isEmpty()
        assertThat(fixture.state(id)).isEqualTo("running")
    }

    @Test
    fun `a lapsed invocation whose budget is spent is dead and its reservation released`() {
        val fixture = HousekeepingFixture("reaper_dead")
        val id = fixture.enqueue(dedupe = Dedupe.Unique("k"))
        fixture.database.jdbc.update("UPDATE rain_jobs.job_invocation SET retry_spent = retry_limit WHERE id = ?", id)
        fixture.running(id, Fixtures.START)

        assertThat(fixture.reaper().reap().dead).containsExactly(id)
        assertThat(fixture.state(id)).isEqualTo("dead")
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE released_at IS NULL")).isZero()
    }

    @Test
    fun `a pass takes at most one batch, earliest lapse first`() {
        val fixture = HousekeepingFixture("reaper_batch")
        val later = fixture.enqueue()
        val earlier = fixture.enqueue()
        fixture.running(later, Fixtures.START.minusSeconds(1))
        fixture.running(earlier, Fixtures.START.minusSeconds(5))

        assertThat(fixture.reaper(batch = 1).reap().requeued).containsExactly(earlier)
        assertThat(fixture.state(later)).isEqualTo("running")
    }

    @Test
    fun `two reapers at once take disjoint rows`() {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val fixture =
            HousekeepingFixture("reaper_disjoint", decorate = { real ->
                object : HousekeepingLedger by real {
                    override fun lapsed(
                        now: Instant,
                        batch: Int,
                    ): List<LapsedInvocation> =
                        real.lapsed(now, batch).also {
                            if (Thread.currentThread().name == FIRST) {
                                paused.countDown()
                                Awaits.latch(resume, "the second reaper to finish")
                            }
                        }
                }
            })
        val a = fixture.enqueue()
        val b = fixture.enqueue()
        fixture.running(a, Fixtures.START.minusSeconds(9))
        fixture.running(b, Fixtures.START.minusSeconds(3))
        val pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(FIRST).factory())

        val first = pool.submit<List<java.util.UUID>> { fixture.reaper(batch = 1).reap().requeued }
        Awaits.latch(paused, "the first reaper to hold its page")
        val second = fixture.reaper(batch = 1).reap().requeued
        resume.countDown()

        assertThat(first.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)).containsExactly(a)
        assertThat(second).describedAs("the locked row is skipped, not waited for").containsExactly(b)
        pool.shutdown()
    }

    @Test
    fun `the reaper's page reads through the lease index under a limit`() {
        val fixture = HousekeepingFixture("reaper_plan")

        val plan =
            QueryPlans.explain(
                fixture.database.dataSource,
                fixture.database.dsl.renderInlined(fixture.jooq.lapsedQuery(Fixtures.START, 100)),
                generic = false,
            )

        assertThat(plan.usesIndex("ix_job_invocation_lease")).describedAs(plan.json).isTrue()
        assertThat(plan.boundedScan("job_invocation")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    private companion object {
        const val FIRST = "first-reaper"
    }
}
