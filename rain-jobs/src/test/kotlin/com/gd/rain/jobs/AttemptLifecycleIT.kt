package com.gd.rain.jobs

import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** The attempt lifecycle against PostgreSQL, one attempt driven by hand per step. */
@Tag("integration")
class AttemptLifecycleIT {
    @Test
    fun `a successful attempt is succeeded, holds no lease, names its scheduler and releases its unique reservation`() {
        val fixture = AttemptFixture("attempt_success")
        val id = fixture.enqueue(Dedupe.Unique("k"))
        val seen = mutableListOf<AttemptMeta>()

        val completion = fixture.run(id) { note, attempt -> seen += attempt.meta.also { check(note == Note("payload")) } }

        assertThat(completion).isEqualTo(Completion.Remove)
        val row = fixture.row(id)
        assertThat(row["state"]).isEqualTo("succeeded")
        assertThat(row["attempts"]).isEqualTo(1)
        assertThat(row["lease_token"]).isNull()
        assertThat(row["picked_by"]).isEqualTo("fixture#standard")
        assertThat(fixture.instant(id, "finished_at")).isEqualTo(Fixtures.START)
        assertThat(fixture.heldIntents(id)).isZero()
        assertThat(seen.single().attempt).isEqualTo(1)
        assertThat(seen.single().deadline).isEqualTo(Fixtures.START.plus(fixture.profile.attemptTimeout))
        assertThat(fixture.queue.queue.enqueue(fixture.definition, Note("again"), Fixtures.options(Dedupe.Unique("k"))))
            .describedAs("the key is free once the invocation is terminal")
            .isInstanceOf(EnqueueOutcome.Scheduled::class.java)
    }

    @Test
    fun `a collapse reservation is released by the claim, before the handler reads anything`() {
        val fixture = AttemptFixture("attempt_collapse")
        val id = fixture.enqueue(Dedupe.Collapse("c"))
        val duringAttempt = mutableListOf<EnqueueOutcome>()

        fixture.run(id) { _, _ ->
            duringAttempt +=
                fixture.queue.queue.enqueue(fixture.definition, Note("n"), Fixtures.options(Dedupe.Collapse("c")))
        }

        assertThat(duringAttempt.single()).isInstanceOf(EnqueueOutcome.Scheduled::class.java)
    }

    @Test
    fun `a permanent refusal is failed with no budget spent`() {
        val fixture = AttemptFixture("attempt_permanent")
        val id = fixture.enqueue()

        val completion = fixture.run(id) { _, _ -> throw JobPermanentException("nothing to do") }

        assertThat(completion).isEqualTo(Completion.Remove)
        val row = fixture.row(id)
        assertThat(row["state"]).isEqualTo("failed")
        assertThat(row["retry_spent"]).isEqualTo(0)
        assertThat(row["failure_code"]).isEqualTo(FailureCode.PERMANENT)
        assertThat(row["failure_message"] as String).contains("nothing to do")
    }

    @Test
    fun `a failure is a charged retry due after the profile's backoff, and the budget exhausts into dead`() {
        val fixture = AttemptFixture("attempt_retries", profile = Fixtures.profile(retries = 1))
        val id = fixture.enqueue(Dedupe.Unique("k"))
        val attempts = AtomicInteger()

        val first = fixture.run(id) { _, _ -> error("boom ${attempts.incrementAndGet()}") }

        val eligible = Fixtures.START.plus(fixture.profile.backoff.ceiling(0))
        assertThat(first).isEqualTo(Completion.Reschedule(eligible))
        assertThat(fixture.row(id)["state"]).isEqualTo("queued")
        assertThat(fixture.row(id)["retry_spent"]).isEqualTo(1)
        assertThat(fixture.row(id)["failure_code"]).isEqualTo(FailureCode.FAILED)
        assertThat(fixture.instant(id, "eligible_at")).isEqualTo(eligible)
        assertThat(fixture.heldIntents(id)).describedAs("a retry keeps the unique reservation").isEqualTo(1)

        fixture.clock.set(eligible)
        val second = fixture.run(id) { _, _ -> error("boom ${attempts.incrementAndGet()}") }

        assertThat(second).isEqualTo(Completion.Remove)
        assertThat(fixture.row(id)["state"]).isEqualTo("dead")
        assertThat(fixture.row(id)["attempts"]).isEqualTo(2)
        assertThat(fixture.row(id)["failure_message"] as String).contains("boom 2")
        assertThat(fixture.heldIntents(id)).isZero()
    }

