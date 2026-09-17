package com.gd.rain.event.test

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.projection.ProjectionAdvance
import com.gd.rain.event.projection.ProjectionCheckpoint
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionCheckpointStoreSupport
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLease
import java.time.Duration
import java.time.Instant

/** Serializable in-memory checkpoint reference adapter for projection tests, never a production fallback. */
public class InMemoryProjectionCheckpointStore : ProjectionCheckpointStoreSupport() {
    private val entries: MutableMap<ProjectionLane, Entry> = mutableMapOf()

    override fun claim(
        lane: ProjectionLane,
        contract: ProjectionCheckpointContract,
        initialCursor: EventLogCursor,
        now: Instant,
        leaseFor: Duration,
    ): ProjectionClaim =
        synchronized(entries) {
            require(initialCursor.origin == contract.origin) { "projection initial cursor belongs to another log" }
            require(!leaseFor.isNegative && !leaseFor.isZero) { "projection lease duration is positive" }
            val existing = entries[lane]
            if (existing != null && existing.checkpoint.contract != contract) return ProjectionClaim.ContractDrift
            if (existing?.active?.expiresAt?.isAfter(now) == true) return ProjectionClaim.Busy(existing.active.expiresAt)
            val prior = existing?.checkpoint
            val fence = (prior?.fence ?: 0) + 1
            val checkpoint =
                (prior ?: ProjectionCheckpoint(lane, contract, initialCursor, fence, now)).copy(
                    fence = fence,
                    updatedAt = now,
                )
            val lease = newLease(lane, fence)
            entries[lane] = Entry(checkpoint, Active(lease, now.plus(leaseFor)))
            ProjectionClaim.Acquired(checkpoint, lease)
        }

    override fun advance(
        lease: ProjectionLease,
        next: EventLogCursor,
        now: Instant,
    ): ProjectionAdvance =
        synchronized(entries) {
            requireLease(lease)
            val entry = entries[lease.lane] ?: return ProjectionAdvance.LostLease
            val active = entry.active ?: return ProjectionAdvance.LostLease
            if (active.lease !== lease || !active.expiresAt.isAfter(now)) return ProjectionAdvance.LostLease
            require(next.origin == entry.checkpoint.contract.origin) { "projection cursor belongs to another log" }
            require(next.deliveredPosition >= entry.checkpoint.cursor.deliveredPosition) {
                "projection checkpoint cursor cannot move backward"
            }
            val checkpoint = entry.checkpoint.copy(cursor = next, updatedAt = now)
            entries[lease.lane] = entry.copy(checkpoint = checkpoint)
            ProjectionAdvance.Advanced(checkpoint)
        }

    override fun release(lease: ProjectionLease): Unit =
        synchronized(entries) {
            if (!owns(lease)) return
            val entry = entries[lease.lane] ?: return
            if (entry.active?.lease === lease) entries[lease.lane] = entry.copy(active = null)
        }

    override fun checkpoint(lane: ProjectionLane): ProjectionCheckpoint? = synchronized(entries) { entries[lane]?.checkpoint }

    /** Reports whether [lease] can still atomically guard a companion in-memory projection mutation at [now]. */
    public fun isCurrent(
        lease: ProjectionLease,
        now: Instant,
    ): Boolean =
        synchronized(entries) {
            owns(lease) &&
                entries[lease.lane]
                    ?.active
                    ?.let { active -> active.lease === lease && active.expiresAt.isAfter(now) } == true
        }

    private data class Entry(
        val checkpoint: ProjectionCheckpoint,
        val active: Active?,
    )

    private data class Active(
        val lease: ProjectionLease,
        val expiresAt: Instant,
    )
}
