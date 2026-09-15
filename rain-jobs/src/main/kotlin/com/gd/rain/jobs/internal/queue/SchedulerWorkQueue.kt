package com.gd.rain.jobs.internal.queue

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.EnqueueOutcome
import com.gd.rain.jobs.IntentConflictException
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobPayloadCodec
import com.gd.rain.jobs.UnknownJobDefinitionException
import com.gd.rain.jobs.WorkQueue
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.ledger.DedupeMode
import com.gd.rain.jobs.internal.ledger.IntentLedger
import com.gd.rain.jobs.internal.ledger.NewInvocation
import com.gd.rain.jobs.internal.ledger.Reservation
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.TaskInstance
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * [WorkQueue] over rain's ledger and db-scheduler's client, in one transaction that joins the caller's:
 *
 * 1. with dedupe, reserve the intent first; a held key absorbs the order into its holder (one counter update, no
 *    invocation row) and stops;
 * 2. insert the invocation;
 * 3. schedule its db-scheduler execution through a client built on a transaction-aware DataSource.
 */
internal class SchedulerWorkQueue(
    private val catalog: JobCatalog,
    private val intents: IntentLedger,
    private val client: SchedulerClient,
    private val codec: JobPayloadCodec,
    private val transactions: TransactionOperations,
    private val ids: IdGenerator,
    private val clock: Clock,
) : WorkQueue {
    override fun <P : Any> enqueue(
        definition: JobDefinition<P>,
        payload: P,
        options: EnqueueOptions,
    ): EnqueueOutcome {
        val declared = catalog.definition(definition.name)
        if (declared != definition) throw UnknownJobDefinitionException(definition.name)
        require(definition.payloadType.isInstance(payload)) {
            "definition ${definition.name} takes ${definition.payloadType.name}, not ${payload::class.java.name}"
        }
        val profile = catalog.profileOf(definition)
        val payloadJson = codec.encode(payload)
        return checkNotNull(
            transactions.execute {
                val now = clock.instant()
                val id = ids.next()
                val mode = DedupeMode.of(options.dedupe)
                val key = DedupeMode.keyOf(options.dedupe)
                val absorbedBy = key?.let { place(definition.name, profile.id, it, mode, id, now) }
                if (absorbedBy != null) {
                    EnqueueOutcome.Deduplicated(absorbedBy)
                } else {
                    val eligibleAt = now.plus(options.after)
                    intents.insert(
                        NewInvocation(
                            id = id,
                            definition = definition.name,
                            profile = profile.id,
                            priority = options.priority,
                            payloadJson = payloadJson,
                            dedupeMode = mode,
                            dedupeKey = key,
                            subjectKey = options.subjectKey,
                            retryLimit = profile.retries,
                            createdAt = now,
                            eligibleAt = eligibleAt,
                        ),
                    )
                    schedule(definition.name, JobTaskData(id, 0), options.priority.value, eligibleAt)
                    EnqueueOutcome.Scheduled(id)
                }
            },
        )
    }

    /**
     * Reserves the key for [invocation] and answers null, or answers the holder the order was absorbed into. A key
     * released between the conflicting insert and the absorption is free again, so the placement runs again; after
     * [PLACEMENTS] such rounds the order is refused.
     */
    private fun place(
        definition: String,
        profile: String,
        key: String,
        mode: DedupeMode,
        invocation: UUID,
        now: Instant,
    ): UUID? {
        repeat(PLACEMENTS) {
            when (intents.reserve(definition, profile, key, mode, invocation, now)) {
                Reservation.Reserved -> return null
                is Reservation.Held -> intents.absorb(definition, key, now)?.let { return it }
            }
        }
        throw IntentConflictException(definition, key, PLACEMENTS)
    }

    private fun schedule(
        definition: String,
        data: JobTaskData,
        priority: Int,
        at: Instant,
    ) {
        val instance =
            TaskInstance
                .Builder<JobTaskData>(definition, data.instanceId)
                .data(data)
                .priority(priority)
                .build()
        val created = client.schedule(instance, at, SchedulerClient.ScheduleOptions.WHEN_EXISTS_DO_NOTHING)
        check(created) { "db-scheduler already holds execution ${data.instanceId} of $definition, which this enqueue just minted" }
    }

    internal companion object {
        /** Rounds of reserve-then-absorb before an order whose key keeps changing holder is refused. */
        const val PLACEMENTS: Int = 3
    }
}
