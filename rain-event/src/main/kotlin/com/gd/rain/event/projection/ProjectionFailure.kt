package com.gd.rain.event.projection

import com.gd.rain.event.EventBytes
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StreamRef
import java.security.MessageDigest

/** Stable closed code suitable for a durable hold row, never an exception message or tenant/domain value. */
public class ProjectionFailureCode private constructor(
    private val value: String,
) {
    public fun text(): String = value

    override fun equals(other: Any?): Boolean = other is ProjectionFailureCode && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "projection-failure:$value"

    public companion object {
        public fun of(value: String): ProjectionFailureCode {
            require(StreamRef.NAME.matches(value)) { "projection failure code is not stable" }
            return ProjectionFailureCode(value)
        }
    }
}

/** A classifier is explicit because only a closed permanent outcome may enter the halt/park protocol. */
public fun interface ProjectionFailureClassifier {
    public fun classify(failure: Throwable): ProjectionFailure
}

/** Failure classification carries a durable code only for a deliberate permanent transition. */
public sealed interface ProjectionFailure {
    public data object Transient : ProjectionFailure

    public data class Permanent(
        public val code: ProjectionFailureCode,
    ) : ProjectionFailure
}

/** Opaque digest of the stream sequence whose causal ordering is held; it never renders a stream key. */
public class ProjectionSequenceId private constructor(
    private val value: ByteArray,
) {
    public val diagnosticDigest: String = value.take(8).joinToString("") { "%02x".format(it) }

    public fun copy(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is ProjectionSequenceId && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "projection-sequence:$diagnosticDigest"

    public companion object {
        public const val BYTES: Int = 32

        /** Rehydrates an already validated opaque digest from durable storage; callers never provide a raw sequence key. */
        internal fun fromDigest(value: ByteArray): ProjectionSequenceId {
            require(value.size == BYTES) { "projection sequence digest has ${value.size} bytes, not $BYTES" }
            return ProjectionSequenceId(value.copyOf())
        }

        /**
         * Derives a one-stream sequence under the declaring hasher identity.
         *
         * A multi-stream causal sequence must derive its own opaque stable key instead; a partition hash alone is
         * deliberately not accepted as that identity because hash collisions must not join independent queues.
         */
        public fun forStream(
            hasher: SequenceKeyHasherId,
            stream: StreamRef,
        ): ProjectionSequenceId =
            ProjectionSequenceId(
                MessageDigest.getInstance("SHA-256").run {
                    update("rain.event.projection.sequence.v1".toByteArray(Charsets.UTF_8))
                    update(0)
                    update(hasher.text().toByteArray(Charsets.UTF_8))
                    update(0)
                    update(stream.namespace.copy())
                    update(0)
                    update(stream.family.toByteArray(Charsets.UTF_8))
                    update(0)
                    update(stream.key.toByteArray(Charsets.UTF_8))
                    digest()
                },
            )

        /** Derives an opaque identity from a bounded, application-defined stable sequence key. */
        public fun derive(
            hasher: SequenceKeyHasherId,
            key: EventBytes,
        ): ProjectionSequenceId =
            ProjectionSequenceId(
                MessageDigest.getInstance("SHA-256").run {
                    update("rain.event.projection.sequence.v1".toByteArray(Charsets.UTF_8))
                    update(0)
                    update(hasher.text().toByteArray(Charsets.UTF_8))
                    update(0)
                    update(key.copy())
                    digest()
                },
            )
    }
}

/** Bounded durable retention for one held sequence, independent from global event-log retention. */
public data class ProjectionHoldLimits(
    public val maxLetters: Int,
    public val maxBytes: Int,
) {
    init {
        require(maxLetters in 1..MAX_LETTERS) { "projection hold letter limit is outside 1..$MAX_LETTERS" }
        require(maxBytes in 1..MAX_BYTES) { "projection hold byte limit is outside 1..$MAX_BYTES" }
    }

    public companion object {
        public const val MAX_LETTERS: Int = 100_000
        public const val MAX_BYTES: Int = 64 * 1024 * 1024
    }
}

/** Immutable envelope retained for redrive; the checksum detects a corrupted duplicate independently of log replay. */
public class ProjectionLetter(
    public val sequence: ProjectionSequenceId,
    public val event: StoredEvent,
) {
    public val checksum: EventBytes = checksum(sequence, event)

    /** Exact bounded logical byte accounting for durable queue capacity; it intentionally excludes index overhead. */
    public val retainedBytes: Int =
        sequence.copy().size +
            event.stream.namespace.copy().size +
            utf8Bytes(event.stream.family) +
            utf8Bytes(event.stream.key) +
            utf8Bytes(event.fact.name) +
            event.payload.size +
            event.metadata.size +
            FIXED_ENVELOPE_BYTES

    override fun toString(): String = "projection-letter[${sequence.diagnosticDigest}:${event.position}]"
}

private const val FIXED_ENVELOPE_BYTES: Int = 40

private fun utf8Bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

internal fun checksum(
    sequence: ProjectionSequenceId,
    event: StoredEvent,
): EventBytes =
    EventBytes.of(
        MessageDigest.getInstance("SHA-256").run {
            update("rain.event.projection.letter.v1".toByteArray(Charsets.UTF_8))
            update(0)
            update(sequence.copy())
            update(0)
            update(event.position.toString().toByteArray(Charsets.UTF_8))
            update(0)
            update(event.stream.namespace.copy())
            update(0)
            update(event.stream.family.toByteArray(Charsets.UTF_8))
            update(0)
            update(event.stream.key.toByteArray(Charsets.UTF_8))
            update(0)
            update(event.streamVersion.toString().toByteArray(Charsets.UTF_8))
            update(0)
            update(event.fact.name.toByteArray(Charsets.UTF_8))
            update(0)
            update(
                event.fact.revision
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )
            update(0)
            update(event.payload.copy())
            update(0)
            update(event.metadata.copy())
            update(0)
            update(event.recordedAt.toEpochMilli().toString().toByteArray(Charsets.UTF_8))
            digest()
        },
    )
