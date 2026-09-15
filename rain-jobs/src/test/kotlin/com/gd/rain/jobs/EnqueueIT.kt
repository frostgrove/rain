package com.gd.rain.jobs

import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.jobs.support.QueueFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

@Tag("integration")
class EnqueueIT {
    @Test
    fun `an enqueue writes the invocation and its execution at the stated priority and delay`() {
        val fixture = QueueFixture("enqueue_writes")

        val outcome =
            fixture.queue.enqueue(
                Fixtures.definition(),
                Note("hello"),
                EnqueueOptions(Dedupe.None, JobPriority(70), after = Duration.ofMinutes(2), subjectKey = SubjectKey("ticket:1")),
            )

        assertThat(outcome).isInstanceOf(EnqueueOutcome.Scheduled::class.java)
        val row =
            fixture.database.jdbc.queryForMap(
                "SELECT state, profile, priority, payload::text AS payload, subject_key, retry_limit, generation, eligible_at " +
                    "FROM rain_jobs.job_invocation WHERE id = ?",
                outcome.invocation,
            )
        assertThat(row["state"]).isEqualTo("queued")
        assertThat(row["profile"]).isEqualTo("standard")
        assertThat(row["priority"]).isEqualTo(70)
        assertThat(row["payload"]).isEqualTo("{\"text\": \"hello\"}")
        assertThat(row["subject_key"]).isEqualTo("ticket:1")
        assertThat(row["retry_limit"]).isEqualTo(4)
        assertThat(row["generation"]).isEqualTo(0)
        val execution =
            fixture.database.jdbc.queryForMap(
                "SELECT task_name, task_instance, priority, execution_time = ? AS due_as_stated FROM rain_jobs.scheduled_tasks",
                java.sql.Timestamp.from(Fixtures.START.plus(Duration.ofMinutes(2))),
            )
        assertThat(execution["task_name"]).isEqualTo("notes.write")
        assertThat(execution["task_instance"]).isEqualTo("${outcome.invocation}/0")
        assertThat(execution["priority"]).isEqualTo(70)
        assertThat(execution["due_as_stated"]).isEqualTo(true)
    }

    @Test
    fun `an enqueue inside a rolled-back transaction leaves nothing behind`() {
        val fixture = QueueFixture("enqueue_rollback")

        assertThatThrownBy {
            fixture.database.inTransaction {
                fixture.queue.enqueue(Fixtures.definition(), Note("x"), Fixtures.options(Dedupe.Unique("k")))
                error("the caller's change failed after enqueueing")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(fixture.invocations()).isZero()
        assertThat(fixture.executions()).isZero()
        assertThat(fixture.heldIntents()).describedAs("the reservation rolled back with the order").isZero()
    }

    @Test
    fun `an enqueue from a caller without a transaction commits on its own`() {
        val fixture = QueueFixture("enqueue_autonomous")

        fixture.queue.enqueue(Fixtures.definition(), Note("x"), Fixtures.options())

        assertThat(fixture.invocations()).isEqualTo(1)
        assertThat(fixture.executions()).isEqualTo(1)
    }

    @Test
    fun `a unique key admits one order and absorbs the next into its holder`() {
        val fixture = QueueFixture("enqueue_unique")

        val first = fixture.queue.enqueue(Fixtures.definition(), Note("a"), Fixtures.options(Dedupe.Unique("k")))
        val second = fixture.queue.enqueue(Fixtures.definition(), Note("b"), Fixtures.options(Dedupe.Unique("k")))

        assertThat(first).isInstanceOf(EnqueueOutcome.Scheduled::class.java)
        assertThat(second).isEqualTo(EnqueueOutcome.Deduplicated(first.invocation))
        assertThat(fixture.executions()).isEqualTo(1)
    }

    @Test
    fun `the same key under another definition is a different reservation`() {
        val other = Fixtures.definition("notes.index")
        val fixture = QueueFixture("enqueue_scoped", definitions = listOf(Fixtures.definition(), other))

        fixture.queue.enqueue(Fixtures.definition(), Note("a"), Fixtures.options(Dedupe.Unique("k")))
        val elsewhere = fixture.queue.enqueue(other, Note("a"), Fixtures.options(Dedupe.Unique("k")))

        assertThat(elsewhere).isInstanceOf(EnqueueOutcome.Scheduled::class.java)
    }

    @Test
    fun `work without a dedupe key is never absorbed`() {
        val fixture = QueueFixture("enqueue_none")

        val first = fixture.queue.enqueue(Fixtures.definition(), Note("a"), Fixtures.options())
        val second = fixture.queue.enqueue(Fixtures.definition(), Note("a"), Fixtures.options())

        assertThat(second.invocation).isNotEqualTo(first.invocation)
        assertThat(fixture.executions()).isEqualTo(2)
    }

    @Test
    fun `a definition that is not declared, or a different one under a declared name, is refused and writes nothing`() {
        val fixture = QueueFixture("enqueue_unknown")

        assertThatThrownBy { fixture.queue.enqueue(Fixtures.definition("notes.other"), Note("a"), Fixtures.options()) }
            .isInstanceOf(UnknownJobDefinitionException::class.java)
        assertThatThrownBy {
            fixture.queue.enqueue(Fixtures.definition(profile = "elsewhere"), Note("a"), Fixtures.options())
        }.isInstanceOf(UnknownJobDefinitionException::class.java)
        assertThat(fixture.invocations()).isZero()
    }
}
