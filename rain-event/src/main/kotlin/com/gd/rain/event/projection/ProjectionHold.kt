package com.gd.rain.event.projection

import com.gd.rain.event.StoredEvent
import com.gd.rain.persistence.tx.TransactionPlacement
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Durable state for one causally ordered parked sequence. */
public enum class ProjectionHoldState {
    HELD,
    REDRIVING,
}

/** Redacted observability state for one parked sequence; envelopes are read only through a fenced redrive lease. */
public data class ProjectionHold(
    public val lane: ProjectionLane,
    public val sequence: ProjectionSequenceId,
    public val firstPosition: Long,
    public val failureCode: ProjectionFailureCode,
    public val state: ProjectionHoldState,
    public val letterCount: Int,
    public val letterBytes: Int,
    public val fence: Long,
    public val createdAt: Instant,
    public val updatedAt: Instant,
) {
    init {
        require(firstPosition > 0) { "projection hold first position is positive" }
        require(letterCount in 0..ProjectionHoldLimits.MAX_LETTERS) { "projection hold letter count is outside hard limit" }
        require(letterBytes in 0..ProjectionHoldLimits.MAX_BYTES) { "projection hold byte count is outside hard limit" }
        require(fence >= 0) { "projection hold fence is non-negative" }
    }
}

/** Result of a live pass inspecting or appending a held sequence. */
public sealed interface ProjectionHoldEnqueue {
    /** The sequence was not parked, so the runner may invoke its normal handler. */
    public data object NotHeld : ProjectionHoldEnqueue

    /** The envelope is durably represented and must not be delivered on the live path. */
    public data class Queued(
        public val hold: ProjectionHold,
        public val repeated: Boolean,
    ) : ProjectionHoldEnqueue

    /** The envelope was not retained; the lane must enter a durable halt rather than silently advance. */
    public data class CapacityExceeded(
        public val hold: ProjectionHold,
    ) : ProjectionHoldEnqueue

    /** The checkpoint claim was no longer live, so the caller transaction must roll back. */
    public data object LostLease : ProjectionHoldEnqueue
}

/** Store-minted fenced ownership of one held sequence during a redrive pass. */
public class ProjectionRedriveLease internal constructor(
    internal val storeId: UUID,
    internal val token: UUID,
    public val lane: ProjectionLane,
    public val sequence: ProjectionSequenceId,
    public val fence: Long,
) {
    override fun toString(): String =
        "projection-redrive-lease[${lane.projection.text()}:${lane.generation.value}:${lane.partition.canonical}:${sequence.diagnosticDigest}]"
}

/** Claim result for one bounded redrive pass. */
public sealed interface ProjectionRedriveClaim {
    public data class Acquired(
        public val hold: ProjectionHold,
        public val lease: ProjectionRedriveLease,
    ) : ProjectionRedriveClaim

    public data class Busy(
        public val retryAt: Instant,
    ) : ProjectionRedriveClaim

    public data object Idle : ProjectionRedriveClaim
}

/** A stored letter with prior redrive-attempt diagnostics, never a raw exception body. */
public data class ProjectionRedriveLetter(
    public val letter: ProjectionLetter,
    public val attempts: Int,
    public val lastFailureCode: ProjectionFailureCode?,
    public val lastFailedAt: Instant?,
) {
    init {
        require(attempts >= 0) { "projection redrive attempts are non-negative" }
        require((lastFailureCode == null) == (lastFailedAt == null)) { "projection redrive failure diagnostics are incomplete" }
    }
}

/** Acknowledge is strictly head-only; callers cannot remove a later queued envelope. */
public sealed interface ProjectionRedriveAcknowledge {
    public data class Advanced(
        public val remainingLetters: Int,
    ) : ProjectionRedriveAcknowledge

    public data object LostLease : ProjectionRedriveAcknowledge
}

/** Result of recording a redrive failure and returning the sequence to the held state. */
public enum class ProjectionRedriveFailure {
    RECORDED,
    LOST_LEASE,
}

/** A bounded non-authority reference to the operator who made an audited hole decision. */
public data class ProjectionOperator(
    public val type: String,
    public val id: String,
) {
    init {
        require(TYPE.matches(type)) { "projection operator type is not stable" }
        require(id.isNotBlank() && id.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES) {
            "projection operator id is blank or too large"
        }
    }

    public companion object {
        private val TYPE: Regex = Regex("^[a-z][a-z0-9_-]{0,63}$")
        public const val MAX_ID_BYTES: Int = 512
    }
}

