package com.gd.rain.jobs.support

import com.gd.rain.jobs.JacksonJobPayloadCodec
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.JobsProperties
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.jobs.SchedulerProperties
import com.gd.rain.jobs.internal.execution.AttemptStatementTimeout
import com.gd.rain.jobs.internal.execution.VirtualAttemptThreads
import com.gd.rain.jobs.internal.ledger.JooqAttemptLedger
import com.gd.rain.jobs.internal.worker.DbManagedScheduler
import com.gd.rain.jobs.internal.worker.DbSchedulerFactory
import com.gd.rain.jobs.internal.worker.JobsWorker
import com.gd.rain.jobs.internal.worker.REAL_GRACE_WAIT
import com.gd.rain.jobs.internal.worker.WorkerAssembly
import com.gd.rain.jobs.internal.worker.WorkerParts
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.persistence.lock.JooqAdvisoryLockStore
import com.gd.rain.persistence.tx.TransactionRetry
import java.time.Duration

/**
 * A worker over one fresh database, assembled by the production assembly with real db-scheduler schedulers. Polling
 * happens at start and when a test triggers it, never on a timer the test would have to wait for.
 */
internal class WorkerFixture(
    prefix: String,
    profiles: List<JobProfile>,
    definitions: List<JobDefinition<*>>,
    workers: Map<String, Int>,
    handlers: (QueueFixture) -> List<JobHandler<*>>,
    recurring: List<RecurringWork> = emptyList(),
    grace: Duration = Duration.ofSeconds(10),
) {
    val queue = QueueFixture(prefix, profiles, definitions)
    val properties =
        JobsProperties(
            workers = workers,
            requiredRecurring = recurring.map { it.name },
            drainGrace = grace,
            reservedConnections = 0,
            scheduler = SchedulerProperties(pollInterval = Duration.ofHours(1)),
        )
    private val statements = JooqAdvisoryLockStore(queue.database.dsl)
    val worker: JobsWorker

    init {
        queue.database.transactions.setTransactionExecutionListeners(listOf(AttemptStatementTimeout({ statements }, queue.clock)))
        val parts =
            WorkerParts(
                catalog = queue.catalog,
                handlers = handlers(queue),
                recurring = recurring,
                properties = properties,
                ledger = JooqAttemptLedger(queue.database.dsl),
                locks =
                    AdvisoryLocks(
                        queue.database.transactions,
                        statements,
                        TransactionRetry(1, Duration.ofMillis(10), Duration.ofMillis(10)),
                        Duration.ofSeconds(5),
                    ),
                statements = statements,
                threads = VirtualAttemptThreads,
                codec = JacksonJobPayloadCodec(),
                jitter = { 0 },
                ids = Fixtures.ids,
                clock = queue.clock,
                host = "it-host",
                meters = null,
            )
        worker =
            JobsWorker(
                assemble = { WorkerAssembly.assemble(parts) },
                factory = DbSchedulerFactory(queue.database.dataSource, properties.scheduler, grace, "it-host", queue.clock, null),
                grace = grace,
                graceWait = REAL_GRACE_WAIT,
            )
    }

    fun scheduler(name: String): DbManagedScheduler = worker.schedulers().single { it.spec.name == name } as DbManagedScheduler

    /** The worker pool size db-scheduler was really built with; it exposes no getter, so the field is read. */
    fun threadsOf(name: String): Int {
        val field = scheduler(name).scheduler.javaClass.getDeclaredField("threadpoolSize")
        field.isAccessible = true
        return field.getInt(scheduler(name).scheduler)
    }
}
