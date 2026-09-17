package com.gd.rain.event

import com.gd.rain.core.actor.Actor
import java.security.MessageDigest
import java.util.Arrays

/** Immutable bounded bytes with value equality and no mutable-array escape. */
public class EventBytes private constructor(
    private val value: ByteArray,
) {
    /** Returns a defensive copy for a codec or JDBC binding. */
    public fun copy(): ByteArray = value.copyOf()

    /** Number of bytes, not UTF-16 code units. */
    public val size: Int get() = value.size

    override fun equals(other: Any?): Boolean = other is EventBytes && Arrays.equals(value, other.value)

    override fun hashCode(): Int = Arrays.hashCode(value)

    override fun toString(): String = "event-bytes[$size]"

    public companion object {
        public fun of(value: ByteArray): EventBytes = EventBytes(value.copyOf())

        public fun utf8(value: String): EventBytes = EventBytes(value.toByteArray(Charsets.UTF_8))

        public val EMPTY: EventBytes = EventBytes(ByteArray(0))
    }
}

/** A 32-byte namespace chosen by the application or an optional composition module. */
public class EventNamespace private constructor(
    private val value: ByteArray,
) {
    /** A short safe diagnostic identifier. It is never an authority or a metric label. */
    public val diagnosticDigest: String = value.take(8).joinToString("") { "%02x".format(it) }

    public fun copy(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is EventNamespace && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "event-namespace:$diagnosticDigest"

    public companion object {
        public const val SIZE_BYTES: Int = 32

        public fun of(value: ByteArray): EventNamespace {
            require(value.size == SIZE_BYTES) { "event namespace has ${value.size} bytes, not $SIZE_BYTES" }
            return EventNamespace(value.copyOf())
        }

        /** Derives a namespace from non-secret stable inputs with domain separation. */
        public fun derive(vararg parts: EventBytes): EventNamespace {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("rain.event.namespace.v1".toByteArray(Charsets.UTF_8))
            parts.forEach {
                digest.update(0)
                digest.update(it.copy())
            }
            return EventNamespace(digest.digest())
        }
    }
}

/** A caller-chosen idempotency key. Its value is deliberately redacted from [toString]. */
public class OperationKey private constructor(
    private val value: String,
) {
    internal fun canonical(): ByteArray = value.toByteArray(Charsets.UTF_8)

    internal fun text(): String = value

    override fun equals(other: Any?): Boolean = other is OperationKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "operation-key[redacted]"

    public companion object {
        public const val MAX_BYTES: Int = 256

        public fun of(value: String): OperationKey {
            require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "operation key is blank or exceeds $MAX_BYTES UTF-8 bytes"
            }
            return OperationKey(value)
        }
    }
}

