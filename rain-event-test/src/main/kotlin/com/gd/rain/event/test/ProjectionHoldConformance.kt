package com.gd.rain.event.test

import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.FactType
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StreamRef
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionCheckpointStore
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionFailureCode
import com.gd.rain.event.projection.ProjectionHoldEnqueue
import com.gd.rain.event.projection.ProjectionHoldEviction
import com.gd.rain.event.projection.ProjectionHoldLimits
import com.gd.rain.event.projection.ProjectionHoleAcknowledgement
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLease
import com.gd.rain.event.projection.ProjectionLetter
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionOperator
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionRedriveAcknowledge
import com.gd.rain.event.projection.ProjectionRedriveClaim
import com.gd.rain.event.projection.ProjectionRedriveRelease
import com.gd.rain.event.projection.ProjectionSequenceId
import com.gd.rain.event.projection.ProjectionTopologyFingerprint
import com.gd.rain.event.projection.SameUnitProjectionHoldStore
import com.gd.rain.event.projection.SequenceKeyHasherId
import java.time.Clock
import java.time.Duration

/**
 * Supplies a hold/checkpoint pair under the target's caller-owned transaction boundary.
 *
 * This certifies the durable parking protocol only. It deliberately does not certify that a projection destination,
 * hold store, and checkpoint share one transaction authority; that is an integration property of a SAME_UNIT runner.
 */
public interface ProjectionHoldConformanceTarget : ProjectionCheckpointConformanceTarget {
    public fun holdStore(): SameUnitProjectionHoldStore

    /** Executes [block] inside one already-configured target transaction; it never begins a hidden worker transaction. */
    public fun <T : Any> inUnit(block: () -> T): T
}

/**
 * Reusable conformance for the durable ParkSequence/redrive state machine.
 *
 * Every section uses a separate lane, so a failed section cannot contaminate later evidence. The launcher creates only
 * synthetic immutable envelopes; it neither appends to an event store nor invokes an application projection handler.
 */
