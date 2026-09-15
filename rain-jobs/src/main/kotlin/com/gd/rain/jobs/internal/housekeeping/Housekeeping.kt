package com.gd.rain.jobs.internal.housekeeping

import com.gd.rain.jobs.FailureCode
import com.gd.rain.jobs.Jitter
import com.gd.rain.jobs.ReaperProperties
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.jobs.RetentionProperties
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.JobTopology
import com.gd.rain.jobs.internal.ledger.HousekeepingLedger
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration
import java.util.UUID

internal data class ReapReport(
    val requeued: List<UUID>,
    val dead: List<UUID>,
    val unknownDefinition: List<UUID>,
)

/**
 * Returns running invocations whose lease lapsed — their worker is gone — to the queue with one retry charged, or buries
 * them when the budget is spent. An invocation of a definition this application does not declare has no profile to
 * retry under and no handler to run it: it is dead with `unknown_definition`, never retried under an assumed profile.
 *
 * One page of at most `batch` rows per pass, locked `FOR UPDATE SKIP LOCKED` in one transaction, so reapers on
 * different workers take disjoint pages. It never reads `scheduled_tasks`.
 */
internal class JobReaper(
    private val catalog: JobCatalog,
    private val ledger: HousekeepingLedger,
    private val transactions: TransactionOperations,
    private val properties: ReaperProperties,
    private val jitter: Jitter,
    private val clock: Clock,
) : RecurringWork {
    override val name: String = JobTopology.REAPER
    override val interval: Duration = properties.interval

    override fun run() {
        val report = reap()
        if (report.requeued.size + report.dead.size + report.unknownDefinition.size > 0) {
            log.warn(
                "reaped lapsed leases: {} requeued, {} dead, {} of unknown definitions",
                report.requeued.size,
                report.dead.size,
                report.unknownDefinition.size,
            )
        }
    }

    fun reap(): ReapReport =
        checkNotNull(
            transactions.execute {
                val now = clock.instant()
                val requeued = mutableListOf<UUID>()
                val dead = mutableListOf<UUID>()
                val unknown = mutableListOf<UUID>()
                ledger.lapsed(now, properties.batch).forEach { lapsed ->
                    val message = "the lease expired at ${lapsed.leaseExpiresAt}; the worker that held it did not renew it"
                    val definition = catalog.definition(lapsed.definition)
                    when {
                        definition == null -> {
                            val because = "job definition ${lapsed.definition} is not declared in this application; $message"
                            if (ledger.bury(lapsed, FailureCode.UNKNOWN_DEFINITION, because, now)) unknown += lapsed.id
                        }

                        lapsed.retrySpent >= lapsed.retryLimit -> {
                            if (ledger.bury(lapsed, FailureCode.LEASE_EXPIRED, message, now)) dead += lapsed.id
                        }

                        else -> {
                            val eligibleAt = now.plus(catalog.profileOf(definition).backoff.delay(lapsed.retrySpent, jitter))
                            if (ledger.requeue(lapsed, eligibleAt, FailureCode.LEASE_EXPIRED, message)) requeued += lapsed.id
                        }
                    }
                }
                ReapReport(requeued, dead, unknown)
            },
        )

    private companion object {
        val log = LoggerFactory.getLogger(JobReaper::class.java)
    }
}

internal data class RetentionReport(
    val deletedInvocations: Map<String, Long>,
    val deletedIntents: Map<String, Long>,
    /** Profiles owning retained rows that the catalogue does not declare; no retention is declared for them, so their rows are kept. */
    val undeclaredProfiles: Set<String>,
    /** False when the run budget ran out with batches or profiles still to look at; the next pass continues. */
    val finished: Boolean,
)

/**
 * Deletes each declared profile's terminal invocations and released reservations older than the profile's retention,
 * in single-statement batches over the partial retention indexes. A pass stops starting batches once its run budget
 * is spent and says so.
 *
 * Rows of a profile this application no longer declares have no declared retention, so they are kept, and never
 * silently: every pass walks the profiles that own retained rows, one index seek per distinct profile, and reports
 * and logs each undeclared one. Declaring a `JobProfile` with that id makes its rows expire again.
 */
internal class JobRetention(
    private val catalog: JobCatalog,
    private val ledger: HousekeepingLedger,
    private val properties: RetentionProperties,
    private val clock: Clock,
) : RecurringWork {
    override val name: String = JobTopology.RETENTION
    override val interval: Duration = properties.interval

    override fun run() {
        val report = sweep()
        if (report.undeclaredProfiles.isNotEmpty()) {
            log.warn(
                "job retention keeps the rows of profiles this application does not declare, which have no retention: {}; " +
                    "declare a JobProfile with each id to have its rows expire",
                report.undeclaredProfiles.joinToString(", "),
            )
        }
        if (!report.finished) log.warn("job retention ran out of its {} budget; the next pass continues", properties.runBudget)
    }

    fun sweep(): RetentionReport {
        val started = clock.instant()
        val deadline = started.plus(properties.runBudget)
        val invocations = linkedMapOf<String, Long>()
        val intents = linkedMapOf<String, Long>()
        for (profile in catalog.profiles) {
            val before = started.minus(profile.retention)
            val invocationsDone = drain(deadline) { ledger.deleteTerminal(profile.id, before, properties.batch) }
            invocations[profile.id] = invocationsDone.deleted
            if (!invocationsDone.finished) return RetentionReport(invocations, intents, emptySet(), finished = false)
            val intentsDone = drain(deadline) { ledger.deleteReleasedIntents(profile.id, before, properties.batch) }
            intents[profile.id] = intentsDone.deleted
            if (!intentsDone.finished) return RetentionReport(invocations, intents, emptySet(), finished = false)
        }
        val declared = catalog.profiles.map { it.id }.toSet()
        val undeclared = sortedSetOf<String>()
        for (next in listOf(ledger::nextTerminalProfile, ledger::nextReleasedIntentProfile)) {
            var after: String? = null
            while (true) {
                if (!clock.instant().isBefore(deadline)) return RetentionReport(invocations, intents, undeclared, finished = false)
                val profile = next(after) ?: break
                if (profile !in declared) undeclared += profile
                after = profile
            }
        }
        return RetentionReport(invocations, intents, undeclared, finished = true)
    }

    private data class Drained(
        val deleted: Long,
        val finished: Boolean,
    )

    private fun drain(
        deadline: java.time.Instant,
        batch: () -> Int,
    ): Drained {
        var deleted = 0L
        while (true) {
            if (!clock.instant().isBefore(deadline)) return Drained(deleted, finished = false)
            val removed = batch()
            deleted += removed
            if (removed < properties.batch) return Drained(deleted, finished = true)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(JobRetention::class.java)
    }
}
