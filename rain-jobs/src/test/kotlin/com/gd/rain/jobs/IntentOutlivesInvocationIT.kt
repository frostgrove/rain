package com.gd.rain.jobs

import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.HousekeepingFixture
import com.gd.rain.jobs.support.Note
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Gap 5: reservations have no foreign key or cascade to invocations. Deleting an invocation leaves its reservation
 * history for the intent retention to remove on its own schedule, and a key whose holder row is gone is refused
 * explicitly rather than silently treated as free.
 */
@Tag("integration")
class IntentOutlivesInvocationIT {
    @Test
    fun `an invocation deleted by retention leaves its released reservation behind`() {
        val profile = Fixtures.profile(retention = Duration.ofDays(1))
        val fixture = HousekeepingFixture("intent_outlives", listOf(profile))
        val id = fixture.enqueue(dedupe = Dedupe.Unique("k"))
        fixture.terminal(id, JobState.SUCCEEDED, Fixtures.START)
        fixture.clock.advance(Duration.ofDays(2))

        fixture.jooq.deleteTerminal(profile.id, fixture.clock.instant().minus(profile.retention), 100)

        assertThat(fixture.state(id)).isNull()
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE invocation_id = '$id'"))
            .describedAs("no cascade removed the reservation history")
            .isEqualTo(1)
        assertThat(
            fixture.database.count(
                "SELECT count(*) FROM information_schema.table_constraints " +
                    "WHERE constraint_type = 'FOREIGN KEY' AND table_schema = 'rain_jobs'",
            ),
        ).isZero()
    }

    @Test
    fun `a held key whose holder row is gone refuses the order instead of absorbing it into nothing`() {
        val fixture = HousekeepingFixture("intent_orphan")
        val id = fixture.enqueue(dedupe = Dedupe.Unique("k"))
        fixture.database.jdbc.update("DELETE FROM rain_jobs.job_invocation WHERE id = ?", id)

        val refused =
            runCatching { fixture.queue.queue.enqueue(Fixtures.definition(), Note("again"), Fixtures.options(Dedupe.Unique("k"))) }

        assertThat(refused.exceptionOrNull()).isInstanceOf(IntentConflictException::class.java)
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_invocation")).isZero()
    }
}
