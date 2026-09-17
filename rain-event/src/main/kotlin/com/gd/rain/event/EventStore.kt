package com.gd.rain.event

import com.gd.rain.persistence.tx.BackingIdentity
import java.time.Instant
import java.util.UUID

/** A page is finite by construction and can never mean "all events after this version". */
public data class StreamPage(
    public val events: List<StoredEvent>,
    public val afterVersion: Long,
    public val hasMore: Boolean,
) {
    init {
        require(afterVersion >= 0) { "after version is not negative" }
        require(events.zipWithNext().all { (left, right) -> left.streamVersion < right.streamVersion }) {
            "a stream page is strictly ordered"
        }
    }
}

/** An immutable stored envelope. Payload and metadata remain encoded until the declared catalogue reads them. */
public data class StoredEvent(
    public val stream: StreamRef,
    public val streamVersion: Long,
    public val position: Long,
    public val fact: FactType,
    public val payload: EventBytes,
    public val metadata: EventBytes,
    public val recordedAt: Instant,
) {
    init {
        require(streamVersion > 0) { "stream version is positive" }
        require(position > 0) { "global position is positive" }
    }
}

/** Exact durable range returned by a successful append or a completed idempotency receipt. */
public data class CommitRange(
    public val firstPosition: Long?,
    public val lastPosition: Long?,
    public val firstVersion: Long?,
    public val lastVersion: Long?,
    public val count: Int,
) {
    init {
        require(count >= 0) { "commit range count is not negative" }
        if (count == 0) {
            require(firstPosition == null && lastPosition == null && firstVersion == null && lastVersion == null) {
                "an empty commit range has no endpoints"
            }
        } else {
            require(firstPosition != null && lastPosition != null && firstVersion != null && lastVersion != null) {
                "a non-empty commit range has all endpoints"
            }
            require(lastPosition >= firstPosition) { "global range endpoints are reversed" }
            require(lastVersion - firstVersion + 1 == count.toLong()) { "stream range is not dense" }
        }
    }

    public companion object {
        public val EMPTY: CommitRange = CommitRange(null, null, null, null, 0)
    }
}

/** The result of an append attempt once the store can classify it safely. */
public sealed interface AppendResult {
    public data class Committed(
        public val range: CommitRange,
    ) : AppendResult

    public data object Conflict : AppendResult
}

/** A bounded encoded event staged for one append. */
public data class EncodedFact(
    public val type: FactType,
    public val payload: EventBytes,
) {
    init {
        require(payload.size <= EventLimits.HARD_PAYLOAD_BYTES) {
            "fact payload exceeds ${EventLimits.HARD_PAYLOAD_BYTES} bytes"
        }
    }
}

/** An append batch whose contents have already been catalogued and encoded. */
public data class EncodedChanges(
    public val stream: StreamRef,
    public val facts: List<EncodedFact>,
) {
    init {
        require(facts.size <= EventLimits.HARD_BATCH_SIZE) { "event batch exceeds ${EventLimits.HARD_BATCH_SIZE}" }
    }
}

/**
 * A store-specific unit. Its constructor is private: callers obtain it only from
 * [EventStore.inCallerTransaction]. A JDBC adapter verifies an already-open caller transaction;
 * the deterministic memory adapter uses a virtual unit solely for conformance tests.
 */
public class EventTransaction internal constructor(
    internal val storeId: UUID,
    public val backing: BackingIdentity,
    internal val nonce: UUID,
)

/** Low-level storage boundary. It never starts, commits, retries, or rolls back a JDBC transaction. */
public interface EventStore {
    public val backing: BackingIdentity
    public val limits: EventLimits

    /**
     * Enters the caller's active transaction after validating its backing. For a real adapter this
     * is only an affinity check; transaction ownership stays with the application. The callback
     * makes accidental token use outside that unit impossible in the reference implementation.
     */
    public fun <T> inCallerTransaction(block: (EventTransaction) -> T): T

    public fun version(
        transaction: EventTransaction,
        stream: StreamRef,
    ): Long?

    public fun readStream(
        transaction: EventTransaction,
        stream: StreamRef,
        afterVersion: Long,
        limit: Int,
    ): StreamPage

    /** Performs one atomic conditional append; a conflict is confirmed and never retried here. */
    public fun append(
        transaction: EventTransaction,
        expectedVersion: Long,
        changes: EncodedChanges,
        metadata: EventMetadata,
    ): AppendResult
}

/**
 * Base for adapters that need to mint [EventTransaction] values after they have checked their own
 * transaction boundary. It exposes no connection and intentionally gives consumers no way to mint
 * a token: only a store implementation can call [newTransaction].
 */
public abstract class EventStoreSupport : EventStore {
    private val storeId: UUID = UUID.randomUUID()

    /** Creates a unit after the adapter has validated its caller-owned transaction. */
    protected fun newTransaction(): EventTransaction = EventTransaction(storeId, backing, UUID.randomUUID())

    /** Refuses a token minted by another adapter or another unit before data access. */
    protected fun requireTransaction(transaction: EventTransaction) {
        check(transaction.storeId == storeId) { "event transaction belongs to another store" }
        check(transaction.backing == backing) { "event transaction belongs to another backing" }
    }

    /** Mints a receipt capability only for this store's already validated caller transaction. */
    protected fun newReceiptToken(
        transaction: EventTransaction,
        namespace: EventNamespace,
        operation: OperationKey,
        request: RequestFingerprint,
    ): ReceiptToken {
        requireTransaction(transaction)
        return ReceiptToken(ReceiptBinding(transaction.storeId, transaction.nonce, namespace, operation, request))
    }

    /** Refuses a receipt capability minted by another store or virtual transaction unit. */
    protected fun requireReceiptToken(
        transaction: EventTransaction,
        token: ReceiptToken,
    ) {
        requireTransaction(transaction)
        check(token.binding.storeId == transaction.storeId && token.binding.nonce == transaction.nonce) {
            "receipt token is from another event transaction"
        }
    }

    /** Compares opaque units without exposing their nonce to adapter consumers. */
    protected fun isSameTransaction(
        left: EventTransaction,
        right: EventTransaction,
    ): Boolean = left.storeId == right.storeId && left.nonce == right.nonce && left.backing == right.backing
}