/** Primary key of an immutable operator hole decision. */
public data class ProjectionHoleId(
    public val value: Long,
) {
    init {
        require(value > 0) { "projection hole id is positive" }
    }
}

/** Durable, redacted record that a sequence range was intentionally omitted from a projection. */
public data class ProjectionHole(
    public val id: ProjectionHoleId,
    public val lane: ProjectionLane,
    public val sequence: ProjectionSequenceId,
    public val firstPosition: Long,
    public val lastPosition: Long,
    public val operator: ProjectionOperator,
    public val reason: String,
    public val createdAt: Instant,
    public val acknowledgedAt: Instant?,
) {
    init {
        require(firstPosition > 0 && lastPosition >= firstPosition) { "projection hole range is invalid" }
        require(reason.isNotBlank() && reason.toByteArray(Charsets.UTF_8).size <= MAX_REASON_BYTES) {
            "projection hole reason is blank or too large"
        }
    }

    public val acknowledged: Boolean get() = acknowledgedAt != null

    public companion object {
        public const val MAX_REASON_BYTES: Int = 1024
    }
}

/** An explicit operator action releases a parked sequence and leaves an unacknowledged immutable hole. */
public sealed interface ProjectionHoldEviction {
    public data class Evicted(
        public val hole: ProjectionHole,
    ) : ProjectionHoldEviction

    public data object Missing : ProjectionHoldEviction

    public data class Busy(
        public val retryAt: Instant,
    ) : ProjectionHoldEviction
}

/** Acknowledgement is intentionally separate from skip: it is the explicit rebuild/cutover policy decision. */
public sealed interface ProjectionHoleAcknowledgement {
    public data class Acknowledged(
        public val hole: ProjectionHole,
    ) : ProjectionHoleAcknowledgement

    public data object Missing : ProjectionHoleAcknowledgement

    public data object AlreadyAcknowledged : ProjectionHoleAcknowledgement
}

/**
 * Durable parking/redrive boundary for SAME_UNIT projections.
 *
 * A caller must prove the returned placement is the same caller-owned transaction as its destination and checkpoint
 * store. `enqueue` is conditional on the live checkpoint lease, making hold creation/letter enqueue and checkpoint
 * advancement one database unit. Redrive uses a distinct fenced lease because it runs after the live cursor advanced.
 */
public interface SameUnitProjectionHoldStore {
    public fun inspectPlacement(): TransactionPlacement

    /**
     * Observes an existing hold or appends [letter]. Passing [failure] creates the first hold atomically when absent;
     * passing null never creates a hold.
     */
    public fun enqueue(
        lease: ProjectionLease,
        letter: ProjectionLetter,
        failure: ProjectionFailureCode?,
        limits: ProjectionHoldLimits,
    ): ProjectionHoldEnqueue

    /** Claims at most one sequence and never begins a transaction or invokes application work. */
    public fun claimRedrive(
        lane: ProjectionLane,
        now: Instant,
        leaseFor: Duration,
    ): ProjectionRedriveClaim

    /** Reads an ordered finite prefix of the claimed sequence; the first entry is the only acknowledgement candidate. */
    public fun letters(
        lease: ProjectionRedriveLease,
        limit: Int,
    ): List<ProjectionRedriveLetter>

    /** Removes exactly the current queue head after its destination write committed in the caller's transaction. */
    public fun acknowledge(
        lease: ProjectionRedriveLease,
        position: Long,
    ): ProjectionRedriveAcknowledge

    /** Retains the head, increments its attempt state, and releases the sequence to a later fenced redrive. */
    public fun fail(
        lease: ProjectionRedriveLease,
        failure: ProjectionFailureCode,
    ): ProjectionRedriveFailure

    /** Operator-only skip. It removes the held queue only after writing its immutable hole record. */
    public fun evict(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoldEviction

    /** Makes the explicit policy decision required before a generation with this hole may cut over. */
    public fun acknowledgeHole(
        id: ProjectionHoleId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoleAcknowledgement

    /** Bounded status read; it contains no queued envelope body. */
    public fun holds(
        lane: ProjectionLane,
        limit: Int,
    ): List<ProjectionHold>
}
