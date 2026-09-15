package com.gd.rain.jobs

import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gap 1: a single step longer than the lease keeps its lease, because renewal does not wait for the step to end. */
@Tag("integration")
class LeaseRenewalDuringLongStepIT {
    @Test
    fun `a step running past several lease lifetimes keeps the lease and succeeds`() {
        val fixture = AttemptFixture("long_step", profile = Fixtures.profile(stepTimeout = Duration.ofMinutes(5)))
        val id = fixture.enqueue()
        val inStep = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val attempt =
            fixture.start(id) { _, work ->
                work.step(Duration.ofMinutes(5)) {
                    inStep.countDown()
                    Awaits.latch(finish, "the test to finish the step")
                }
            }
        Awaits.latch(inStep, "the long step to start")

        repeat(4) {
            fixture.clock.advance(Duration.ofSeconds(20))
            assertThat(fixture.renewer.renewOnce().renewed).containsExactly(id)
            assertThat(fixture.instant(id, "lease_expires_at")).isEqualTo(fixture.clock.instant().plus(fixture.ttl))
            assertThat(
                fixture.database.count(
                    "SELECT count(*) FROM rain_jobs.job_invocation WHERE state = 'running' AND lease_expires_at <= '${Timestamp.from(
                        fixture.clock.instant(),
                    ).toInstant()}'",
                ),
            ).describedAs("nothing for a reaper to reclaim").isZero()
        }
        assertThat(fixture.clock.instant()).isAfter(Fixtures.START.plus(fixture.ttl.multipliedBy(2)))

        finish.countDown()

        assertThat(attempt.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo(Completion.Remove)
        assertThat(fixture.row(id)["state"]).isEqualTo("succeeded")
    }
}
