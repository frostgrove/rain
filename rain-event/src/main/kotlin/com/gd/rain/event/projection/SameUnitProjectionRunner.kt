package com.gd.rain.event.projection

import com.gd.rain.event.CommittedEventLog
import com.gd.rain.event.StoredEvent
import com.gd.rain.persistence.tx.SameTransactionAuthority
import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.persistence.tx.TransactionPlacement
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Scoped destination capability. It proves the caller-owned unit but exposes no event append API or raw connection. */
public data class ProjectionDestinationContext internal constructor(
    public val backing: com.gd.rain.persistence.tx.BackingIdentity,
    internal val authority: TransactionAuthority,
)

/**
 * Opens no transaction. An adapter verifies an already active destination transaction and supplies
 * a scoped marker to the handler; failure escaping [inCallerTransaction] must roll that transaction back.
 */
public interface SameUnitProjectionDestination {
    public fun inspectPlacement(): TransactionPlacement

    public fun <T> inCallerTransaction(block: (ProjectionDestinationContext) -> T): T
}

/**
 * Optional savepoint boundary for a classified permanent failure.
 *
 * PARK_SEQUENCE catches only after this adapter rolls back the failed handler's partial destination writes; without
 * that boundary the runner must propagate the exception and let the whole caller transaction roll back.
 */
public interface SameUnitProjectionSavepoint {
    public fun <T> inHandlerSavepoint(block: () -> T): T
}

/** Base for destination adapters that must mint a context only after proving an active transaction. */
public abstract class SameUnitProjectionDestinationSupport : SameUnitProjectionDestination {
    protected fun context(placement: TransactionPlacement): ProjectionDestinationContext =
        ProjectionDestinationContext(placement.backing, placement.requireAuthority())
}

/** SAME_UNIT handler receives no EventStore/EventRepository capability and is invoked at most once per pass. */
public fun interface SameUnitProjectionHandler {
    public fun apply(
        batch: ProjectionBatch,
        destination: ProjectionDestinationContext,
    ): Unit
}

/** Per-envelope work required by PARK_SEQUENCE; it makes the failing causal sequence unambiguous. */
public fun interface SameUnitProjectionEventHandler {
    public fun apply(
        event: StoredEvent,
        destination: ProjectionDestinationContext,
    ): Unit
}

/** Explicit permanent-failure path: its handler is isolated with a savepoint before a hold is committed. */
public data class ProjectionParkDefinition(
    public val handler: SameUnitProjectionEventHandler,
    public val classifier: ProjectionFailureClassifier,
    public val limits: ProjectionHoldLimits,
)

/** Explicit declaration for the stronger same-database protocol. */
public data class SameUnitProjectionDefinition(
    public val spec: ProjectionSpec,
    public val handler: SameUnitProjectionHandler,
    public val park: ProjectionParkDefinition?,
) {
    public constructor(
        spec: ProjectionSpec,
        handler: SameUnitProjectionHandler,
    ) : this(spec, handler, null)

    init {
        require((spec.permanentFailurePolicy == ProjectionPermanentFailurePolicy.PARK_SEQUENCE) == (park != null)) {
            "park sequence projection requires exactly one per-envelope park definition"
        }
    }

    public companion object {
        /** Declares the only mode that can continue unrelated sequences after a classified permanent failure. */
        public fun parked(
            spec: ProjectionSpec,
            handler: SameUnitProjectionEventHandler,
            classifier: ProjectionFailureClassifier,
            limits: ProjectionHoldLimits,
        ): SameUnitProjectionDefinition =
            SameUnitProjectionDefinition(
                spec,
                SameUnitProjectionHandler { _, _ -> error("park-sequence projection has no batch handler") },
                ProjectionParkDefinition(handler, classifier, limits),
            )
    }
}

/** Result of one caller-transaction-bound SAME_UNIT pass. */
public sealed interface SameUnitPass {
    public data class Advanced(
        public val checkpoint: ProjectionCheckpoint,
        public val delivered: Int,
        public val applied: Int,
        public val hasMore: Boolean,
    ) : SameUnitPass

    public data class Idle(
        public val checkpoint: ProjectionCheckpoint,
    ) : SameUnitPass

    public data class Busy(
        public val retryAt: Instant,
    ) : SameUnitPass

    /** The lane has a durable permanent stop and no handler was invoked by this pass. */
    public data class Halted(
        public val halt: ProjectionHalt,
    ) : SameUnitPass

