package com.gd.rain.jobs

import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gap 2: a terminal write checks the lease token in the statement that releases the reservation. When the invocation
 * was reclaimed while the attempt ran, the write changes nothing — neither the state nor the reservation — and the
 * execution follows where the invocation now is.
 */
@Tag("integration")
class TerminalWriteAfterReclaimIT {
    @Test
    fun `a success written after the invocation was requeued changes nothing and reschedules to the requeued eligibility`() {
        val fixture = AttemptFixture("terminal_requeued")
        val id = fixture.enqueue(Dedupe.Unique("k"))
        val running = CountDownLatch(1)
        val reclaimed = CountDownLatch(1)
        val requeuedUntil = Fixtures.START.plusSeconds(90)

        val attempt =
            fixture.start(id) { _, _ ->
                running.countDown()
                Awaits.latch(reclaimed, "the reclaim")
            }
        Awaits.latch(running, "the attempt to run")
        fixture.sql(
            "UPDATE rain_jobs.job_invocation SET state = 'queued', lease_token = NULL, lease_expires_at = NULL, " +
                "retry_spent = retry_spent + 1, " +
                "failure_code = 'lease_expired', eligible_at = ? WHERE id = ?",
            Timestamp.from(requeuedUntil),
            id,
        )
        reclaimed.countDown()

        val completion = attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)

        assertThat(completion).isEqualTo(Completion.Reschedule(requeuedUntil))
        assertThat(fixture.row(id)["state"]).isEqualTo("queued")
        assertThat(fixture.row(id)["finished_at"]).isNull()
        assertThat(fixture.heldIntents(id)).describedAs("the refused write released no reservation").isEqualTo(1)
    }

    @Test
    fun `a success written after the invocation was reclaimed as dead does not overwrite it and removes the execution`() {
        val fixture = AttemptFixture("terminal_dead")
        val id = fixture.enqueue(Dedupe.Unique("k"))
        val running = CountDownLatch(1)
        val reclaimed = CountDownLatch(1)

        val attempt =
            fixture.start(id) { _, _ ->
                running.countDown()
                Awaits.latch(reclaimed, "the reclaim")
            }
        Awaits.latch(running, "the attempt to run")
        fixture.sql(
            "UPDATE rain_jobs.job_invocation SET state = 'dead', finished_at = eligible_at, lease_token = NULL, lease_expires_at = NULL, " +
                "failure_code = 'lease_expired' WHERE id = ?",
            id,
        )
        fixture.sql("UPDATE rain_jobs.job_intent SET released_at = reserved_at WHERE invocation_id = ?", id)
        reclaimed.countDown()

        assertThat(attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(Completion.Remove)
        assertThat(fixture.row(id)["state"]).isEqualTo("dead")
        assertThat(fixture.row(id)["failure_code"]).isEqualTo(FailureCode.LEASE_EXPIRED)
    }
}
