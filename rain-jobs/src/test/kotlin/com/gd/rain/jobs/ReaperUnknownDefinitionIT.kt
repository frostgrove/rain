package com.gd.rain.jobs

import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.HousekeepingFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Gap 9: a lapsed invocation whose definition this application does not declare is dead with `unknown_definition` —
 * never retried under a default profile it was not enqueued with.
 */
@Tag("integration")
class ReaperUnknownDefinitionIT {
    @Test
    fun `an invocation of an undeclared definition is buried as unknown_definition, whatever budget it has left`() {
        val fixture = HousekeepingFixture("reaper_unknown")
        val id = fixture.enqueue(dedupe = Dedupe.Unique("k"))
        fixture.database.jdbc.update("UPDATE rain_jobs.job_invocation SET definition = 'notes.retired' WHERE id = ?", id)
        fixture.running(id, Fixtures.START.minusSeconds(1))

        val report = fixture.reaper().reap()

        assertThat(report.unknownDefinition).containsExactly(id)
        assertThat(report.requeued).isEmpty()
        val row = fixture.row(id)
        assertThat(row["state"]).isEqualTo("dead")
        assertThat(row["failure_code"]).isEqualTo(FailureCode.UNKNOWN_DEFINITION)
        assertThat(row["failure_message"] as String).contains("notes.retired")
        assertThat(row["retry_spent"]).describedAs("no retry was charged under an assumed profile").isEqualTo(0)
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE released_at IS NULL")).isZero()
    }
}
