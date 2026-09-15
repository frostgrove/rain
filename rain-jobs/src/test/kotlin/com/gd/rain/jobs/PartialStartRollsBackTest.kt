package com.gd.rain.jobs

import com.gd.rain.jobs.internal.worker.REAL_GRACE_WAIT
import com.gd.rain.jobs.internal.worker.SchedulerSpec
import com.gd.rain.jobs.support.FakeScheduler
import com.gd.rain.jobs.support.FakeWorker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Gap 7: a start that fails part-way stops what it started, the renewer too, and reports the failure. */
class PartialStartRollsBackTest {
    @Test
    fun `a scheduler that fails to start stops the ones already started and the renewer, and the start fails`() {
        val fake =
            FakeWorker(listOf("alpha", "beta", "gamma"), graceWait = REAL_GRACE_WAIT) { name, events ->
                FakeScheduler(
                    SchedulerSpec(name, 1, emptyList(), emptyList()),
                    events,
                    onStart = { if (name == "beta") throw IllegalStateException("beta cannot reach the database") },
                )
            }

        assertThatThrownBy { fake.worker.start() }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("beta")

        assertThat(fake.worker.isRunning).isFalse()
        assertThat(fake.events.first()).isEqualTo("start renewer")
        assertThat(fake.events).contains("start alpha", "start beta", "stop alpha", "stop beta", "stop renewer")
        assertThat(fake.events).describedAs("nothing after the failure is started").doesNotContain("start gamma")
        assertThat(fake.events.last()).isEqualTo("stop renewer")
        assertThat(fake.ticker.running()).isFalse()
    }

    @Test
    fun `a worker that started cleanly stops everything once, schedulers before the renewer`() {
        val fake =
            FakeWorker(listOf("alpha", "beta"), graceWait = REAL_GRACE_WAIT) { name, events ->
                FakeScheduler(SchedulerSpec(name, 1, emptyList(), emptyList()), events)
            }

        fake.worker.start()
        fake.worker.stop()
        fake.worker.stop()

        assertThat(fake.events.filter { it.startsWith("stop") }).hasSize(3)
        assertThat(fake.events.last()).isEqualTo("stop renewer")
        assertThat(fake.worker.report().running).isFalse()
    }
}
