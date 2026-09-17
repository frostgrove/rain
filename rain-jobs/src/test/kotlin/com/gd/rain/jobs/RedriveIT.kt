package com.gd.rain.jobs

import com.gd.rain.jobs.admin.RedriveOutcome
import com.gd.rain.jobs.support.AdminFixture
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/** Gap 3: a redrive resolves the definition, takes the reservation again, requeues under a new generation and schedules it. */
@Tag("integration")
class RedriveIT {
    @Test
    fun `a dead invocation is requeued with a fresh budget and an execution of its next generation is scheduled`() {
        val fixture = AdminFixture("redrive_schedules")
        val id =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), Fixtures.options(priority = 70))
                .invocation
        fixture.terminal(id, JobState.DEAD, Fixtures.START)
        assertThat(fixture.queue.executions()).isZero()

        val outcome = fixture.administration.redrive(id)

        assertThat(outcome).isEqualTo(RedriveOutcome.Redriven(id, 1))
        val row = fixture.row(id)
        assertThat(row["state"]).isEqualTo("queued")
        assertThat(row["retry_spent"]).isEqualTo(0)
        assertThat(row["finished_at"]).isNull()
        assertThat(row["failure_code"]).isNull()
        assertThat(row["generation"]).isEqualTo(1)
        val execution = fixture.database.jdbc.queryForMap("SELECT task_name, task_instance, priority FROM rain_jobs.scheduled_tasks")
        assertThat(execution["task_instance"]).isEqualTo("$id/1")
        assertThat(execution["priority"]).isEqualTo(70)
        assertThat(fixture.administration.redrive(id)).describedAs("a second press").isEqualTo(RedriveOutcome.NotTerminal(JobState.QUEUED))
    }

    @Test
    fun `a redrive takes its unique reservation again`() {
        val fixture = AdminFixture("redrive_reserves")
        val id =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), Fixtures.options(Dedupe.Unique("k")))
                .invocation
        fixture.terminal(id, JobState.FAILED, Fixtures.START)

        fixture.administration.redrive(id)

        assertThat(
            fixture.database.jdbc.queryForObject(
                "SELECT invocation_id FROM rain_jobs.job_intent WHERE released_at IS NULL",
                UUID::class.java,
            ),
        ).isEqualTo(id)
        assertThat(fixture.queue.queue.enqueue(Fixtures.definition(), Note("again"), Fixtures.options(Dedupe.Unique("k"))))
            .isEqualTo(EnqueueOutcome.Deduplicated(id))
    }

    @Test
    fun `a reservation another invocation holds now answers Deduplicated and writes nothing`() {
        val fixture = AdminFixture("redrive_held")
        val id =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), Fixtures.options(Dedupe.Unique("k")))
                .invocation
        fixture.terminal(id, JobState.DEAD, Fixtures.START)
        val holder =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("newer"), Fixtures.options(Dedupe.Unique("k")))
                .invocation
        val before = fixture.row(id)
        val executions = fixture.queue.executions()

        assertThat(fixture.administration.redrive(id)).isEqualTo(RedriveOutcome.Deduplicated(holder))

        assertThat(fixture.row(id)).usingRecursiveComparison().isEqualTo(before)
        assertThat(fixture.queue.executions()).isEqualTo(executions)
    }

    @Test
    fun `an invocation of a definition this application does not declare answers UnknownDefinition and writes nothing`() {
        val fixture = AdminFixture("redrive_unknown")
        val id =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), Fixtures.options(Dedupe.Unique("k")))
                .invocation
        fixture.terminal(id, JobState.DEAD, Fixtures.START)
        fixture.database.jdbc.update("UPDATE rain_jobs.job_invocation SET definition = 'notes.retired' WHERE id = ?", id)
        val before = fixture.row(id)

        assertThat(fixture.administration.redrive(id)).isEqualTo(RedriveOutcome.UnknownDefinition("notes.retired"))

        assertThat(fixture.row(id)).usingRecursiveComparison().isEqualTo(before)
        assertThat(fixture.queue.executions()).isZero()
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_intent WHERE released_at IS NULL")).isZero()
    }

    @Test
    fun `live and missing invocations are not redriven`() {
        val fixture = AdminFixture("redrive_refusals")
        val queued =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), Fixtures.options())
                .invocation
        val succeeded =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), Fixtures.options())
                .invocation
        fixture.terminal(succeeded, JobState.SUCCEEDED, Fixtures.START)

        assertThat(fixture.administration.redrive(queued)).isEqualTo(RedriveOutcome.NotTerminal(JobState.QUEUED))
        assertThat(fixture.administration.redrive(succeeded)).isEqualTo(RedriveOutcome.NotTerminal(JobState.SUCCEEDED))
        assertThat(fixture.administration.redrive(UUID(0, 42))).isEqualTo(RedriveOutcome.NotFound)
    }

    @Test
    fun `a redrive inside a transaction that rolls back leaves the invocation dead and nothing scheduled`() {
        val fixture = AdminFixture("redrive_rollback")
        val id =
            fixture.queue.queue
                .enqueue(Fixtures.definition(), Note("n"), Fixtures.options())
                .invocation
        fixture.terminal(id, JobState.DEAD, Fixtures.START)

        runCatching {
            fixture.database.inTransaction {
                fixture.administration.redrive(id)
                error("the operator's request failed afterwards")
            }
        }

        assertThat(fixture.row(id)["state"]).isEqualTo("dead")
        assertThat(fixture.queue.executions()).isZero()
    }
}
