package com.gd.rain.event

import java.security.MessageDigest
import java.util.UUID

/** Stable, opaque identity of one immutable global event log. */
public class EventLogId private constructor(
    private val value: ByteArray,
) {
    /** A short non-authoritative diagnostic form; it is not a metric label. */
    public val diagnosticDigest: String = value.take(8).joinToString("") { "%02x".format(it) }

    public fun copy(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is EventLogId && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "event-log:$diagnosticDigest"

    public companion object {
        public const val BYTES: Int = 16

        public fun of(value: ByteArray): EventLogId {
            require(value.size == BYTES) { "event log id has ${value.size} bytes, not $BYTES" }
            return EventLogId(value.copyOf())
        }

        /** Mints a fresh identity for an explicit in-memory test log. */
        public fun random(): EventLogId {
            val value = UUID.randomUUID()
            return of(
                java.nio.ByteBuffer
                    .allocate(BYTES)
                    .putLong(value.mostSignificantBits)
                    .putLong(value.leastSignificantBits)
                    .array(),
            )
        }
    }
}

/** Stable durable identity carried by every cursor and mark; a foreign log is always refused. */
public data class EventLogOrigin(
    public val logId: EventLogId,
)

/**
 * A cursor over an immutable, committed global log.
 *
 * It has no public constructor: only a [CommittedEventLog] can mint the initial cursor or advance
 * it. The settlement data is adapter-private so callers cannot turn an arbitrary identity position
 * into a proof that preceding PostgreSQL writers have committed.
 */
public class EventLogCursor internal constructor(
    public val origin: EventLogOrigin,
    public val deliveredPosition: Long,
    internal val settlement: EventLogSettlement,
) {
    init {
        require(deliveredPosition >= 0) { "event log cursor position is not negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is EventLogCursor &&
            origin == other.origin &&
            deliveredPosition == other.deliveredPosition &&
            settlement == other.settlement

    override fun hashCode(): Int = 31 * (31 * origin.hashCode() + deliveredPosition.hashCode()) + settlement.hashCode()

    override fun toString(): String = "event-log-cursor[${origin.logId.diagnosticDigest}:$deliveredPosition]"

    public companion object {
        /** Starts at the beginning of one declared log. Adapters still reject a foreign origin. */
        public fun start(origin: EventLogOrigin): EventLogCursor = EventLogCursor(origin, 0, EventLogSettlement())

        /** Advances a cursor without changing its adapter-private settlement evidence. */
        public fun after(
            cursor: EventLogCursor,
            deliveredPosition: Long,
        ): EventLogCursor {
            require(deliveredPosition >= cursor.deliveredPosition) { "event log cursor cannot move backward" }
            return EventLogCursor(cursor.origin, deliveredPosition, cursor.settlement)
        }
    }
}

/** Adapter-private evidence gathered while minting the cursor's current settlement boundary. */
internal data class EventLogSettlement(
    val boundXid: String? = null,
    val reach: Long = 0,
)

/** A durable global position suitable for bounded projection read-your-writes waiting. */
public data class EventLogMark(
    public val origin: EventLogOrigin,
    public val position: Long,
) {
    init {
        require(position > 0) { "event log mark position is positive" }
    }
}

/** One finite committed-log read. `hasMore` refers only to the same observed settlement boundary. */
public data class CommittedLogPage(
    public val events: List<StoredEvent>,
    public val next: EventLogCursor,
    public val hasMore: Boolean,
) {
    init {
        require(events.zipWithNext().all { (left, right) -> left.position < right.position }) {
            "a committed log page is strictly ordered"
        }
        require(events.all { it.position <= next.deliveredPosition }) { "a committed log page exceeds its cursor" }
        if (events.isNotEmpty()) {
            require(next.deliveredPosition == events.last().position) {
                "a committed log cursor does not acknowledge the delivered suffix"
            }
        }
        require(!hasMore || events.isNotEmpty()) { "an empty committed log page cannot prove more settled records" }
    }
}

/**
 * Read-only optional capability over the immutable global log.
 *
 * A reader never opens a long-lived transaction or treats identity ordering as commit ordering. A
 * PostgreSQL implementation advances only through rows settled before its captured xmin boundary;
 * an in-memory implementation exposes the serially committed reference order.
 */
public interface CommittedEventLog {
    public val origin: EventLogOrigin

    public fun initialCursor(): EventLogCursor

    /** Returns at most [limit] records, always validating the origin of [cursor]. */
    public fun readCommitted(
        cursor: EventLogCursor,
        limit: Int,
    ): CommittedLogPage

    /** Converts a non-empty append range into a mark for this exact log. */
    public fun mark(range: CommitRange): EventLogMark? = range.lastPosition?.let { EventLogMark(origin, it) }
}
