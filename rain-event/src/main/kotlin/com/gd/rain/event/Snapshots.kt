package com.gd.rain.event

import java.security.MessageDigest
import java.time.Instant

/** Stable, application-declared identity of a snapshot state codec. */
public data class SnapshotCodecId(
    public val type: String,
    public val revision: Int,
) {
    init {
        require(StreamRef.NAME.matches(type)) { "snapshot codec type is not a stable event name" }
        require(revision > 0) { "snapshot codec revision is positive" }
    }
}

/** A 32-byte application declaration over aggregate fold and catalogue semantics, never a code hash. */
public class SnapshotFingerprint private constructor(
    private val value: ByteArray,
) {
    public fun copy(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is SnapshotFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "snapshot-fingerprint[redacted]"

    public companion object {
        public const val BYTES: Int = 32

        public fun of(value: ByteArray): SnapshotFingerprint {
            require(value.size == BYTES) { "snapshot fingerprint has ${value.size} bytes, not $BYTES" }
            return SnapshotFingerprint(value.copyOf())
        }
    }
}

/** Explicit state serialization; private aggregate fields are never inspected by the framework. */
public interface SnapshotCodec<S : Any> {
    public val id: SnapshotCodecId

    public fun encode(state: S): EventBytes

    /** Decodes an already validated codec revision. Throwing makes this snapshot disposable, not fatal to replay. */
    public fun decode(payload: EventBytes): S
}

/** Per-aggregate snapshot declaration. [fingerprint] changes only with fold/catalog semantics. */
public data class SnapshotSpec<S : Any>(
    public val codec: SnapshotCodec<S>,
    public val fingerprint: SnapshotFingerprint,
)

/** Candidate bytes to persist after a committed stream version. The store records its own creation time. */
public class SnapshotWrite(
    public val stream: StreamRef,
    public val version: Long,
    public val fingerprint: SnapshotFingerprint,
    public val codec: SnapshotCodecId,
    public val payload: EventBytes,
) {
    public val checksum: EventBytes = checksum(stream, version, fingerprint, codec, payload)

    init {
        require(version > 0) { "snapshot version is positive" }
        require(payload.size <= EventLimits.HARD_PAYLOAD_BYTES) { "snapshot payload exceeds ${EventLimits.HARD_PAYLOAD_BYTES} bytes" }
    }

    override fun toString(): String = "snapshot-write[${stream.family}@v$version, payload=redacted]"
}

/** Raw stored state. A corrupt checksum is represented here so a repository can ignore it rather than fail replay. */
public class StoredSnapshot(
    public val stream: StreamRef,
    public val version: Long,
    public val fingerprint: SnapshotFingerprint,
    public val codec: SnapshotCodecId,
    public val payload: EventBytes,
    checksum: EventBytes,
    public val createdAt: Instant,
) {
    private val checksum: EventBytes = EventBytes.of(checksum.copy())

    init {
        require(version > 0) { "stored snapshot version is positive" }
        require(this.checksum.size == SnapshotFingerprint.BYTES) { "stored snapshot checksum has an unknown format" }
    }

    /** A defensive comparison over every replay-relevant field. */
    public fun checksumIsValid(): Boolean =
        MessageDigest.isEqual(checksum.copy(), checksum(stream, version, fingerprint, codec, payload).copy())

    public fun checksum(): EventBytes = EventBytes.of(checksum.copy())

    override fun toString(): String = "stored-snapshot[${stream.family}@v$version, payload=redacted]"
}

/** Persisting an exact repeat is harmless; a different row at the same stream/version is a closed collision. */
public sealed interface SnapshotSaveResult {
    public data class Saved(
        public val snapshot: StoredSnapshot,
    ) : SnapshotSaveResult

    public data class Repeated(
        public val snapshot: StoredSnapshot,
    ) : SnapshotSaveResult

    public data object Collision : SnapshotSaveResult
}

/** Why a candidate was ignored. None of these conditions may change the result of aggregate replay. */
public enum class SnapshotIgnoredReason {
    CHECKSUM,
    FINGERPRINT,
    CODEC,
    VERSION,
    DECODE,
}

/** Optional low-cardinality observation hook; it receives no stream key, namespace, payload or state. */
public fun interface SnapshotObserver {
    public fun ignored(reason: SnapshotIgnoredReason): Unit

    public companion object {
        public val NONE: SnapshotObserver = SnapshotObserver {}
    }
}

/**
 * Optional storage extension over an [EventStore]. Every method is bound to its same caller-owned
 * event transaction; deleting snapshots is safe because event records remain the source of truth.
 */
public interface EventSnapshotStore {
    public fun latest(
        transaction: EventTransaction,
        stream: StreamRef,
        atOrBeforeVersion: Long,
    ): StoredSnapshot?

    public fun save(
        transaction: EventTransaction,
        write: SnapshotWrite,
    ): SnapshotSaveResult

    /** Deletes at most [batch] obsolete snapshots while preserving the most recent [keep] rows for one stream. */
    public fun prune(
        transaction: EventTransaction,
        stream: StreamRef,
        keep: Int,
        batch: Int,
    ): Int
}

internal fun checksum(
    stream: StreamRef,
    version: Long,
    fingerprint: SnapshotFingerprint,
    codec: SnapshotCodecId,
    payload: EventBytes,
): EventBytes =
    EventBytes.of(
        MessageDigest.getInstance("SHA-256").run {
            update("rain.event.snapshot.v1".toByteArray(Charsets.UTF_8))
            update(0)
            update(stream.namespace.copy())
            update(0)
            update(stream.family.toByteArray(Charsets.UTF_8))
            update(0)
            update(stream.key.toByteArray(Charsets.UTF_8))
            update(0)
            update(version.toString().toByteArray(Charsets.UTF_8))
            update(0)
            update(fingerprint.copy())
            update(0)
            update(codec.type.toByteArray(Charsets.UTF_8))
            update(0)
            update(codec.revision.toString().toByteArray(Charsets.UTF_8))
            update(0)
            update(payload.copy())
            digest()
        },
    )
