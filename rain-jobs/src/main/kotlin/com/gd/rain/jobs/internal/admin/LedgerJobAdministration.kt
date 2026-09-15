package com.gd.rain.jobs.internal.admin

import com.gd.rain.jobs.IntentConflictException
import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.JobsProperties
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.admin.CancelOutcome
import com.gd.rain.jobs.admin.DeadLetterCursor
import com.gd.rain.jobs.admin.DeadLetterPage
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.jobs.admin.JobDefinitionView
import com.gd.rain.jobs.admin.RedriveOutcome
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.ledger.AdministrationLedger
import com.gd.rain.jobs.internal.ledger.DedupeMode
import com.gd.rain.jobs.internal.ledger.IntentLedger
import com.gd.rain.jobs.internal.ledger.Reservation
import com.gd.rain.jobs.internal.queue.SchedulerWorkQueue
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.TaskInstance
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.util.UUID

internal class LedgerJobAdministration(
    private val catalog: JobCatalog,
    private val properties: JobsProperties,
    private val ledger: AdministrationLedger,
    private val intents: IntentLedger,
    private val client: SchedulerClient,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) : JobAdministration {
    override fun deadLetters(
        definition: String?,
        after: DeadLetterCursor?,
        limit: Int,
    ): DeadLetterPage {
        require(limit in 1..JobAdministration.MAX_PAGE) { "a dead-letter page holds 1..${JobAdministration.MAX_PAGE} items, got $limit" }
        val rows = ledger.deadLetters(definition, after, limit)
        val items = rows.take(limit)
        val next = if (rows.size > limit) items.last().let { DeadLetterCursor(it.finishedAt, it.id) } else null
        return DeadLetterPage(items, next)
    }

    override fun redrive(invocation: UUID): RedriveOutcome = checkNotNull(transactions.execute { redriveInTransaction(invocation) })

    private fun redriveInTransaction(invocation: UUID): RedriveOutcome {
        val candidate = ledger.lockForRedrive(invocation) ?: return RedriveOutcome.NotFound
        if (candidate.state !in JobState.DEAD_LETTERS) return RedriveOutcome.NotTerminal(candidate.state)
        val definition = catalog.definition(candidate.definition) ?: return RedriveOutcome.UnknownDefinition(candidate.definition)
        val profile = catalog.profileOf(definition)
        val now = clock.instant()
        val key = candidate.dedupeKey
        if (candidate.dedupeMode != DedupeMode.NONE && key != null) {
            reserveAgain(definition.name, profile.id, key, candidate.dedupeMode, invocation)?.let { return RedriveOutcome.Deduplicated(it) }
        }
        val generation =
            checkNotNull(ledger.requeueForRedrive(invocation, profile.id, profile.retries, now)) {
                "invocation $invocation was locked as ${candidate.state.wire} and is no longer failed or dead"
            }
        val data = JobTaskData(invocation, generation)
        val instance =
            TaskInstance
                .Builder<JobTaskData>(definition.name, data.instanceId)
                .data(data)
                .priority(candidate.priority)
                .build()
        check(client.schedule(instance, now, SchedulerClient.ScheduleOptions.WHEN_EXISTS_DO_NOTHING)) {
            "db-scheduler already holds execution ${data.instanceId}, whose generation this redrive just raised"
        }
        return RedriveOutcome.Redriven(invocation, generation)
    }

    /** Null once the reservation is this invocation's again; otherwise the invocation holding it. */
    private fun reserveAgain(
        definition: String,
        profile: String,
        key: String,
        mode: DedupeMode,
        invocation: UUID,
    ): UUID? {
        repeat(SchedulerWorkQueue.PLACEMENTS) {
            when (val reservation = intents.reserve(definition, profile, key, mode, invocation, clock.instant())) {
                Reservation.Reserved -> return null
                is Reservation.Held -> reservation.holder?.let { return it }
            }
        }
        throw IntentConflictException(definition, key, SchedulerWorkQueue.PLACEMENTS)
    }

    override fun cancelBySubject(subject: SubjectKey): CancelOutcome {
        var cancelled = 0L
        var batches = 0
        while (true) {
            val removed = checkNotNull(transactions.execute { ledger.cancelLive(subject, JobAdministration.CANCEL_BATCH, clock.instant()) })
            batches++
            cancelled += removed
            if (removed < JobAdministration.CANCEL_BATCH) return CancelOutcome(cancelled, batches)
        }
    }

    override fun definitions(): List<JobDefinitionView> =
        catalog.definitions.map { definition ->
            JobDefinitionView(
                name = definition.name,
                profile = definition.profile,
                payloadType = definition.payloadType.name,
                workers = checkNotNull(properties.workers[definition.name]) { "definition ${definition.name} has no worker ceiling" },
            )
        }
}
