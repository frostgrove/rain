package com.gd.rain.event.test

import com.gd.rain.event.AppendResult
import com.gd.rain.event.CommittedEventLog
import com.gd.rain.event.CommittedLogPage
import com.gd.rain.event.CompletedReceipt
import com.gd.rain.event.EncodedChanges
import com.gd.rain.event.EncodedFact
import com.gd.rain.event.EventLimits
import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.EventOperationReceipts
import com.gd.rain.event.EventSnapshotStore
import com.gd.rain.event.EventStoreSupport
import com.gd.rain.event.EventTransaction
import com.gd.rain.event.OperationKey
import com.gd.rain.event.ReceiptClaim
import com.gd.rain.event.ReceiptCompletion
import com.gd.rain.event.ReceiptRefusal
import com.gd.rain.event.ReceiptToken
import com.gd.rain.event.RequestFingerprint
import com.gd.rain.event.SnapshotSaveResult
import com.gd.rain.event.SnapshotWrite
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StoredSnapshot
import com.gd.rain.event.StreamPage
import com.gd.rain.event.StreamRef
import com.gd.rain.event.appendFingerprint
import com.gd.rain.persistence.tx.BackingIdentity
import java.time.Clock

/**
 * Deterministic serializable reference store for tests and conformance suites.
 *
 * A callback receives a virtual transaction; all its writes are copy-on-write and become visible
 * only when the callback returns normally. This is deliberately not a production fallback and has
 * no Spring auto-configuration.
 */
