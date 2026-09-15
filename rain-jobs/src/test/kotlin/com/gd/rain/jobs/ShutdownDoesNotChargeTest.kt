package com.gd.rain.jobs

import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.execution.AttemptHandle
import com.gd.rain.jobs.internal.execution.AttemptThreads
import com.gd.rain.jobs.internal.execution.AwaitResult
import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.internal.execution.VirtualAttemptThreads
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.ScriptedExecution
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * Gap 7: when shutdown interrupts the scheduler thread waiting for an attempt, the attempt is interrupted and released
 * uncharged — no retry spent, due again at once — and the scheduler thread keeps its interrupt.
 */
class ShutdownDoesNotChargeTest {
    @Test
    fun `an interrupted wait releases the invocation uncharged and reschedules it now`() {
        val started = CountDownLatch(1)
        val bodyInterrupted = CountDownLatch(1)
        val threads =
            AttemptThreads { name, body ->
                val real = VirtualAttemptThreads.start(name, body)
                AttemptHandle { _ ->
                    Awaits.latch(started, "the body to start")
                    Thread.currentThread().interrupt()
                    real.await(java.time.Duration.ofDays(1)).also { check(it == AwaitResult.INTERRUPTED) }
                }
            }
        val scripted =
            ScriptedExecution(threads) { _, _ ->
                started.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (interrupted: InterruptedException) {
                    bodyInterrupted.countDown()
                    throw interrupted
                }
            }
        scripted.clock.advance(java.time.Duration.ofSeconds(3))

        val completion = scripted.execution.attempt(JobTaskData(UUID(0, 1), 0))
        val schedulerThreadInterrupted = Thread.interrupted()

        val now = Fixtures.START.plusSeconds(3)
        assertThat(completion).isEqualTo(Completion.Reschedule(now))
        assertThat(scripted.ledger.writes).containsExactly("release due $now")
        assertThat(schedulerThreadInterrupted).describedAs("the scheduler thread's interrupt is restored").isTrue()
        Awaits.latch(bodyInterrupted, "the attempt body to be interrupted")
    }

    @Test
    fun `a body that stops on the shutdown with any exception is still an uncharged release`() {
        val started = CountDownLatch(1)
        val threads =
            AttemptThreads { name, body ->
                val real = VirtualAttemptThreads.start(name, body)
                AttemptHandle { _ ->
                    Awaits.latch(started, "the body to start")
                    Thread.currentThread().interrupt()
                    real.await(java.time.Duration.ofDays(1))
                }
            }
        val scripted =
            ScriptedExecution(threads) { _, _ ->
                started.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    error("a handler that turns the interrupt into a failure of its own")
                }
            }

        scripted.execution.attempt(JobTaskData(UUID(0, 1), 0))
        Thread.interrupted()

        assertThat(scripted.ledger.writes).containsExactly("release due ${Fixtures.START}")
    }
}