    @Test
    fun `a deferral charges no retry, and the profile's deferral budget exhausts into dead`() {
        val fixture = AttemptFixture("attempt_deferral", profile = Fixtures.profile(deferrals = 1))
        val id = fixture.enqueue()

        val first = fixture.run(id) { _, _ -> throw JobDeferredException(Duration.ofSeconds(30)) }

        assertThat(first).isEqualTo(Completion.Reschedule(Fixtures.START.plusSeconds(30)))
        assertThat(fixture.row(id)["state"]).isEqualTo("queued")
        assertThat(fixture.row(id)["deferrals"]).isEqualTo(1)
        assertThat(fixture.row(id)["retry_spent"]).isEqualTo(0)

        fixture.clock.advance(Duration.ofSeconds(30))
        val second = fixture.run(id) { _, _ -> throw JobDeferredException(Duration.ofSeconds(30)) }

        assertThat(second).isEqualTo(Completion.Remove)
        assertThat(fixture.row(id)["state"]).isEqualTo("dead")
        assertThat(fixture.row(id)["failure_code"]).isEqualTo(FailureCode.DEFERRALS_EXHAUSTED)
    }

    @Test
    fun `a payload that cannot be read as the payload type is failed without spending the budget`() {
        val fixture = AttemptFixture("attempt_payload")
        val id = fixture.enqueue()
        fixture.sql("UPDATE rain_jobs.job_invocation SET payload = '{\"other\": 1}' WHERE id = ?", id)
        val ran = AtomicInteger()

        fixture.run(id) { _, _ -> ran.incrementAndGet() }

        assertThat(ran.get()).isZero()
        assertThat(fixture.row(id)["state"]).isEqualTo("failed")
        assertThat(fixture.row(id)["failure_code"]).isEqualTo(FailureCode.PAYLOAD_UNREADABLE)
    }

    @Test
    fun `a claim that is refused runs nothing, and the execution follows where the invocation is`() {
        val fixture = AttemptFixture("attempt_refusals")
        val ran = AtomicInteger()
        val body: (Note, Attempt) -> Unit = { _, _ -> ran.incrementAndGet() }

        val cancelled = fixture.enqueue()
        fixture.sql("UPDATE rain_jobs.job_invocation SET state = 'cancelled', finished_at = eligible_at WHERE id = ?", cancelled)
        assertThat(fixture.run(cancelled, body = body)).isEqualTo(Completion.Remove)

        val stale = fixture.enqueue()
        assertThat(fixture.run(stale, generation = 1, body = body)).describedAs("another generation").isEqualTo(Completion.Remove)

        val later = fixture.enqueue()
        fixture.sql("UPDATE rain_jobs.job_invocation SET eligible_at = eligible_at + interval '1 minute' WHERE id = ?", later)
        assertThat(fixture.run(later, body = body)).isEqualTo(Completion.Reschedule(Fixtures.START.plusSeconds(60)))

        val leased = fixture.enqueue()
        val otherLease = Fixtures.START.plusSeconds(20)
        fixture.sql(
            "UPDATE rain_jobs.job_invocation SET state = 'running', lease_token = gen_random_uuid(), lease_expires_at = ? WHERE id = ?",
            java.sql.Timestamp.from(otherLease),
            leased,
        )
        assertThat(fixture.run(leased, body = body)).isEqualTo(Completion.Reschedule(otherLease))

        val lapsed = fixture.enqueue()
        fixture.sql(
            "UPDATE rain_jobs.job_invocation SET state = 'running', lease_token = gen_random_uuid(), lease_expires_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Fixtures.START.minusSeconds(1)),
            lapsed,
        )
        assertThat(fixture.run(lapsed, body = body)).isEqualTo(Completion.Reschedule(Fixtures.START.plus(AttemptFixture.REAPER)))

        assertThat(fixture.execution { _, _ -> ran.incrementAndGet() }.attempt(JobTaskData(java.util.UUID(0, 9), 0)))
            .describedAs("no invocation at all")
            .isEqualTo(Completion.Remove)
        assertThat(ran.get()).isZero()
        assertThat(fixture.gate.inFlightOf(fixture.definition.name)).isZero()
    }
}
