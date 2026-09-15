package com.gd.rain.jobs.internal.worker

import com.gd.rain.jobs.SchedulerProperties
import com.gd.rain.jobs.internal.Jackson3TaskSerializer
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerBuilder
import com.github.kagkarlsson.scheduler.SchedulerName
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener
import com.github.kagkarlsson.scheduler.event.SchedulerListener
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization
import com.github.kagkarlsson.scheduler.stats.MicrometerStatsRegistry
import com.github.kagkarlsson.scheduler.stats.StatsRegistryAdapter
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/** What one scheduler is built from: the tasks it executes and the recurring tasks it owns. */
internal data class SchedulerSpec(
    val name: String,
    val threads: Int,
    val tasks: List<Task<*>>,
    val recurring: List<RecurringTask<*>>,
)

/** One started-and-stopped scheduler, whatever implements it. */
internal interface ManagedScheduler {
    val spec: SchedulerSpec

    fun start()

    fun stop()

    fun started(): Boolean

    fun shuttingDown(): Boolean

    fun lastPoll(): Instant?

    /** Polls for due work now instead of at the next interval. */
    fun triggerPoll()
}

internal fun interface SchedulerFactory {
    fun create(spec: SchedulerSpec): ManagedScheduler
}

/**
 * db-scheduler schedulers on rain's table. Each is built on the raw DataSource — its poller, heartbeat writer and
 * completions must never join a caller's transaction — with the PostgreSQL customization stated rather than detected.
 */
internal class DbSchedulerFactory(
    private val dataSource: DataSource,
    private val properties: SchedulerProperties,
    /** Every scheduler stops within this: db-scheduler waits its `shutdownMaxWait` twice (drain, then after interrupting). */
    private val grace: Duration,
    private val host: String,
    private val clock: Clock,
    private val meters: MeterRegistry?,
) : SchedulerFactory {
    override fun create(spec: SchedulerSpec): ManagedScheduler {
        val polls = PollListener(clock)
        val builder =
            SchedulerBuilder(dataSource, spec.tasks)
                .startTasks(spec.recurring)
                .schedulerName(SchedulerName.Fixed("$host#${spec.name}"))
                .threads(spec.threads)
                .pollingInterval(properties.pollInterval)
                .heartbeatInterval(properties.heartbeatInterval)
                .missedHeartbeatsLimit(properties.missedHeartbeats)
                .shutdownMaxWait(grace.dividedBy(2))
                .deleteUnresolvedAfter(NEVER)
                .tableName(TABLE)
                .serializer(Jackson3TaskSerializer())
                .jdbcCustomization(PostgreSqlJdbcCustomization(false, false))
                .enablePriority()
                .clock { clock.instant() }
                .addSchedulerListener(polls)
        meters?.let { builder.addSchedulerListener(StatsRegistryAdapter(MicrometerStatsRegistry(it, spec.tasks + spec.recurring))) }
        return DbManagedScheduler(spec, builder.build(), polls)
    }

    internal companion object {
        const val TABLE: String = "rain_jobs.scheduled_tasks"

        /**
         * Every scheduler sees the other schedulers' task names as unresolved and must leave their executions alone;
         * db-scheduler deletes executions unresolved for longer than this, so it is set beyond any process lifetime.
         */
        val NEVER: Duration = Duration.ofDays(36_500)
    }
}

internal class DbManagedScheduler(
    override val spec: SchedulerSpec,
    val scheduler: Scheduler,
    private val polls: PollListener,
) : ManagedScheduler {
    override fun start(): Unit = scheduler.start()

    override fun stop(): Unit = scheduler.stop()

    override fun started(): Boolean = scheduler.schedulerState.isStarted

    override fun shuttingDown(): Boolean = scheduler.schedulerState.isShuttingDown

    override fun lastPoll(): Instant? = polls.last

    override fun triggerPoll(): Unit = scheduler.triggerCheckForDueExecutions()
}

internal class PollListener(
    private val clock: Clock,
) : AbstractSchedulerListener() {
    @Volatile
    var last: Instant? = null
        private set

    override fun onSchedulerEvent(type: SchedulerListener.SchedulerEventType) {
        if (type == SchedulerListener.SchedulerEventType.RAN_EXECUTE_DUE) last = clock.instant()
    }
}
