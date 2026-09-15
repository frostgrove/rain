package com.gd.rain.jobs

import com.gd.rain.jobs.internal.execution.AwaitResult
import com.gd.rain.jobs.internal.execution.VirtualAttemptThreads
import com.gd.rain.jobs.support.Awaits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch

class AttemptThreadsTest {
    @Test
    fun `a body that returns is finished`() {
        val handle = VirtualAttemptThreads.start("finishes") { }

        assertThat(handle.await(Duration.ofSeconds(Awaits.BOUND_SECONDS))).isEqualTo(AwaitResult.FINISHED)
    }

    @Test
    fun `a body still running when the budget is spent is timed out, and runs on a thread of its own`() {
        val release = CountDownLatch(1)
        val bodyThread =
            java.util.concurrent.atomic
                .AtomicReference<Thread>()
        val handle =
            VirtualAttemptThreads.start("blocks") {
                bodyThread.set(Thread.currentThread())
                Awaits.latch(release, "the test to release the body")
            }

        assertThat(handle.await(Duration.ZERO)).isEqualTo(AwaitResult.TIMED_OUT)

        release.countDown()
        assertThat(handle.await(Duration.ofSeconds(Awaits.BOUND_SECONDS))).isEqualTo(AwaitResult.FINISHED)
        assertThat(bodyThread.get()).isNotSameAs(Thread.currentThread())
        assertThat(bodyThread.get().isVirtual).isTrue()
    }

    @Test
    fun `an interrupted waiter is told so and the body is left to its owner`() {
        val release = CountDownLatch(1)
        val handle = VirtualAttemptThreads.start("blocks") { Awaits.latch(release, "the test to release the body") }

        Thread.currentThread().interrupt()
        val answer = handle.await(Duration.ofDays(1))

        assertThat(answer).isEqualTo(AwaitResult.INTERRUPTED)
        release.countDown()
    }
}
