package com.gd.rain.event

import java.security.MessageDigest
import java.util.UUID

/** SHA-256 of immutable application command bytes, used to prevent an operation-key collision. */
public class RequestFingerprint private constructor(
    private val value: EventBytes,
) {
    public fun copy(): EventBytes = EventBytes.of(value.copy())

    override fun equals(other: Any?): Boolean = other is RequestFingerprint && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "request-fingerprint[redacted]"

    internal fun sameAs(value: ByteArray): Boolean = MessageDigest.isEqual(this.value.copy(), value)

    public companion object {
        internal fun stored(value: ByteArray): RequestFingerprint {
            require(value.size == BYTES) { "a request fingerprint has ${value.size} bytes, not $BYTES" }
            return RequestFingerprint(EventBytes.of(value))
        }

        internal const val BYTES: Int = 32

        /** Computes the fingerprint before a command's mutable transport representation is discarded. */
        public fun of(commandBytes: EventBytes): RequestFingerprint =
            RequestFingerprint(EventBytes.of(MessageDigest.getInstance("SHA-256").digest(commandBytes.copy())))
    }
}

/** Optional typed reply stored with a completed receipt, bounded by the store's response limit. */
public data class ReceiptResponse(
    public val type: String,
    public val revision: Int,
    public val payload: EventBytes,
) {
    init {
        require(StreamRef.NAME.matches(type)) { "receipt response type is not a stable event name" }
        require(revision > 0) { "receipt response revision is positive" }
        require(payload.size <= EventLimits.HARD_RESPONSE_BYTES) { "receipt response exceeds the hard bound" }
    }
}

/** The immutable terminal result that a repeated operation receives instead of running a decision again. */
public data class CompletedReceipt(
    public val stream: StreamRef,
    public val range: CommitRange,
    public val append: AppendFingerprint,
    public val response: ReceiptResponse?,
)

/** What a receipt claim decides inside the caller's event transaction. */
public sealed interface ReceiptClaim {
    /** This transaction owns the receipt and may load, decide, append, and complete it exactly once. */
    public data class Claimed(
        public val token: ReceiptToken,
    ) : ReceiptClaim

    /** The same key and immutable request completed earlier; application code must not run its decision again. */
    public data class Repeated(
        public val receipt: CompletedReceipt,
    ) : ReceiptClaim
}

/** The values required to complete a receipt after a service has appended its decided facts. */
public data class ReceiptCompletion(
    public val stream: StreamRef,
    public val range: CommitRange,
    public val append: AppendFingerprint,
    /** The same canonical metadata supplied to append; a receipt verifies it against committed rows. */
    public val metadata: EventMetadata,
    public val response: ReceiptResponse? = null,
) {
    init {
        require(metadata.canonicalBytes().size <= EventLimits.HARD_METADATA_BYTES) { "receipt metadata exceeds the hard bound" }
        require(response == null || response.payload.size <= EventLimits.HARD_RESPONSE_BYTES) { "receipt response exceeds the hard bound" }
    }
}

/** Opaque ownership of an incomplete receipt, bound to one event-store transaction. */
public class ReceiptToken internal constructor(
    internal val binding: ReceiptBinding,
) {
    /** Lets a store implementation locate its opaque claim without exposing the key's raw string. */
    public fun matches(
        namespace: EventNamespace,
        operation: OperationKey,
        request: RequestFingerprint,
    ): Boolean = binding.namespace == namespace && binding.operation == operation && binding.request == request

    override fun toString(): String = "receipt-token[opaque]"
}

/**
 * Optional low-level receipt capability. It deliberately has no decision callback: a caller claims,
 * runs its pure decision exactly once, appends, and completes inside the same transaction.
 */
public interface EventOperationReceipts {
    public fun claim(
        transaction: EventTransaction,
        namespace: EventNamespace,
        operation: OperationKey,
        request: RequestFingerprint,
    ): ReceiptClaim

    public fun complete(
        transaction: EventTransaction,
        token: ReceiptToken,
        completion: ReceiptCompletion,
    ): CompletedReceipt
}

internal data class ReceiptBinding(
    val storeId: UUID,
    val nonce: UUID,
    val namespace: EventNamespace,
    val operation: OperationKey,
    val request: RequestFingerprint,
)

/** Closed receipt protocol refusals; a caller must reconcile rather than guess on these outcomes. */
public sealed class ReceiptRefusal(
    message: String,
) : IllegalStateException(message) {
    public data object Collision : ReceiptRefusal("event operation key belongs to another request")

    public data object Incomplete : ReceiptRefusal("event operation receipt is incomplete")
}
