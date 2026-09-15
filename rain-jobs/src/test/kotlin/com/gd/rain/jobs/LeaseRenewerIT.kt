package com.gd.rain.jobs

import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.keyOf
import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.internal.ledger.AttemptLedger
import com.gd.rain.jobs.internal.ledger.LeaseRef
import com.gd.rain.jobs.internal.ledger.RenewalAnswer
import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Gap 1: what the per-process renewer does with each answer the database gives, and with no answer at all. */
@Tag("integration")
class LeaseRenewerIT {
    @Test
    fun `a lease whose token was replaced is lost at once, and the attempt is interrupted and ends uncharged`() {
        val fixture = AttemptFixture("renewer_lost")
        val id = fixture.enqueue()
        val running = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val attempt =
            fixture.start(id) { _, _ ->
                running.countDown()
                try {
                    CountDownLatch(1).await(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
                } catch (stopped: InterruptedException) {
                    interrupted.countDown()
                    throw stopped
                }
            }
        Awaits.latch(running, "the attempt to run")
        val otherLease = Fixtures.START.plusSeconds(28)
        fixture.sql(
            "UPDATE rain_jobs.job_invocation SET lease_token = gen_random_uuid(), lease_expires_at = ? WHERE id = ?",
            Timestamp.from(otherLease),
            id,
        )

        val report = fixture.renewer.renewOnce()

        assertThat(report.lost).containsExactly(id)
        Awaits.latch(interrupted, "the attempt to be interrupted")
        assertThat(attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(Completion.Reschedule(otherLease))
        assertThat(fixture.row(id)["retry_spent"]).isEqualTo(0)
        assertThat(fixture.row(id)["failure_code"]).isNull()
    }

    @Test
    fun `a round that gets no answer revokes nothing before the horizon, and the next answer renews`() {
        val failures = AtomicInteger(1)
        val fixture = AttemptFixture("renewer_blip", decorateLedger = { real -> failingRenewals(real, failures) })
        val id = fixture.enqueue()
        val running = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val attempt =
            fixture.start(id) { _, _ ->
                running.countDown()
                Awaits.latch(finish, "the test to finish the attempt")
            }
        Awaits.latch(running, "the attempt to run")
        fixture.clock.advance(Duration.ofSeconds(10))

        val blip = fixture.renewer.renewOnce()

        assertThat(blip.failure).isInstanceOf(DataAccessResourceFailureException::class.java)
        assertThat(blip.expired).isEmpty()

        fixture.clock.advance(Duration.ofSeconds(10))
        val answered = fixture.renewer.renewOnce()

        assertThat(answered.renewed).containsExactly(id)
        assertThat(fixture.instant(id, "lease_expires_at")).isEqualTo(Fixtures.START.plusSeconds(20).plus(fixture.ttl))
        finish.countDown()
        assertThat(attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(Completion.Remove)
        assertThat(fixture.row(id)["state"]).isEqualTo("succeeded")
    }

    @Test
    fun `rounds without an answer past the horizon revoke the lease`() {
        val fixture = AttemptFixture("renewer_horizon", decorateLedger = { real -> failingRenewals(real, AtomicInteger(Int.MAX_VALUE)) })
        val id = fixture.enqueue()
        val running = CountDownLatch(1)
        val attempt =
            fixture.start(id) { _, _ ->
                running.countDown()
                CountDownLatch(1).await(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
            }
        Awaits.latch(running, "the attempt to run")

        fixture.clock.advance(fixture.ttl.minusMillis(1))
        assertThat(fixture.renewer.renewOnce().expired).isEmpty()
        fixture.clock.advance(Duration.ofMillis(1))
        val report = fixture.renewer.renewOnce()

        assertThat(report.expired).containsExactly(id)
        assertThat(attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS))
            .describedAs("the lease lapsed in the database too, so the reaper takes it within an interval")
            .isEqualTo(Completion.Reschedule(Fixtures.START.plus(fixture.ttl).plus(AttemptFixture.REAPER)))
        assertThat(fixture.row(id)["retry_spent"]).isEqualTo(0)
    }

    @Test
    fun `a lease whose row a fenced effect holds is busy, not lost`() {
        val fixture = AttemptFixture("renewer_busy")
        val id = fixture.enqueue()
        val inEffect = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val attempt =
            fixture.start(id) { _, work ->
                work.fenced(listOf(Exclusively(keyOf("busy")))) {
                    inEffect.countDown()
                    Awaits.latch(finish, "the test to finish the effect")
                }
            }
        Awaits.latch(inEffect, "the fenced effect to hold the row")

        val report = fixture.renewer.renewOnce()

        assertThat(report.busy).containsExactly(id)
        assertThat(report.lost).isEmpty()
        finish.countDown()
        assertThat(attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(Completion.Remove)
    }

    private fun failingRenewals(
        real: AttemptLedger,
        failures: AtomicInteger,
    ): AttemptLedger =
        object : AttemptLedger by real {
            override fun renew(
                leases: Collection<LeaseRef>,
                expiresAt: Instant,
            ): RenewalAnswer {
                if (failures.getAndDecrement() > 0) throw DataAccessResourceFailureException("connection reset")
                return real.renew(leases, expiresAt)
            }
        }
}