    /** The cursor advanced while at least one sequence was durably queued rather than delivered. */
    public data class Parked(
        public val checkpoint: ProjectionCheckpoint,
        public val delivered: Int,
        public val applied: Int,
        public val queued: Int,
        public val hasMore: Boolean,
    ) : SameUnitPass

    public data object ContractDrift : SameUnitPass

    /** Another runner advanced after the source page was read, so this runner invokes no handler. */
    public data object Rebased : SameUnitPass

    public data object LostLease : SameUnitPass
}

/**
 * One finite atomic projection pass for a destination that shares a proven transaction authority
 * with its checkpoint store. It deliberately has no retry loop: a caller may retry the whole pass
 * only after transaction rollback, which redelivers the page as a new delivery attempt.
 */
public class SameUnitProjectionRunner(
    private val definition: SameUnitProjectionDefinition,
    private val log: CommittedEventLog,
    private val checkpoints: SameUnitProjectionCheckpointStore,
    private val destination: SameUnitProjectionDestination,
    private val clock: Clock,
    private val leaseFor: Duration,
    private val holds: SameUnitProjectionHoldStore? = null,
) {
    init {
        require(definition.spec.advancement == ProjectionAdvancement.SAME_UNIT) {
            "same-unit runner cannot execute an after-apply projection"
        }
        require(!leaseFor.isNegative && !leaseFor.isZero) { "projection lease duration is positive" }
        require(leaseFor <= MAX_LEASE) { "projection lease duration exceeds $MAX_LEASE" }
        require((definition.spec.permanentFailurePolicy == ProjectionPermanentFailurePolicy.PARK_SEQUENCE) == (holds != null)) {
            "park sequence projection requires exactly one same-unit hold store"
        }
    }

    /** Reads one source page before entering the destination transaction, then claims and commits it atomically. */
    public fun run(lane: ProjectionLane): SameUnitPass {
        val spec = definition.spec
        require(lane.projection == spec.name) { "projection lane belongs to another projection" }
        require(lane.partition in spec.cover.members) { "projection lane partition is not in the declared cover" }
        val contract = ProjectionCheckpointContract(log.origin, spec.revision, spec.cover.fingerprint)
        val observed = checkpoints.checkpoint(lane)
        if (observed != null && observed.contract != contract) return SameUnitPass.ContractDrift
        val sourceCursor = observed?.cursor ?: log.initialCursor()
        val page = log.readCommitted(sourceCursor, spec.budget.pageSize)
        return destination.inCallerTransaction { scopedDestination ->
            val destinationPlacement = destination.inspectPlacement()
            require(
                destinationPlacement.backing == scopedDestination.backing,
            ) { "projection destination changed backing inside its transaction" }
            SameTransactionAuthority.require(destinationPlacement.requireAuthority(), scopedDestination.authority)
            SameTransactionAuthority.require(destinationPlacement, checkpoints.inspectPlacement())
            holds?.let { SameTransactionAuthority.require(destinationPlacement, it.inspectPlacement()) }
            when (val claim = checkpoints.claim(lane, contract, sourceCursor, clock.instant(), leaseFor)) {
                is ProjectionClaim.Busy -> SameUnitPass.Busy(claim.retryAt)
                is ProjectionClaim.Halted -> SameUnitPass.Halted(claim.halt)
                ProjectionClaim.ContractDrift -> SameUnitPass.ContractDrift
                is ProjectionClaim.Acquired -> runClaimed(claim, spec, sourceCursor, page, scopedDestination)
            }
        }
    }

    private fun runClaimed(
        claim: ProjectionClaim.Acquired,
        spec: ProjectionSpec,
        sourceCursor: com.gd.rain.event.EventLogCursor,
        page: com.gd.rain.event.CommittedLogPage,
        destination: ProjectionDestinationContext,
    ): SameUnitPass {
        if (claim.checkpoint.cursor != sourceCursor) {
            checkpoints.release(claim.lease)
            return SameUnitPass.Rebased
        }
        if (page.events.isEmpty()) {
            return when (val advanced = checkpoints.advance(claim.lease, page.next, clock.instant())) {
                is ProjectionAdvance.Advanced -> SameUnitPass.Idle(advanced.checkpoint)
                ProjectionAdvance.LostLease -> SameUnitPass.LostLease
            }
        }
        if (spec.permanentFailurePolicy == ProjectionPermanentFailurePolicy.PARK_SEQUENCE) {
            return runParked(claim, spec, page, destination)
        }
        val handled =
            page.events.filter { event ->
                spec.cover.partitionFor(event) == claim.checkpoint.lane.partition && spec.route(event) == ProjectionRoute.Handle
            }
        if (handled.isNotEmpty()) definition.handler.apply(ProjectionBatch(spec.name, handled, page.next), destination)
        return when (val advanced = checkpoints.advance(claim.lease, page.next, clock.instant())) {
            is ProjectionAdvance.Advanced -> SameUnitPass.Advanced(advanced.checkpoint, page.events.size, handled.size, page.hasMore)
            ProjectionAdvance.LostLease -> throw ProjectionLeaseLostException()
        }
    }

    private fun runParked(
        claim: ProjectionClaim.Acquired,
        spec: ProjectionSpec,
        page: com.gd.rain.event.CommittedLogPage,
        destination: ProjectionDestinationContext,
    ): SameUnitPass {
        val park = checkNotNull(definition.park) { "park sequence projection has no park definition" }
        val holdStore = checkNotNull(holds) { "park sequence projection has no hold store" }
        val isolation = destinationSavepoint()
        val haltStore = checkpoints as? SameUnitProjectionHaltStore
            ?: error("park sequence projection checkpoint store cannot durably halt a lane")
        var applied = 0
        var queued = 0
        page.events.forEach { event ->
            if (spec.cover.partitionFor(event) != claim.checkpoint.lane.partition) return@forEach
            val route = spec.route(event) ?: return@forEach
            val letter = ProjectionLetter(spec.cover.hasher.sequence(event), event)
            when (val state = holdStore.enqueue(claim.lease, letter, null, park.limits)) {
                ProjectionHoldEnqueue.NotHeld -> {
                    if (route == ProjectionRoute.Handle) {
                        try {
                            isolation.inHandlerSavepoint { park.handler.apply(event, destination) }
                            applied++
                        } catch (failure: Throwable) {
                            when (val classified = park.classifier.classify(failure)) {
                                ProjectionFailure.Transient -> throw failure
                                is ProjectionFailure.Permanent ->
                                    when (val parked = holdStore.enqueue(claim.lease, letter, classified.code, park.limits)) {
                                        is ProjectionHoldEnqueue.Queued -> queued++
                                        is ProjectionHoldEnqueue.CapacityExceeded -> return halt(haltStore, claim, event, capacityFailure())
                                        ProjectionHoldEnqueue.LostLease -> throw ProjectionLeaseLostException()
                                        ProjectionHoldEnqueue.NotHeld -> error("permanent failure did not create its projection hold")
                                    }
                            }
                        }
                    }
                }

                is ProjectionHoldEnqueue.Queued -> queued++
                is ProjectionHoldEnqueue.CapacityExceeded -> return halt(haltStore, claim, event, capacityFailure())
                ProjectionHoldEnqueue.LostLease -> throw ProjectionLeaseLostException()
            }
        }
        return when (val advanced = checkpoints.advance(claim.lease, page.next, clock.instant())) {
            is ProjectionAdvance.Advanced ->
                if (queued == 0) {
                    SameUnitPass.Advanced(advanced.checkpoint, page.events.size, applied, page.hasMore)
                } else {
                    SameUnitPass.Parked(advanced.checkpoint, page.events.size, applied, queued, page.hasMore)
                }

            ProjectionAdvance.LostLease -> throw ProjectionLeaseLostException()
        }
    }

    private fun halt(
        haltStore: SameUnitProjectionHaltStore,
        claim: ProjectionClaim.Acquired,
        event: StoredEvent,
        failure: ProjectionFailureCode,
    ): SameUnitPass.Halted =
        when (val halted = haltStore.halt(claim.lease, event.position, failure)) {
            is ProjectionHaltResult.Halted -> SameUnitPass.Halted(halted.halt)
            ProjectionHaltResult.LostLease -> throw ProjectionLeaseLostException()
        }

    private fun destinationSavepoint(): SameUnitProjectionSavepoint =
        destination as? SameUnitProjectionSavepoint
            ?: error("park sequence projection destination cannot isolate a failed handler with a savepoint")

    private fun capacityFailure(): ProjectionFailureCode = ProjectionFailureCode.of("projection.hold_capacity")

    private companion object {
        val MAX_LEASE: Duration = Duration.ofMinutes(10)
    }
}

/** Returning a typed pass after destination work would commit a broken SAME_UNIT; force the caller transaction to roll back. */
public class ProjectionLeaseLostException : IllegalStateException("projection checkpoint lease was lost before same-unit commit")
