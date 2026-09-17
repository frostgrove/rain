package com.gd.rain.event.projection

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.persistence.tx.TransactionPlacement
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One immutable generation; rebuilding creates a higher value and never clears a live generation. */
public data class ProjectionGeneration(
    public val value: Long,
) {
    init {
        require(value > 0) { "projection generation is positive" }
    }
}

/** A single independently fenced projection lane. Its partition is part of durable identity. */
public data class ProjectionLane(
    public val projection: ProjectionName,
    public val generation: ProjectionGeneration,
    public val partition: ProjectionPartition,
)

/** Immutable identity of the event log and projection semantics a checkpoint is allowed to advance. */
public data class ProjectionCheckpointContract(
    public val origin: EventLogOrigin,
    public val revision: ProjectionContractRevision,
    public val topology: ProjectionTopologyFingerprint,
)

/** Durable checkpoint state observable without exposing a mutable lease token. */
public data class ProjectionCheckpoint(
    public val lane: ProjectionLane,
    public val contract: ProjectionCheckpointContract,
    public val cursor: EventLogCursor,
    public val fence: Long,
    public val updatedAt: Instant,
) {
    init {
        require(cursor.origin == contract.origin) { "projection checkpoint cursor belongs to another log" }
        require(fence > 0) { "projection checkpoint fence is positive" }
    }
}

/** A store-minted, non-serializable exclusive lease for one checkpoint lane. */
public class ProjectionLease internal constructor(
    internal val storeId: UUID,
    internal val token: UUID,
    public val lane: ProjectionLane,
    public val fence: Long,
) {
    override fun toString(): String = "projection-lease[${lane.projection.text()}:${lane.generation.value}:${lane.partition.canonical}]"
}

/** A claim either owns a live fenced lane, observes an existing owner, or refuses semantic drift. */
public sealed interface ProjectionClaim {
    public data class Acquired(
        public val checkpoint: ProjectionCheckpoint,
        public val lease: ProjectionLease,
    ) : ProjectionClaim

    public data class Busy(
        public val retryAt: Instant,
    ) : ProjectionClaim

    /** A durable permanent failure or capacity stop; a normal runner must not claim this lane again. */
    public data class Halted(
        public val halt: ProjectionHalt,
    ) : ProjectionClaim

    /** A durable topology split retired this parent; an old deployment must not recreate or resume its checkpoint. */
    public data object Retired : ProjectionClaim

    public data object ContractDrift : ProjectionClaim
}

/** Durable, redacted lane stop. Recovery must be an explicit operator protocol, never automatic retry. */
public data class ProjectionHalt(
    public val lane: ProjectionLane,
    public val position: Long,
    public val failure: ProjectionFailureCode,
    public val createdAt: Instant,
) {
    init {
        require(position > 0) { "projection halt position is positive" }
    }
}

/** A halt is conditional on the exact current checkpoint lease so a losing worker cannot stop a newer owner. */
public sealed interface ProjectionHaltResult {
    public data class Halted(
        public val halt: ProjectionHalt,
    ) : ProjectionHaltResult

    public data object LostLease : ProjectionHaltResult
}

/** Advancing a stale or released lease is never interpreted as success. */
public sealed interface ProjectionAdvance {
    public data class Advanced(
        public val checkpoint: ProjectionCheckpoint,
    ) : ProjectionAdvance

    public data object LostLease : ProjectionAdvance
}

/**
 * Durable checkpoint and lease boundary.
 *
 * `claim` is deliberately separate from event-log reading: it can be stored on the projection
 * destination's own authority for AFTER_APPLY. SAME_UNIT adapters additionally prove the same
 * authority before calling a handler and advancing the checkpoint in one transaction.
 */
public interface ProjectionCheckpointStore {
    public fun claim(
        lane: ProjectionLane,
        contract: ProjectionCheckpointContract,
        initialCursor: EventLogCursor,
        now: Instant,
        leaseFor: Duration,
    ): ProjectionClaim

    /** Writes [next] only when [lease] remains current, fenced, and on its exact source log. */
    public fun advance(
        lease: ProjectionLease,
        next: EventLogCursor,
        now: Instant,
    ): ProjectionAdvance

    /** Releases a current lease after a pass failure or no-work pass; a stale release is harmless. */
    public fun release(lease: ProjectionLease): Unit

    /** Reads the durable position for waiting/status; it does not acquire a lease. */
    public fun checkpoint(lane: ProjectionLane): ProjectionCheckpoint?
}

/** Supplies a current placement without checking out another connection. */
public fun interface ProjectionTransactionPlacement {
    public fun inspect(): TransactionPlacement
}

/** A checkpoint adapter that can prove its writes share a caller-owned destination transaction. */
public interface SameUnitProjectionCheckpointStore : ProjectionCheckpointStore {
    public fun inspectPlacement(): TransactionPlacement
}

/** Optional stronger checkpoint capability used by SAME_UNIT permanent-failure handling. */
public interface SameUnitProjectionHaltStore : SameUnitProjectionCheckpointStore {
    public fun halt(
        lease: ProjectionLease,
        position: Long,
        failure: ProjectionFailureCode,
    ): ProjectionHaltResult
}

/**
 * Base for checkpoint adapters. It gives a store its own unforgeable lease namespace while leaving
 * all persistence and concurrency decisions to the adapter.
 */
public abstract class ProjectionCheckpointStoreSupport : ProjectionCheckpointStore {
    private val storeId: UUID = UUID.randomUUID()

    protected fun newLease(
        lane: ProjectionLane,
        fence: Long,
    ): ProjectionLease = ProjectionLease(storeId, UUID.randomUUID(), lane, fence)

    /** Rebinds this store's freshly chosen token to the fence atomically returned by an adapter. */
    protected fun withFence(
        lease: ProjectionLease,
        fence: Long,
    ): ProjectionLease {
        requireLease(lease)
        return ProjectionLease(storeId, lease.token, lease.lane, fence)
    }

    protected fun owns(lease: ProjectionLease): Boolean = lease.storeId == storeId

    protected fun requireLease(lease: ProjectionLease): Unit = check(owns(lease)) { "projection lease belongs to another checkpoint store" }
}
