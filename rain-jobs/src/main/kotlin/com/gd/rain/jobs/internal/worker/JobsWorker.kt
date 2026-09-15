package com.gd.rain.jobs.internal.worker

import com.gd.rain.jobs.JobsHealth
import com.gd.rain.jobs.JobsHealthReport
import com.gd.rain.jobs.RenewerHealth
import com.gd.rain.jobs.SchedulerHealth
import com.gd.rain.jobs.internal.execution.LeaseRenewer
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Everything a started worker runs, assembled when it starts (after the bean-time checks have passed). */
internal class WorkerRuntime(
    val specs: List<SchedulerSpec>,
    val renewer: LeaseRenewer,
    val ticker: RenewalTicker,
    val wedged: Map<String, AtomicInteger>,
)

/** Waits for every stop to finish, for at most the grace. A test decides when the wait gives up. */
internal fun interface GraceWait {
    fun await(
        done: CountDownLatch,
        grace: Duration,
    ): Boolean
}

internal val REAL_GRACE_WAIT: GraceWait = GraceWait { done, grace -> done.await(grace.toNanos(), TimeUnit.NANOSECONDS) }

/** What a stop left behind: the schedulers that had not finished stopping when the grace ran out. */
internal data class StopReport(
    val unfinished: List<String>,
    val failed: Map<String, Throwable>,
)

/**
 * The worker role's one lifecycle: the lease renewer and every scheduler.
 *
 * Start: the renewer first, then each scheduler; a scheduler that fails to start stops every scheduler already
 * started and the renewer, and the failure propagates. Stop: every scheduler at once, each on a thread of its own,
 * bounded together by one drain grace; then the renewer, which kept draining attempts' leases alive until then.
 */
public class JobsWorker internal constructor(
    private val assemble: () -> WorkerRuntime,
    private val factory: SchedulerFactory,
    private val grace: Duration,
    private val graceWait: GraceWait,
) : SmartLifecycle,
    JobsHealth {
    @Volatile
    private var runtime: WorkerRuntime? = null

    @Volatile
    private var schedulers: List<ManagedScheduler> = emptyList()

    @Volatile
    private var running = false

    @Volatile
    internal var lastStop: StopReport? = null
        private set

    @Synchronized
    override fun start() {
        if (running) return
        val assembled = assemble()
        assembled.ticker.start()
        val up = mutableListOf<ManagedScheduler>()
        try {
            assembled.specs.forEach { spec ->
                val scheduler = factory.create(spec)
                up += scheduler
                scheduler.start()
                log.info("started job scheduler {} with {} threads", spec.name, spec.threads)
            }
        } catch (failure: Throwable) {
            log.error("job scheduler start failed; stopping the {} already started", up.size)
            lastStop = stopAll(up)
            assembled.ticker.stop()
            throw failure
        }
        runtime = assembled
        schedulers = up
        running = true
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        lastStop = stopAll(schedulers)
        runtime?.ticker?.stop()
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = PHASE

    override fun report(): JobsHealthReport {
        val current = runtime
        return JobsHealthReport(
            running = running,
            schedulers =
                schedulers.map { scheduler ->
                    SchedulerHealth(
                        name = scheduler.spec.name,
                        threads = scheduler.spec.threads,
                        started = scheduler.started(),
                        shuttingDown = scheduler.shuttingDown(),
                        lastPoll = scheduler.lastPoll(),
                        wedgedAttempts = current?.wedged?.get(scheduler.spec.name)?.get() ?: 0,
                    )
                },
            leaseRenewer =
                RenewerHealth(
                    running = current?.ticker?.running() ?: false,
                    lastRound = current?.renewer?.lastRound,
                    activeLeases = current?.renewer?.activeLeases() ?: 0,
                ),
        )
    }

    internal fun schedulers(): List<ManagedScheduler> = schedulers

    private fun stopAll(targets: List<ManagedScheduler>): StopReport {
        if (targets.isEmpty()) return StopReport(emptyList(), emptyMap())
        val done = CountDownLatch(targets.size)
        val finished =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
        val failed = java.util.concurrent.ConcurrentHashMap<String, Throwable>()
        targets.forEach { scheduler ->
            Thread.ofVirtual().name("rain-jobs-stop-${scheduler.spec.name}").start {
                try {
                    scheduler.stop()
                } catch (failure: Throwable) {
                    failed[scheduler.spec.name] = failure
                } finally {
                    finished += scheduler.spec.name
                    done.countDown()
                }
            }
        }
        val all = graceWait.await(done, grace)
        val unfinished = targets.map { it.spec.name }.filterNot { all || it in finished }
        if (unfinished.isNotEmpty()) log.warn("job schedulers {} did not stop within the drain grace {}", unfinished, grace)
        failed.forEach { (name, failure) -> log.warn("job scheduler {} failed to stop cleanly", name, failure) }
        return StopReport(unfinished, failed.toMap())
    }

    internal companion object {
        private val log = LoggerFactory.getLogger(JobsWorker::class.java)

        /** Started after, and stopped before, the web server and everything a handler depends on. */
        const val PHASE: Int = SmartLifecycle.DEFAULT_PHASE - 1
    }
}

/** Drives the lease renewer's rounds. */
internal interface RenewalTicker {
    fun start()

    fun stop()

    fun running(): Boolean
}

/** One virtual thread renewing every interval until stopped. */
internal class RenewerLoop(
    private val renewer: LeaseRenewer,
    private val interval: Duration,
) : RenewalTicker {
    @Volatile
    private var stopSignal: CountDownLatch? = null

    @Synchronized
    override fun start() {
        check(stopSignal == null) { "the lease renewer is already running" }
        val signal = CountDownLatch(1)
        stopSignal = signal
        Thread.ofVirtual().name("rain-jobs-lease-renewer").start {
            while (!signal.await(interval.toNanos(), TimeUnit.NANOSECONDS)) {
                try {
                    renewer.renewOnce()
                } catch (failure: RuntimeException) {
                    log.error("a lease renewal round failed", failure)
                }
            }
        }
    }

    @Synchronized
    override fun stop() {
        stopSignal?.countDown()
        stopSignal = null
    }

    override fun running(): Boolean = stopSignal != null

    private companion object {
        val log = LoggerFactory.getLogger(RenewerLoop::class.java)
    }
}