public class InMemoryEventStore(
    override val limits: EventLimits = EventLimits(),
    private val clock: Clock,
    override val backing: BackingIdentity = BackingIdentity.named("rain.event.memory", "default"),
    logId: EventLogId = EventLogId.random(),
) : EventStoreSupport(),
    EventOperationReceipts,
    EventSnapshotStore,
    CommittedEventLog {
    override val origin: EventLogOrigin = EventLogOrigin(logId)
    private var committed: Map<StreamRef, List<StoredEvent>> = emptyMap()
    private var committedReceipts: Map<ReceiptAddress, MemoryReceipt> = emptyMap()
    private var committedSnapshots: Map<StreamRef, Map<Long, StoredSnapshot>> = emptyMap()
    private var nextPosition: Long = 1
    private var active: Pending? = null
    private var activeTransaction: EventTransaction? = null

    override fun <T> inCallerTransaction(block: (EventTransaction) -> T): T =
        synchronized(this) {
            check(active == null) { "memory event transactions do not nest" }
            val pending =
                Pending(
                    committed.mapValues { (_, events) -> events.toMutableList() }.toMutableMap(),
                    committedReceipts.toMutableMap(),
                    committedSnapshots.mapValues { (_, snapshots) -> snapshots.toMutableMap() }.toMutableMap(),
                    nextPosition,
                )
            active = pending
            val transaction = newTransaction()
            activeTransaction = transaction
            try {
                val result = block(transaction)
                committed = pending.streams.mapValues { (_, events) -> events.toList() }
                committedReceipts = pending.receipts.toMap()
                committedSnapshots = pending.snapshots.mapValues { (_, snapshots) -> snapshots.toMap() }
                nextPosition = pending.nextPosition
                result
            } finally {
                active = null
                activeTransaction = null
            }
        }

    override fun version(
        transaction: EventTransaction,
        stream: StreamRef,
    ): Long? = pending(transaction).streams[stream]?.lastOrNull()?.streamVersion

    override fun readStream(
        transaction: EventTransaction,
        stream: StreamRef,
        afterVersion: Long,
        limit: Int,
    ): StreamPage {
        require(afterVersion >= 0) { "after version is not negative" }
        require(limit in 1..limits.streamPageSize) { "stream page limit is outside the configured bound" }
        val events = pending(transaction).streams[stream].orEmpty().filter { it.streamVersion > afterVersion }
        return StreamPage(events.take(limit), afterVersion, events.size > limit)
    }

    override fun append(
        transaction: EventTransaction,
        expectedVersion: Long,
        changes: EncodedChanges,
        metadata: EventMetadata,
    ): AppendResult {
        require(expectedVersion >= 0) { "expected version is not negative" }
        require(changes.facts.size <= limits.batchSize) { "event batch exceeds the configured bound" }
        require(metadata.canonicalBytes().size <= limits.metadataBytes) { "metadata exceeds the configured bound" }
        val pending = pending(transaction)
        val events = pending.streams.getOrPut(changes.stream) { mutableListOf() }
        val current = events.lastOrNull()?.streamVersion ?: 0
        if (current != expectedVersion) return AppendResult.Conflict
        if (changes.facts.isEmpty()) return AppendResult.Committed(com.gd.rain.event.CommitRange.EMPTY)
        val firstVersion = current + 1
        val firstPosition = pending.nextPosition
        val canonicalMetadata = metadata.canonicalBytes()
        changes.facts.forEachIndexed { index, fact ->
            events +=
                StoredEvent(
                    stream = changes.stream,
                    streamVersion = firstVersion + index,
                    position = pending.nextPosition++,
                    fact = fact.type,
                    payload = fact.payload,
                    metadata = canonicalMetadata,
                    recordedAt = clock.instant(),
                )
        }
        return AppendResult.Committed(
            com.gd.rain.event.CommitRange(
                firstPosition = firstPosition,
                lastPosition = pending.nextPosition - 1,
                firstVersion = firstVersion,
                lastVersion = firstVersion + changes.facts.size - 1,
                count = changes.facts.size,
            ),
        )
    }

    override fun latest(
        transaction: EventTransaction,
        stream: StreamRef,
        atOrBeforeVersion: Long,
    ): StoredSnapshot? {
        require(atOrBeforeVersion > 0) { "snapshot upper version is positive" }
        return pending(transaction)
            .snapshots[stream]
            .orEmpty()
            .values
            .filter {
                it.version <= atOrBeforeVersion
            }.maxByOrNull(StoredSnapshot::version)
    }

    override fun save(
        transaction: EventTransaction,
        write: SnapshotWrite,
    ): SnapshotSaveResult {
        val pending = pending(transaction)
        require(
            pending.streams[write.stream].orEmpty().any { it.streamVersion == write.version },
        ) { "snapshot source version was not found" }
        val snapshot =
            StoredSnapshot(
                write.stream,
                write.version,
                write.fingerprint,
                write.codec,
                write.payload,
                write.checksum,
                clock.instant(),
            )
        val snapshots = pending.snapshots.getOrPut(write.stream) { mutableMapOf() }
        val prior = snapshots.putIfAbsent(write.version, snapshot)
        return when {
            prior == null -> SnapshotSaveResult.Saved(snapshot)

            prior.fingerprint == write.fingerprint &&
                prior.codec == write.codec &&
                prior.payload == write.payload &&
                prior.checksum() == write.checksum -> SnapshotSaveResult.Repeated(prior)

            else -> SnapshotSaveResult.Collision
        }
    }

    override fun prune(
        transaction: EventTransaction,
        stream: StreamRef,
        keep: Int,
        batch: Int,
    ): Int {
        require(keep >= 1) { "snapshot retention keep is positive" }
        require(batch > 0) { "snapshot retention batch is positive" }
        val snapshots = pending(transaction).snapshots[stream] ?: return 0
        val obsolete =
            snapshots.keys
                .sortedDescending()
                .drop(keep)
                .take(batch)
        obsolete.forEach(snapshots::remove)
        return obsolete.size
    }

    override fun initialCursor(): EventLogCursor = EventLogCursor.start(origin)

    override fun readCommitted(
        cursor: EventLogCursor,
        limit: Int,
    ): CommittedLogPage =
        synchronized(this) {
            require(cursor.origin == origin) { "event log cursor belongs to another log" }
            require(limit in 1..limits.logPageSize) { "event log page limit is outside the configured bound" }
            val records =
                committed.values
                    .flatten()
                    .asSequence()
                    .filter { it.position > cursor.deliveredPosition }
                    .sortedBy { it.position }
            val candidate = records.take(limit + 1).toList()
            val events = candidate.take(limit)
            val next = EventLogCursor.after(cursor, events.lastOrNull()?.position ?: cursor.deliveredPosition)
            CommittedLogPage(events, next, candidate.size > limit)
        }

    override fun claim(
        transaction: EventTransaction,
        namespace: EventNamespace,
        operation: OperationKey,
        request: RequestFingerprint,
    ): ReceiptClaim {
        val pending = pending(transaction)
        val address = ReceiptAddress(namespace, operation)
        val existing = pending.receipts[address]
        if (existing == null) {
            pending.receipts[address] = MemoryReceipt(request)
            return ReceiptClaim.Claimed(newReceiptToken(transaction, namespace, operation, request))
        }
        if (existing.request != request) throw ReceiptRefusal.Collision
        return ReceiptClaim.Repeated(existing.completed ?: throw ReceiptRefusal.Incomplete)
    }

    override fun complete(
        transaction: EventTransaction,
        token: ReceiptToken,
        completion: ReceiptCompletion,
    ): CompletedReceipt {
        val pending = pending(transaction)
        requireReceiptToken(transaction, token)
        require((completion.response?.payload?.size ?: 0) <= limits.responseBytes) { "receipt response exceeds the configured bound" }
        val entry =
            checkNotNull(
                pending.receipts.entries.singleOrNull { (address, receipt) ->
                    token.matches(address.namespace, address.operation, receipt.request)
                },
            ) { "receipt token has no claim" }
        require(completion.stream.namespace == entry.key.namespace) { "receipt completion targets another namespace" }
        require(completion.metadata.operation == entry.key.operation) { "receipt completion has another operation key" }
        if (entry.value.completed != null) throw ReceiptRefusal.Incomplete
        require(verifiedAppend(pending, completion) == completion.append) { "receipt completion does not match committed event rows" }
        val completed = CompletedReceipt(completion.stream, completion.range, completion.append, completion.response)
        pending.receipts[entry.key] = entry.value.copy(completed = completed)
        return completed
    }

    private fun pending(transaction: EventTransaction): Pending {
        requireTransaction(transaction)
        check(isSameTransaction(transaction, checkNotNull(activeTransaction))) { "memory event transaction is stale" }
        return checkNotNull(active) { "memory event operation is outside its transaction callback" }
    }

    private fun verifiedAppend(
        pending: Pending,
        completion: ReceiptCompletion,
    ): com.gd.rain.event.AppendFingerprint {
        if (completion.range.count == 0) return appendFingerprint(completion.stream, emptyList(), completion.metadata)
        val range = completion.range
        val events =
            pending.streams[completion.stream].orEmpty().filter { event ->
                event.streamVersion in range.firstVersion!!..range.lastVersion!!
            }
        require(events.size == range.count) { "receipt completion event count does not match committed rows" }
        val expectedMetadata = completion.metadata.canonicalBytes()
        val facts =
            events.mapIndexed { index, event ->
                require(event.streamVersion == range.firstVersion!! + index) { "receipt completion versions are not dense" }
                require(event.metadata == expectedMetadata) { "receipt completion metadata does not match committed rows" }
                EncodedFact(event.fact, event.payload)
            }
        require(events.first().position == range.firstPosition) { "receipt completion first position does not match committed rows" }
        require(events.last().position == range.lastPosition) { "receipt completion last position does not match committed rows" }
        return appendFingerprint(completion.stream, facts, completion.metadata)
    }

    private data class Pending(
        val streams: MutableMap<StreamRef, MutableList<StoredEvent>>,
        val receipts: MutableMap<ReceiptAddress, MemoryReceipt>,
        val snapshots: MutableMap<StreamRef, MutableMap<Long, StoredSnapshot>>,
        var nextPosition: Long,
    )

    private data class ReceiptAddress(
        val namespace: EventNamespace,
        val operation: OperationKey,
    )

    private data class MemoryReceipt(
        val request: RequestFingerprint,
        val completed: CompletedReceipt? = null,
    )
}
