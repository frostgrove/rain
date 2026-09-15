package com.gd.rain.jobs

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.JobTopology
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

/** The declared profiles and definitions, and a worker ceiling for exactly each definition. Checked in every role. */
public class JobCatalogCheck internal constructor(
    private val catalog: JobCatalog,
    private val properties: JobsProperties,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> = JobTopology.catalogProblems(catalog, properties)
}

/** Exactly one handler per declared definition, and recurring work that agrees with `rain.jobs.required-recurring`. Worker role. */
public class JobWorkerCheck internal constructor(
    private val catalog: JobCatalog,
    private val handlers: List<JobHandler<*>>,
    private val recurring: List<RecurringWork>,
    private val properties: JobsProperties,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> = JobTopology.workerProblems(catalog, handlers, recurring, properties)
}

/** The connections a worker process can hold at once, term by term. */
public data class ConnectionDemand(
    /** Σ of every profile's threads (its definitions' ceilings): each running attempt's scheduler thread writes through the pool. */
    public val profileThreads: Int,
    /** One thread per recurring task, rain's own included. */
    public val recurringThreads: Int,
    /** Schedulers built: one per profile, and one for recurring work. */
    public val schedulers: Int,
    public val reservedConnections: Int,
) {
    /** Connections db-scheduler's own threads can hold per scheduler: its due-poll thread and its three housekeeper threads. */
    public val libraryConnections: Int get() = schedulers * LIBRARY_CONNECTIONS_PER_SCHEDULER

    public val total: Int get() = profileThreads + recurringThreads + libraryConnections + RENEWER_CONNECTIONS + reservedConnections

    public companion object {
        /**
         * db-scheduler 16.12.0's `SchedulerBuilder.build()` creates a single-thread due executor and a housekeeper
         * `ScheduledThreadPool` of three threads (update heartbeats, detect dead executions, delete unresolved), each of
         * which queries the table (read from the bytecode).
         */
        public const val LIBRARY_CONNECTIONS_PER_SCHEDULER: Int = 4

        /** The per-process lease renewer issues one statement at a time. */
        public const val RENEWER_CONNECTIONS: Int = 1
    }
}

/**
 * Worker role: the connections the worker can hold, plus `rain.jobs.reserved-connections` for everything else, fit in
 * `spring.datasource.hikari.maximum-pool-size` — which has to be stated, because Hikari's own default is a number
 * nobody chose for this process.
 */
public class ConnectionDemandCheck internal constructor(
    private val catalog: JobCatalog,
    private val recurring: List<RecurringWork>,
    private val properties: JobsProperties,
    private val environment: Environment,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val maximum = Binder.get(environment).bind(POOL_SIZE, Int::class.javaObjectType).orElse(null)
        if (catalog.problems.isNotEmpty() || JobTopology.catalogProblems(catalog, properties).isNotEmpty()) {
            return listOf(
                ConfigurationProblem(PATH, ProblemCode.NOT_EVALUATED, "the job declarations have problems, so the demand is not known"),
            )
        }
        if (maximum == null) {
            return listOf(
                ConfigurationProblem(
                    POOL_SIZE,
                    ProblemCode.REQUIRED,
                    "no value is provided; the job worker's connection demand is checked against it",
                ),
            )
        }
        val demand = demand()
        if (demand.total <= maximum) return emptyList()
        return listOf(
            ConfigurationProblem(
                POOL_SIZE,
                ProblemCode.CONTRADICTS,
                "is $maximum, below the worker's demand of ${demand.total}: ${demand.profileThreads} profile threads + " +
                    "${demand.recurringThreads} recurring threads + ${demand.libraryConnections} db-scheduler connections " +
                    "(${demand.schedulers} schedulers × ${ConnectionDemand.LIBRARY_CONNECTIONS_PER_SCHEDULER}) + " +
                    "${ConnectionDemand.RENEWER_CONNECTIONS} lease renewer + ${demand.reservedConnections} rain.jobs.reserved-connections",
            ),
        )
    }

    public fun demand(): ConnectionDemand {
        val plans = JobTopology.profilePlans(catalog, properties)
        val recurringThreads = JobTopology.BUILT_IN_RECURRING.size + recurring.size
        return ConnectionDemand(
            profileThreads = plans.sumOf { it.threads },
            recurringThreads = recurringThreads,
            schedulers = plans.size + 1,
            reservedConnections = properties.reservedConnections,
        )
    }

    private companion object {
        const val POOL_SIZE = "spring.datasource.hikari.maximum-pool-size"
        const val PATH = "rain.jobs.connection-demand"
    }
}

/** The codes rain-jobs lets reach a client. */
public object JobsErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-jobs"

    public val INTENT_CONFLICT: ErrorCode =
        ErrorCode.of("job_intent_conflict", "the work could not be placed because its reservation kept changing hands; try again")

    override val codes: List<ErrorCode> = listOf(INTENT_CONFLICT)
}

/** An order refused for a reservation that kept changing holder may succeed when repeated. */
public object JobsFaultTranslator : FaultTranslator {
    override fun translate(failure: Throwable): Fault? =
        (failure as? IntentConflictException)?.let { Fault.retryable(JobsErrorCodes.INTENT_CONFLICT) }
}
