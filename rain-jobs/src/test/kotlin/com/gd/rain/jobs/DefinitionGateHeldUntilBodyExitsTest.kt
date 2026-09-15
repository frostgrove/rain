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
import com.gd.rain.jobs.support.ScriptedLedger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gap 6: an attempt whose budget ran out while its body ignores the interrupt frees the scheduler thread — it is a
 * charged retry at once — but keeps its definition's slot until the body really exits, and counts as wedged meanwhile.
 */
class DefinitionGateHeldUntilBodyExitsTest {
    @Test
    fun `the gate slot and the wedged count are released by the body's exit, not by the timeout`() {
        val started = CountDownLatch(1)
        val unwedge = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val interruptsIgnored =
            java.util.concurrent.atomic
                .AtomicInteger()
        // The body starts for real; the scheduler thread's wait answers "timed out" as soon as the body is running.
        val threads =
            AttemptThreads { name, body ->
                val real = VirtualAttemptThreads.start(name) { body().also { exited.countDown() } }
                AttemptHandle { _ ->
                    Awaits.latch(started, "the body to start")
                    real.await(java.time.Duration.ZERO).let { if (it == AwaitResult.FINISHED) it else AwaitResult.TIMED_OUT }
                }
            }
        val scripted =
            ScriptedExecution(threads) { _, _ ->
                started.countDown()
                // A statement stuck on a socket: the interrupt does not end it.
                var released = false
                while (!released) {
                    try {
                        released = unwedge.await(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
                        check(released) { "the test never unwedged the body" }
                    } catch (_: InterruptedException) {
                        interruptsIgnored.incrementAndGet()
                    }
                }
            }

        val completion = scripted.execution.attempt(JobTaskData(UUID(0, 1), 0))

        assertThat(completion).isEqualTo(Completion.Reschedule(Fixtures.START.plus(Fixtures.profile().backoff.initial)))
        assertThat(scripted.ledger.writes).containsExactly(
            "retry attempt_timeout until ${Fixtures.START.plus(Fixtures.profile().backoff.initial)}",
        )
        assertThat(scripted.gate.inFlightOf(ScriptedLedger.DEFINITION)).describedAs("the wedged body keeps its slot").isEqualTo(1)
        assertThat(scripted.wedged.get()).isEqualTo(1)

        val refusedWhileWedged = scripted.execution.attempt(JobTaskData(UUID(0, 2), 0))

        assertThat(refusedWhileWedged).isEqualTo(Completion.Reschedule(Fixtures.START.plusSeconds(2)))
        assertThat(scripted.ledger.claims.get()).describedAs("a full gate claims nothing").isEqualTo(1)

        unwedge.countDown()
        Awaits.latch(exited, "the wedged body to exit")
        Awaits.until("the gate slot to be left") { scripted.gate.inFlightOf(ScriptedLedger.DEFINITION) == 0 }
        assertThat(scripted.wedged.get()).isZero()
        assertThat(interruptsIgnored.get()).describedAs("the timeout did interrupt the body").isEqualTo(1)
    }
}
