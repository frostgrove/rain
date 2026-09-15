package com.gd.rain.jobs.internal.execution

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class AwaitResult {
    FINISHED,
    TIMED_OUT,

    /** The waiting thread was interrupted; the body may still be running. */
    INTERRUPTED,
}

/** A started attempt body, awaited by the scheduler thread for at most a budget. */
internal fun interface AttemptHandle {
    fun await(budget: Duration): AwaitResult
}

/** Where attempt bodies run. The production form is one virtual thread per attempt; a test drives the wait by hand. */
internal fun interface AttemptThreads {
    fun start(
        name: String,
        body: () -> Unit,
    ): AttemptHandle
}

internal object VirtualAttemptThreads : AttemptThreads {
    override fun start(
        name: String,
        body: () -> Unit,
    ): AttemptHandle {
        val done = CountDownLatch(1)
        Thread.ofVirtual().name(name).start {
            try {
                body()
            } finally {
                done.countDown()
            }
        }
        return AttemptHandle { budget ->
            try {
                if (done.await(maxOf(budget.toNanos(), 0L), TimeUnit.NANOSECONDS)) AwaitResult.FINISHED else AwaitResult.TIMED_OUT
            } catch (_: InterruptedException) {
                AwaitResult.INTERRUPTED
            }
        }
    }
}
