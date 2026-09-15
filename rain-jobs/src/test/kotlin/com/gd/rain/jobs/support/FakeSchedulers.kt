package com.gd.rain.jobs.support

import com.gd.rain.jobs.internal.execution.LeaseRenewer
import com.gd.rain.jobs.internal.worker.GraceWait
import com.gd.rain.jobs.internal.worker.JobsWorker
import com.gd.rain.jobs.internal.worker.ManagedScheduler
import com.gd.rain.jobs.internal.worker.RenewalTicker
import com.gd.rain.jobs.internal.worker.SchedulerFactory
import com.gd.rain.jobs.internal.worker.SchedulerSpec
import com.gd.rain.jobs.internal.worker.WorkerRuntime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** A scheduler whose start and stop do what the test says, recording the order things happened in. */
internal class FakeScheduler(
    override val spec: SchedulerSpec,
    private val events: MutableList<String>,
    private val onStart: () -> Unit = {},
    private val onStop: () -> Unit = {},
) : ManagedScheduler {
    @Volatile
    private var up = false

    override fun start() {
        events += "start ${spec.name}"
        onStart()
        up = true
    }

    override fun stop() {
        events += "stop ${spec.name}"
        onStop()
        up = false
    }

    override fun started(): Boolean = up

    override fun shuttingDown(): Boolean = false

    override fun lastPoll(): Instant? = null

    override fun triggerPoll() = Unit
}

internal class FakeTicker(
    private val events: MutableList<String>,
) : RenewalTicker {
    @Volatile
    private var up = false

    override fun start() {
        events += "start renewer"
        up = true
    }

    override fun stop() {
        events += "stop renewer"
        up = false
    }

    override fun running(): Boolean = up
}

internal class FakeWorker(
    names: List<String>,
    grace: Duration = Duration.ofSeconds(30),
    graceWait: GraceWait,
    behaviour: (String, MutableList<String>) -> FakeScheduler,
) {
    val events: MutableList<String> = CopyOnWriteArrayList()
    val ticker = FakeTicker(events)
    val worker =
        JobsWorker(
            assemble = {
                WorkerRuntime(
                    specs = names.map { SchedulerSpec(it, 1, emptyList(), emptyList()) },
                    renewer =
                        LeaseRenewer(
                            ScriptedLedger(),
                            com.gd.rain.test
                                .MutableClock(Fixtures.START),
                            Duration.ofSeconds(30),
                        ),
                    ticker = ticker,
                    wedged = emptyMap(),
                )
            },
            factory = SchedulerFactory { spec -> behaviour(spec.name, events) },
            grace = grace,
            graceWait = graceWait,
        )
}
