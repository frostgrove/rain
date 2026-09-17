package com.gd.rain.event.projection

import com.gd.rain.persistence.tx.SameTransactionAuthority
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One bounded redrive result; every successful pass applies and acknowledges exactly one queue head. */
public sealed interface ProjectionRedrivePass {
    /** No parked sequence was eligible for this lane. */
    public data object Idle : ProjectionRedrivePass

    /** Another redrive currently owns an eligible sequence. */
    public data class Busy(
        public val retryAt: Instant,
    ) : ProjectionRedrivePass

    /** The head was delivered and removed; [remainingLetters] stay in causal order for a later pass. */
    public data class Advanced(
        public val position: Long,
        public val remainingLetters: Int,
    ) : ProjectionRedrivePass

    /** Handler work was rolled back to its savepoint while a durable failed-attempt record was committed. */
    public data class Failed(
        public val position: Long,
        public val failure: ProjectionFailureCode,
    ) : ProjectionRedrivePass
}

/**
 * Replays one parked envelope in a caller-owned SAME_UNIT transaction.
 *
 * The runner has no transaction or retry loop. It claims, reads, handles, acknowledges or records one queue head
 * through the same proved transaction authority as its destination. A lease loss after handler work raises rather
 * than returns an apparent success, forcing the caller transaction to roll back the destination write.
 */
public class ProjectionRedriveRunner(
    private val park: ProjectionParkDefinition,
    private val holds: SameUnitProjectionHoldStore,
    private val destination: SameUnitProjectionDestination,
    private val clock: Clock,
    private val leaseFor: Duration,
    private val transientFailure: ProjectionFailureCode = TRANSIENT_FAILURE,
) {
    init {
        require(!leaseFor.isNegative && !leaseFor.isZero) { "projection redrive lease duration is positive" }
        require(leaseFor <= MAX_LEASE) { "projection redrive lease duration exceeds $MAX_LEASE" }
    }

    /** Claims and processes one queue head for [lane]; the caller must already own the destination transaction. */
    public fun run(lane: ProjectionLane): ProjectionRedrivePass =
        destination.inCallerTransaction { scopedDestination ->
            val destinationPlacement = destination.inspectPlacement()
            require(destinationPlacement.backing == scopedDestination.backing) {
                "projection destination changed backing inside its transaction"
            }
            SameTransactionAuthority.require(destinationPlacement.requireAuthority(), scopedDestination.authority)
            SameTransactionAuthority.require(destinationPlacement, holds.inspectPlacement())
            when (val claim = holds.claimRedrive(lane, clock.instant(), leaseFor)) {
                ProjectionRedriveClaim.Idle -> ProjectionRedrivePass.Idle
                is ProjectionRedriveClaim.Busy -> ProjectionRedrivePass.Busy(claim.retryAt)
                is ProjectionRedriveClaim.Acquired -> runClaimed(claim, scopedDestination)
            }
        }

    private fun runClaimed(
        claim: ProjectionRedriveClaim.Acquired,
        destination: ProjectionDestinationContext,
    ): ProjectionRedrivePass {
        val head =
            holds
                .letters(claim.lease, 1)
                .singleOrNull()
                ?: throw ProjectionRedriveLeaseLostException()
        val savepoint = destinationSavepoint()
        try {
            savepoint.inHandlerSavepoint { park.handler.apply(head.letter.event, destination) }
        } catch (failure: Throwable) {
            if (failure is ProjectionRedriveLeaseLostException) throw failure
            val code = failureCode(failure)
            return when (holds.fail(claim.lease, code)) {
                ProjectionRedriveFailure.RECORDED -> ProjectionRedrivePass.Failed(head.letter.event.position, code)
                ProjectionRedriveFailure.LOST_LEASE -> throw ProjectionRedriveLeaseLostException()
            }
        }
        return when (val acknowledged = holds.acknowledge(claim.lease, head.letter.event.position)) {
            is ProjectionRedriveAcknowledge.Advanced -> {
                if (acknowledged.remainingLetters > 0) release(claim.lease)
                ProjectionRedrivePass.Advanced(head.letter.event.position, acknowledged.remainingLetters)
            }

            ProjectionRedriveAcknowledge.LostLease -> {
                throw ProjectionRedriveLeaseLostException()
            }
        }
    }

    private fun release(lease: ProjectionRedriveLease) {
        if (holds.release(lease) == ProjectionRedriveRelease.LOST_LEASE) throw ProjectionRedriveLeaseLostException()
    }

    private fun failureCode(failure: Throwable): ProjectionFailureCode =
        when (val classified = park.classifier.classify(failure)) {
            is ProjectionFailure.Permanent -> classified.code
            ProjectionFailure.Transient -> transientFailure
        }

    private fun destinationSavepoint(): SameUnitProjectionSavepoint =
        destination as? SameUnitProjectionSavepoint
            ?: error("projection redrive destination cannot isolate a failed handler with a savepoint")

    public companion object {
        /** Stable diagnostic for a retryable redrive handler failure whose classifier supplies no more specific code. */
        public val TRANSIENT_FAILURE: ProjectionFailureCode = ProjectionFailureCode.of("projection.redrive_transient")

        private val MAX_LEASE: Duration = Duration.ofMinutes(10)
    }
}

/** A stale redrive outcome must abort the caller-owned unit, never commit its destination writes. */
public class ProjectionRedriveLeaseLostException : IllegalStateException("projection redrive lease was lost before same-unit commit")