public class ProjectionHoldConformance(
    private val target: ProjectionHoldConformanceTarget,
    private val runId: String,
    private val clock: Clock,
) {
    init {
        require(RUN_ID.matches(runId)) { "projection hold conformance run id is not stable" }
        require(TARGET_ID.matches(target.id)) { "projection hold conformance target id is not stable" }
    }

    public fun verify(): EventConformanceReport =
        EventConformanceReport(
            target.id,
            listOf(
                verify(LIVE_SEQUENCE_ORDER, ::liveSequenceOrder),
                verify(REDRIVE_FENCE, ::redriveFence),
                verify(HEAD_ONLY_ACKNOWLEDGEMENT, ::headOnlyAcknowledgement),
                verify(CAPACITY_REFUSAL, ::capacityRefusal),
                verify(OPERATOR_HOLE, ::operatorHole),
            ),
        )

    private fun verify(
        id: String,
        body: () -> Unit,
    ): EventConformanceSection =
        try {
            body()
            EventConformanceSection(id, EventConformanceStatus.PASSED)
        } catch (failure: Exception) {
            EventConformanceSection(id, EventConformanceStatus.FAILED, failure.javaClass.simpleName.take(MAX_FAILURE_TYPE))
        }

    private fun liveSequenceOrder() {
        val setup = setup(LIVE_SEQUENCE_ORDER)
        val first = event(1)
        val second = event(2)
        queue(setup, first)
        val redrive = claimRedrive(setup)
        target.inUnit {
            val lease = claimCheckpoint(setup)
            val queued = target.holdStore().enqueue(lease, letter(second), null, LIMITS)
            check(queued is ProjectionHoldEnqueue.Queued && !queued.repeated) {
                "live envelope was not retained behind a redriving sequence"
            }
            target.checkpointStore().release(lease)
            Unit
        }
        target.inUnit {
            check(target.holdStore().letters(redrive.lease, 10).map { it.letter.event.position } == listOf(1L, 2L)) {
                "redrive letters are not in causal event-log order"
            }
            check(target.holdStore().acknowledge(redrive.lease, first.position) == ProjectionRedriveAcknowledge.Advanced(1)) {
                "redrive could not acknowledge its queue head"
            }
            check(target.holdStore().release(redrive.lease) == ProjectionRedriveRelease.RELEASED) {
                "redrive could not release a remaining sequence"
            }
            Unit
        }
    }

    private fun redriveFence() {
        val setup = setup(REDRIVE_FENCE)
        val first = event(1)
        queue(setup, first)
        val original = claimRedrive(setup)
        target.inUnit {
            check(target.holdStore().claimRedrive(setup.lane, now(), REDRIVE_LEASE) is ProjectionRedriveClaim.Busy) {
                "a live redrive lease did not exclude a second owner"
            }
            Unit
        }
        target.inUnit {
            check(target.holdStore().release(original.lease) == ProjectionRedriveRelease.RELEASED) {
                "original redrive lease could not be released"
            }
            Unit
        }
        val replacement = claimRedrive(setup)
        check(replacement.lease.fence > original.lease.fence) { "redrive reclaim did not advance its fence" }
        val stale = target.inUnit { target.holdStore().acknowledge(original.lease, first.position) }
        check(stale == ProjectionRedriveAcknowledge.LostLease) { "stale redrive lease acknowledged the queue head" }
        target.inUnit {
            check(
                target
                    .holdStore()
                    .letters(replacement.lease, 1)
                    .single()
                    .letter.event.position == first.position,
            ) {
                "stale redrive acknowledgement changed the durable queue"
            }
            check(target.holdStore().release(replacement.lease) == ProjectionRedriveRelease.RELEASED) {
                "replacement redrive lease could not be released"
            }
            Unit
        }
    }

    private fun headOnlyAcknowledgement() {
        val setup = setup(HEAD_ONLY_ACKNOWLEDGEMENT)
        val first = event(1)
        val second = event(2)
        queue(setup, first, second)
        val redrive = claimRedrive(setup)
        target.inUnit {
            val later = runCatching { target.holdStore().acknowledge(redrive.lease, second.position) }
            check(later.isFailure) { "redrive acknowledged a non-head letter" }
            check(target.holdStore().letters(redrive.lease, 10).map { it.letter.event.position } == listOf(1L, 2L)) {
                "non-head acknowledgement changed the durable queue"
            }
            check(target.holdStore().release(redrive.lease) == ProjectionRedriveRelease.RELEASED) {
                "redrive lease could not be released after a refused acknowledgement"
            }
            Unit
        }
    }

    private fun capacityRefusal() {
        val setup = setup(CAPACITY_REFUSAL)
        val first = event(1)
        val second = event(2)
        target.inUnit {
            val lease = claimCheckpoint(setup)
            check(target.holdStore().enqueue(lease, letter(first), FAILURE, ONE_LETTER) is ProjectionHoldEnqueue.Queued) {
                "initial parking letter was not retained"
            }
            check(target.holdStore().enqueue(lease, letter(second), null, ONE_LETTER) is ProjectionHoldEnqueue.CapacityExceeded) {
                "park capacity did not refuse a non-retained letter"
            }
            target.checkpointStore().release(lease)
            Unit
        }
        target.inUnit {
            val hold = target.holdStore().holds(setup.lane, 10).singleOrNull()
            check(hold?.letterCount == 1 && hold.firstPosition == first.position) {
                "capacity refusal changed or dropped the causal queue head"
            }
            Unit
        }
    }

    private fun operatorHole() {
        val setup = setup(OPERATOR_HOLE)
        val first = event(1)
        queue(setup, first)
        val redrive = claimRedrive(setup)
        target.inUnit {
            check(target.holdStore().evict(setup.lane, sequence(), OPERATOR, HOLE_REASON) is ProjectionHoldEviction.Busy) {
                "operator eviction bypassed a live redrive lease"
            }
            check(target.holdStore().release(redrive.lease) == ProjectionRedriveRelease.RELEASED) {
                "redrive lease could not be released before operator eviction"
            }
            Unit
        }
        val eviction =
            target.inUnit {
                target.holdStore().evict(setup.lane, sequence(), OPERATOR, HOLE_REASON)
            } as? ProjectionHoldEviction.Evicted
                ?: error("operator eviction did not create an immutable hole")
        check(!eviction.hole.acknowledged) { "operator skip implicitly acknowledged its hole" }
        val acknowledged = target.inUnit { target.holdStore().acknowledgeHole(eviction.hole.id, OPERATOR, ACKNOWLEDGEMENT_REASON) }
        check(acknowledged is ProjectionHoleAcknowledgement.Acknowledged && acknowledged.hole.acknowledged) {
            "explicit hole acknowledgement did not persist"
        }
        val repeated = target.inUnit { target.holdStore().acknowledgeHole(eviction.hole.id, OPERATOR, ACKNOWLEDGEMENT_REASON) }
        check(repeated == ProjectionHoleAcknowledgement.AlreadyAcknowledged) {
            "a repeated hole acknowledgement was not idempotent"
        }
    }

    private fun queue(
        setup: Setup,
        vararg events: StoredEvent,
    ) {
        target.inUnit {
            val lease = claimCheckpoint(setup)
            events.forEachIndexed { index, event ->
                val failure = FAILURE.takeIf { index == 0 }
                check(target.holdStore().enqueue(lease, letter(event), failure, LIMITS) is ProjectionHoldEnqueue.Queued) {
                    "projection hold did not retain a queued letter"
                }
            }
            target.checkpointStore().release(lease)
            Unit
        }
    }

    private fun claimCheckpoint(setup: Setup): ProjectionLease =
        acquired(target.checkpointStore().claim(setup.lane, setup.contract, setup.initial, now(), CHECKPOINT_LEASE)).lease

    private fun claimRedrive(setup: Setup): ProjectionRedriveClaim.Acquired =
        target.inUnit {
            target.holdStore().claimRedrive(setup.lane, now(), REDRIVE_LEASE) as? ProjectionRedriveClaim.Acquired
                ?: error("projection hold conformance expected a redrive lease")
        }

    private fun setup(section: String): Setup {
        val lane =
            ProjectionLane(
                ProjectionName.of("conformance-$runId-$section"),
                com.gd.rain.event.projection
                    .ProjectionGeneration(1),
                ProjectionPartition.WHOLE,
            )
        return Setup(
            lane,
            ProjectionCheckpointContract(
                target.origin,
                ProjectionContractRevision(1),
                ProjectionTopologyFingerprint.of(ByteArray(ProjectionTopologyFingerprint.BYTES) { section.length.toByte() }),
            ),
            EventLogCursor.start(target.origin),
        )
    }

    private fun event(position: Long): StoredEvent =
        StoredEvent(
            StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 7 }), "conformance", "parked"),
            position,
            position,
            FactType("conformance.parked", 1),
            EventBytes.utf8("payload-$position"),
            EventBytes.utf8("metadata"),
            now(),
        )

    private fun letter(event: StoredEvent): ProjectionLetter = ProjectionLetter(sequence(), event)

    private fun sequence(): ProjectionSequenceId = ProjectionSequenceId.forStream(HASHER, STREAM)

    private fun now(): java.time.Instant = clock.instant()

    private fun acquired(claim: ProjectionClaim): ProjectionClaim.Acquired =
        claim as? ProjectionClaim.Acquired ?: error("projection hold conformance expected a checkpoint lease")

    private data class Setup(
        val lane: ProjectionLane,
        val contract: ProjectionCheckpointContract,
        val initial: EventLogCursor,
    )

    private companion object {
        val TARGET_ID: Regex = Regex("^[a-z][a-z0-9.-]{0,127}$")
        val RUN_ID: Regex = Regex("^[a-z][a-z0-9-]{0,48}$")
        val HASHER: SequenceKeyHasherId = SequenceKeyHasherId.of("conformance.v1")
        val STREAM: StreamRef = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 7 }), "conformance", "parked")
        val FAILURE: ProjectionFailureCode = ProjectionFailureCode.of("conformance.parked")
        val LIMITS: ProjectionHoldLimits = ProjectionHoldLimits(maxLetters = 10, maxBytes = 64 * 1024)
        val ONE_LETTER: ProjectionHoldLimits = ProjectionHoldLimits(maxLetters = 1, maxBytes = 64 * 1024)
        val OPERATOR: ProjectionOperator = ProjectionOperator("operator", "conformance")
        const val HOLE_REASON: String = "discard a corrupt conformance envelope"
        const val ACKNOWLEDGEMENT_REASON: String = "conformance rebuild policy acknowledgement"
        val CHECKPOINT_LEASE: Duration = Duration.ofMinutes(1)
        val REDRIVE_LEASE: Duration = Duration.ofMinutes(1)
        const val MAX_FAILURE_TYPE: Int = 256
        const val LIVE_SEQUENCE_ORDER: String = "projection.hold.live-sequence-order"
        const val REDRIVE_FENCE: String = "projection.hold.redrive-fence"
        const val HEAD_ONLY_ACKNOWLEDGEMENT: String = "projection.hold.head-only-acknowledgement"
        const val CAPACITY_REFUSAL: String = "projection.hold.capacity-refusal"
        const val OPERATOR_HOLE: String = "projection.hold.operator-hole"
    }
}
