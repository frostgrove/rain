package com.gd.rain.event.projection

import com.gd.rain.event.CommittedEventLog
import java.time.Clock
import java.time.Duration

/** Observable result of one bounded asynchronous projection pass. */
public sealed interface AfterApplyPass {
    public data class Advanced(
        public val checkpoint: ProjectionCheckpoint,
        public val delivered: Int,
        public val applied: Int,
        public val hasMore: Boolean,
    ) : AfterApplyPass

    /** No settled envelopes were available; the persisted cursor may still carry a newer settlement boundary. */
    public data class Idle(
        public val checkpoint: ProjectionCheckpoint,
    ) : AfterApplyPass

    public data class Busy(
        public val retryAt: java.time.Instant,
    ) : AfterApplyPass

    /** The lane has a durable permanent stop and this at-least-once pass did not invoke its handler. */
    public data class Halted(
        public val halt: ProjectionHalt,
    ) : AfterApplyPass

    public data object ContractDrift : AfterApplyPass

    /** The handler may already have applied its idempotent destination effect; the cursor did not advance. */
    public data object LostLease : AfterApplyPass
}

/**
 * One-page runner for an explicit at-least-once projection destination.
 *
 * The checkpoint is claimed before the application handler and advances only afterwards. A crash
 * or stale lease between those actions redelivers the same envelopes, so handler destinations must
 * be idempotent by event position or stream/version. This runner intentionally refuses SAME_UNIT:
 * an adapter that proves one transaction authority owns that stronger protocol separately.
 */
public class AfterApplyProjectionRunner(
    private val definition: ProjectionDefinition,
    private val log: CommittedEventLog,
    private val checkpoints: ProjectionCheckpointStore,
    private val clock: Clock,
    private val leaseFor: Duration,
) {
    init {
        require(definition.spec.advancement == ProjectionAdvancement.AFTER_APPLY) {
            "after-apply runner cannot execute a same-unit projection"
        }
        require(!leaseFor.isNegative && !leaseFor.isZero) { "projection lease duration is positive" }
        require(leaseFor <= MAX_LEASE) { "projection lease duration exceeds $MAX_LEASE" }
    }

    /** Claims and processes one finite source page for [lane], never retrying the handler inside this pass. */
    public fun run(lane: ProjectionLane): AfterApplyPass {
        val spec = definition.spec
        require(lane.projection == spec.name) { "projection lane belongs to another projection" }
        require(lane.partition in spec.cover.members) { "projection lane partition is not in the declared cover" }
        val contract = ProjectionCheckpointContract(log.origin, spec.revision, spec.cover.fingerprint)
        val now = clock.instant()
        return when (val claim = checkpoints.claim(lane, contract, log.initialCursor(), now, leaseFor)) {
            is ProjectionClaim.Busy -> AfterApplyPass.Busy(claim.retryAt)
            is ProjectionClaim.Halted -> AfterApplyPass.Halted(claim.halt)
            ProjectionClaim.ContractDrift -> AfterApplyPass.ContractDrift
            is ProjectionClaim.Acquired -> runClaimed(claim, spec)
        }
    }

    private fun runClaimed(
        claim: ProjectionClaim.Acquired,
        spec: ProjectionSpec,
    ): AfterApplyPass {
        try {
            val page = log.readCommitted(claim.checkpoint.cursor, spec.budget.pageSize)
            if (page.events.isEmpty()) {
                return when (val advanced = checkpoints.advance(claim.lease, page.next, clock.instant())) {
                    is ProjectionAdvance.Advanced -> AfterApplyPass.Idle(advanced.checkpoint)
                    ProjectionAdvance.LostLease -> AfterApplyPass.LostLease
                }
            }
            val handled =
                page.events.filter { event ->
                    spec.cover.partitionFor(event) == claim.checkpoint.lane.partition && spec.route(event) == ProjectionRoute.Handle
                }
            if (handled.isNotEmpty()) {
                definition.handler.apply(ProjectionBatch(spec.name, handled, page.next))
            }
            return when (val advanced = checkpoints.advance(claim.lease, page.next, clock.instant())) {
                is ProjectionAdvance.Advanced -> AfterApplyPass.Advanced(advanced.checkpoint, page.events.size, handled.size, page.hasMore)
                ProjectionAdvance.LostLease -> AfterApplyPass.LostLease
            }
        } finally {
            checkpoints.release(claim.lease)
        }
    }

    private companion object {
        val MAX_LEASE: Duration = Duration.ofMinutes(10)
    }
}
