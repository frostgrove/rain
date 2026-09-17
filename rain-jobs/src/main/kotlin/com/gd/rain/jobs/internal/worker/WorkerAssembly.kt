package com.gd.rain.jobs.internal.worker

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.Jitter
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobPayloadCodec
import com.gd.rain.jobs.JobsProperties
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.jobs.SchedulerHealth
import com.gd.rain.jobs.context.PartitionPermit
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.JobTopology
import com.gd.rain.jobs.internal.context.DurableJobContexts
import com.gd.rain.jobs.internal.execution.AttemptThreads
import com.gd.rain.jobs.internal.execution.DefinitionGate
import com.gd.rain.jobs.internal.execution.ExecutionSettings
import com.gd.rain.jobs.internal.execution.FencedEffects
import com.gd.rain.jobs.internal.execution.JobExecution
import com.gd.rain.jobs.internal.execution.LeaseRenewer
import com.gd.rain.jobs.internal.ledger.AttemptLedger
import com.gd.rain.persistence.lock.AdvisoryLockStore
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/** The collaborators a worker is assembled from. */
internal class WorkerParts(
    val catalog: JobCatalog,
    val handlers: List<JobHandler<*>>,
    /** Rain's own recurring work and the application's, together. */
    val recurring: List<RecurringWork>,
    val properties: JobsProperties,
    val ledger: AttemptLedger,
    val locks: AdvisoryLocks,
    val statements: AdvisoryLockStore,
    val threads: AttemptThreads,
    val codec: JobPayloadCodec,
    val contexts: DurableJobContexts = DurableJobContexts(emptyList()),
    val partitionPermit: PartitionPermit = PartitionPermit.NONE,
    val jitter: Jitter,
    val ids: IdGenerator,
    val clock: Clock,
    val host: String,
    val meters: MeterRegistry?,
)

internal object WorkerAssembly {
    fun assemble(parts: WorkerParts): WorkerRuntime {
        val properties = parts.properties
        val renewer = LeaseRenewer(parts.ledger, parts.clock, properties.lease.ttl)
        val fences = FencedEffects(parts.locks, parts.statements, parts.ledger, parts.clock)
        val plans = JobTopology.profilePlans(parts.catalog, properties)
        val gate = DefinitionGate(plans.flatMap { it.ceilings.entries }.associate { it.key to it.value })
        val wedged = plans.associate { it.profile.id to AtomicInteger() }
        val handlers = parts.handlers.groupBy { it.definition.name }.mapValues { it.value.single() }
        val specs =
            plans.map { plan ->
                val settings =
                    ExecutionSettings(
                        leaseTtl = properties.lease.ttl,
                        pollInterval = properties.scheduler.pollInterval,
                        reaperInterval = properties.reaper.interval,
                        pickedBy = "${parts.host}#${plan.profile.id}",
                    )
                val counter = wedged.getValue(plan.profile.id)
                parts.meters?.let { registry ->
                    Gauge.builder(WEDGED_GAUGE, counter) { it.get().toDouble() }.tag("profile", plan.profile.id).register(registry)
                }
                SchedulerSpec(
                    name = plan.profile.id,
                    threads = plan.threads,
                    tasks =
                        plan.ceilings.keys.map { name ->
                            val execution =
                                JobExecution(
                                    handler = handlers.getValue(name),
                                    profile = plan.profile,
                                    gate = gate,
                                    ledger = parts.ledger,
                                    renewer = renewer,
                                    fences = fences,
                                    threads = parts.threads,
                                    codec = parts.codec,
                                    contexts = parts.contexts,
                                    partitionPermit = parts.partitionPermit,
                                    settings = settings,
                                    wedged = counter,
                                    jitter = parts.jitter,
                                    ids = parts.ids,
                                    clock = parts.clock,
                                )
                            Tasks
                                .custom(name, JobTaskData::class.java)
                                .onFailure(
                                    FailureHandler<JobTaskData> { complete, operations ->
                                        operations.reschedule(complete, parts.clock.instant().plus(properties.reaper.interval))
                                    },
                                ).execute(execution)
                        },
                    recurring = emptyList(),
                )
            } +
                // A pool of zero threads cannot be built; with no recurring work there is no scheduler for it.
                listOfNotNull(
                    parts.recurring.takeIf { it.isNotEmpty() }?.let { recurring ->
                        SchedulerSpec(
                            name = SchedulerHealth.RECURRING,
                            threads = recurring.size,
                            tasks = emptyList(),
                            recurring = recurring.map(::recurringTask),
                        )
                    },
                )
        return WorkerRuntime(specs, renewer, RenewerLoop(renewer, properties.lease.renewInterval), wedged)
    }

    private fun recurringTask(work: RecurringWork): RecurringTask<Void> =
        Tasks.recurring(work.name, Schedules.fixedDelay(work.interval)).execute { _, _ -> work.run() }

    const val WEDGED_GAUGE: String = "rain.jobs.attempts.wedged"
}
