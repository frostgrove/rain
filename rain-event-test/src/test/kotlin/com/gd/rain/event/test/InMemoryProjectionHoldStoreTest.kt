package com.gd.rain.event.test

import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.FactType
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StreamRef
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionHoldEnqueue
import com.gd.rain.event.projection.ProjectionHoldEviction
import com.gd.rain.event.projection.ProjectionHoldLimits
import com.gd.rain.event.projection.ProjectionHoldState
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLetter
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionOperator
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionRedriveAcknowledge
import com.gd.rain.event.projection.ProjectionRedriveClaim
import com.gd.rain.event.projection.ProjectionRedriveRelease
import com.gd.rain.event.projection.ProjectionSequenceId
import com.gd.rain.event.projection.ProjectionTopologyFingerprint
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryProjectionHoldStoreTest {
    private val clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
    private val origin = EventLogOrigin(EventLogId.random())
    private val source = EventLogCursor.start(origin)
    private val lane =
        ProjectionLane(
            ProjectionName.of("counter-park"),
            com.gd.rain.event.projection
                .ProjectionGeneration(1),
            ProjectionPartition.WHOLE,
        )
    private val contract =
        ProjectionCheckpointContract(origin, ProjectionContractRevision(1), ProjectionTopologyFingerprint.of(ByteArray(32) { 4 }))
    private val checkpoints = InMemoryProjectionCheckpointStore()
    private val holds = InMemoryProjectionHoldStore(checkpoints, clock)

    @Test
    fun `reference rejects a corrupted duplicate and capacity never silently drops a letter`() {
        val first = event(position = 1, payload = "one")
        val sequence = sequence(first)
        val lease = claimLive()
        val limits = ProjectionHoldLimits(maxLetters = 1, maxBytes = 64 * 1024)

        assertThat(holds.enqueue(lease, ProjectionLetter(sequence, first), failureCode(), limits))
            .isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
        assertThatThrownBy {
            holds.enqueue(lease, ProjectionLetter(sequence, event(position = 1, payload = "tampered")), null, limits)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("projection letter position has a different immutable envelope")
        assertThat(holds.enqueue(lease, ProjectionLetter(sequence, event(position = 2, payload = "two")), null, limits))
            .isInstanceOf(ProjectionHoldEnqueue.CapacityExceeded::class.java)
        assertThat(holds.holds(lane, 10).single().letterCount).isEqualTo(1)
    }

    @Test
    fun `reference fences a stale redrive lease after expiry`() {
        val first = event(position = 1)
        queue(first)

        val original = claimRedrive()
        assertThat(holds.claimRedrive(lane, clock.instant(), Duration.ofMinutes(1)))
            .isInstanceOf(ProjectionRedriveClaim.Busy::class.java)
        clock.advance(Duration.ofMinutes(1))
        val replacement = claimRedrive()

        assertThat(replacement.lease.fence).isGreaterThan(original.lease.fence)
        assertThat(holds.acknowledge(original.lease, first.position)).isEqualTo(ProjectionRedriveAcknowledge.LostLease)
        assertThat(holds.release(replacement.lease)).isEqualTo(ProjectionRedriveRelease.RELEASED)
    }

    @Test
    fun `reference retains a live envelope queued behind an active redrive in strict sequence order`() {
        val first = event(position = 1)
        val second = event(position = 2)
        val sequence = sequence(first)
        val initial = claimLive()
        assertThat(holds.enqueue(initial, ProjectionLetter(sequence, first), failureCode(), limits()))
            .isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
        checkpoints.release(initial)
        val redrive = claimRedrive()
        val live = claimLive()

        assertThat(holds.enqueue(live, ProjectionLetter(sequence, second), null, limits()))
            .isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
        assertThat(holds.letters(redrive.lease, 10).map { it.letter.event.position }).containsExactly(1L, 2L)
        assertThat(holds.acknowledge(redrive.lease, first.position)).isEqualTo(ProjectionRedriveAcknowledge.Advanced(1))
        assertThat(holds.release(redrive.lease)).isEqualTo(ProjectionRedriveRelease.RELEASED)
        val next = claimRedrive()

        assertThat(holds.letters(next.lease, 10).map { it.letter.event.position }).containsExactly(2L)
        assertThat(holds.acknowledge(next.lease, second.position)).isEqualTo(ProjectionRedriveAcknowledge.Advanced(0))
        assertThat(holds.holds(lane, 10)).isEmpty()
    }

    @Test
    fun `reference requires a separate acknowledgement after an operator hole decision`() {
        val first = event(position = 1)
        val sequence = sequence(first)
        queue(first)
        val operator = ProjectionOperator("operator", "projection-admin")
        val redrive = claimRedrive()

        assertThat(holds.evict(lane, sequence, operator, "discard corrupt history"))
            .isInstanceOf(ProjectionHoldEviction.Busy::class.java)
        assertThat(holds.release(redrive.lease)).isEqualTo(ProjectionRedriveRelease.RELEASED)
        val eviction = holds.evict(lane, sequence, operator, "discard corrupt history") as ProjectionHoldEviction.Evicted

        assertThat(eviction.hole.acknowledged).isFalse()
        assertThat(holds.holds(lane, 10)).isEmpty()
        val acknowledged = holds.acknowledgeHole(eviction.hole.id, operator, "rebuild policy accepted")
        assertThat(acknowledged).isInstanceOf(com.gd.rain.event.projection.ProjectionHoleAcknowledgement.Acknowledged::class.java)
        assertThat((acknowledged as com.gd.rain.event.projection.ProjectionHoleAcknowledgement.Acknowledged).hole.acknowledged).isTrue()
        assertThat(holds.acknowledgeHole(eviction.hole.id, operator, "repeated acknowledgement"))
            .isEqualTo(com.gd.rain.event.projection.ProjectionHoleAcknowledgement.AlreadyAcknowledged)
    }

    private fun queue(vararg events: StoredEvent) {
        val lease = claimLive()
        events.forEachIndexed { index, event ->
            val failure = failureCode().takeIf { index == 0 }
            assertThat(holds.enqueue(lease, ProjectionLetter(sequence(event), event), failure, limits()))
                .isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
        }
        checkpoints.release(lease)
    }

    private fun claimLive(): com.gd.rain.event.projection.ProjectionLease =
        (checkpoints.claim(lane, contract, source, clock.instant(), Duration.ofMinutes(1)) as ProjectionClaim.Acquired).lease

    private fun claimRedrive(): ProjectionRedriveClaim.Acquired =
        holds.claimRedrive(lane, clock.instant(), Duration.ofMinutes(1)) as ProjectionRedriveClaim.Acquired

    private fun sequence(event: StoredEvent): ProjectionSequenceId = ProjectionSequenceId.forStream(HASHER, event.stream)

    private fun event(
        position: Long,
        payload: String = "payload-$position",
    ): StoredEvent =
        StoredEvent(
            StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 3 }), "counter", "one"),
            position,
            position,
            FactType("counter.incremented", 1),
            EventBytes.utf8(payload),
            EventBytes.utf8("metadata"),
            clock.instant(),
        )

    private fun failureCode(): com.gd.rain.event.projection.ProjectionFailureCode =
        com.gd.rain.event.projection.ProjectionFailureCode
            .of("destination.invalid")

    private fun limits(): ProjectionHoldLimits = ProjectionHoldLimits(maxLetters = 10, maxBytes = 64 * 1024)

    private companion object {
        val HASHER: com.gd.rain.event.projection.SequenceKeyHasherId =
            com.gd.rain.event.projection.SequenceKeyHasherId
                .of("stream.v1")
    }
}
