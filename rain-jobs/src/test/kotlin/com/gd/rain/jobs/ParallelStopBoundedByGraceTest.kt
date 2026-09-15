package com.gd.rain.jobs

import com.gd.rain.jobs.internal.worker.GraceWait
import com.gd.rain.jobs.internal.worker.SchedulerSpec
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.FakeScheduler
import com.gd.rain.jobs.support.FakeWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Gap 7: schedulers stop at the same time, and the whole stop is bounded by one drain grace — a scheduler that never
 * finishes stopping does not hold the others, or the process, past it.
 */
class ParallelStopBoundedByGraceTest {
    @Test
    fun `stops run concurrently and the lifecycle returns when the grace is spent, naming what did not stop`() {
        val bothStopping = CyclicBarrier(2)
        val stuckStopEntered = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val graces = mutableListOf<Duration>()
        // The grace "runs out" as soon as the two cooperative stops are done while the third is still stuck.
        val graceWait =
            GraceWait { done, grace ->
                graces += grace
                Awaits.until("the cooperative stops to finish") { done.count == 1L }
                Awaits.latch(stuckStopEntered, "the stuck stop to begin")
                false
            }
        val fake =
            FakeWorker(listOf("alpha", "beta", "stuck"), grace = Duration.ofSeconds(20), graceWait = graceWait) { name, events ->
                FakeScheduler(
                    SchedulerSpec(name, 1, emptyList(), emptyList()),
                    events,
                    onStop = {
                        when (name) {
                            // Each of these two finishes only once the other has started stopping: sequential stops would deadlock.
                            "alpha", "beta" -> {
                                bothStopping.await(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
                            }

                            else -> {
                                stuckStopEntered.countDown()
                                neverReleased.await(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
                            }
                        }
                    },
                )
            }
        fake.worker.start()

        fake.worker.stop()

        assertThat(graces).containsExactly(Duration.ofSeconds(20))
        assertThat(fake.worker.lastStop?.unfinished).containsExactly("stuck")
        assertThat(fake.worker.isRunning).isFalse()
        assertThat(
            fake.events.last(),
        ).describedAs("the renewer stops after the grace, not after the stuck scheduler").isEqualTo("stop renewer")
        neverReleased.countDown()
    }
}