/** A stable event stream address. Its namespace prevents one deployment's streams from colliding with another's. */
public data class StreamRef(
    public val namespace: EventNamespace,
    public val family: String,
    public val key: String,
) {
    init {
        require(NAME.matches(family)) { "aggregate family is not a stable event name" }
        require(key.isNotBlank() && key.toByteArray(Charsets.UTF_8).size <= EventLimits.HARD_STREAM_KEY_BYTES) {
            "stream key is blank or exceeds ${EventLimits.HARD_STREAM_KEY_BYTES} UTF-8 bytes"
        }
    }

    public companion object {
        internal val NAME: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** A fact wire identity. Kotlin's class name is never part of this identity. */
public data class FactType(
    public val name: String,
    public val revision: Int,
) {
    init {
        require(StreamRef.NAME.matches(name)) { "fact type is not a stable event name" }
        require(revision >= 1) { "fact revision is positive" }
    }
}

/** Authoritative metadata accepted by the kernel; recorded-at is supplied by the store, never by callers. */
public class EventMetadata(
    public val operation: OperationKey,
    public val causationPosition: Long? = null,
    public val correlationId: String? = null,
    public val actor: Actor? = null,
    public val traceId: String? = null,
    tags: Map<String, String> = emptyMap(),
) {
    /** Canonically ordered declared tags. A mutable caller map cannot alter a stored request. */
    public val tags: Map<String, String> = tags.toSortedMap()

    init {
        require(causationPosition == null || causationPosition > 0) { "causation position is positive" }
        require(correlationId == null || bounded(correlationId, MAX_CORRELATION_BYTES)) { "correlation id is too large" }
        require(traceId == null || bounded(traceId, MAX_TRACE_BYTES)) { "trace id is too large" }
        require(this.tags.size <= MAX_TAGS) { "metadata has more than $MAX_TAGS tags" }
        this.tags.forEach { (key, value) ->
            require(StreamRef.NAME.matches(key)) { "metadata tag name is not declared" }
            require(bounded(value, MAX_TAG_VALUE_BYTES)) { "metadata tag value is too large" }
        }
        require(canonicalBytes().size <= EventLimits.HARD_METADATA_BYTES) {
            "metadata exceeds ${EventLimits.HARD_METADATA_BYTES} canonical bytes"
        }
    }

    /** Stable bytes used in append and receipt fingerprints. */
    public fun canonicalBytes(): EventBytes =
        EventBytes.utf8(
            buildString {
                append(String(operation.canonical(), Charsets.UTF_8)).append('\u0000')
                append(causationPosition ?: "").append('\u0000')
                append(correlationId ?: "").append('\u0000')
                actor?.let { append(it.type).append('\u0000').append(it.id) }
                append('\u0000').append(traceId ?: "")
                tags.forEach { (key, value) -> append('\u0000').append(key).append('=').append(value) }
            },
        )

    private companion object {
        const val MAX_CORRELATION_BYTES: Int = 256
        const val MAX_TRACE_BYTES: Int = 256
        const val MAX_TAGS: Int = 32
        const val MAX_TAG_VALUE_BYTES: Int = 256

        fun bounded(
            value: String,
            maximum: Int,
        ): Boolean = value.toByteArray(Charsets.UTF_8).size <= maximum
    }
}

/** Kernel hard ceilings; deployment configuration may only narrow these values. */
public data class EventLimits(
    public val payloadBytes: Int = DEFAULT_PAYLOAD_BYTES,
    public val metadataBytes: Int = HARD_METADATA_BYTES,
    public val batchSize: Int = DEFAULT_BATCH_SIZE,
    public val streamPageSize: Int = DEFAULT_PAGE_SIZE,
    public val logPageSize: Int = DEFAULT_PAGE_SIZE,
    public val responseBytes: Int = DEFAULT_RESPONSE_BYTES,
) {
    init {
        require(payloadBytes in 1..HARD_PAYLOAD_BYTES) { "payload limit is between 1 and $HARD_PAYLOAD_BYTES" }
        require(metadataBytes in 1..HARD_METADATA_BYTES) { "metadata limit is between 1 and $HARD_METADATA_BYTES" }
        require(batchSize in 1..HARD_BATCH_SIZE) { "batch limit is between 1 and $HARD_BATCH_SIZE" }
        require(streamPageSize in 1..HARD_PAGE_SIZE) { "stream page limit is between 1 and $HARD_PAGE_SIZE" }
        require(logPageSize in 1..HARD_PAGE_SIZE) { "log page limit is between 1 and $HARD_PAGE_SIZE" }
        require(responseBytes in 1..HARD_RESPONSE_BYTES) { "response limit is between 1 and $HARD_RESPONSE_BYTES" }
    }

    public companion object {
        public const val HARD_STREAM_KEY_BYTES: Int = 512
        public const val HARD_METADATA_BYTES: Int = 4 * 1024
        public const val DEFAULT_PAYLOAD_BYTES: Int = 256 * 1024
        public const val HARD_PAYLOAD_BYTES: Int = 1024 * 1024
        public const val DEFAULT_BATCH_SIZE: Int = 128
        public const val HARD_BATCH_SIZE: Int = 1000
        public const val DEFAULT_PAGE_SIZE: Int = 500
        public const val HARD_PAGE_SIZE: Int = 2000
        public const val DEFAULT_RESPONSE_BYTES: Int = 16 * 1024
        public const val HARD_RESPONSE_BYTES: Int = 64 * 1024
    }
}
