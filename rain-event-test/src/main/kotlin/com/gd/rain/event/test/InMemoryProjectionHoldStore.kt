package com.gd.rain.event.test

import com.gd.rain.event.projection.ProjectionFailureCode
import com.gd.rain.event.projection.ProjectionHold
import com.gd.rain.event.projection.ProjectionHoldEnqueue
import com.gd.rain.event.projection.ProjectionHoldEviction
import com.gd.rain.event.projection.ProjectionHoldLimits
import com.gd.rain.event.projection.ProjectionHoldState
import com.gd.rain.event.projection.ProjectionHoldStoreSupport
import com.gd.rain.event.projection.ProjectionHole
import com.gd.rain.event.projection.ProjectionHoleAcknowledgement
import com.gd.rain.event.projection.ProjectionHoleId
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLease
import com.gd.rain.event.projection.ProjectionLetter
import com.gd.rain.event.projection.ProjectionOperator
import com.gd.rain.event.projection.ProjectionRedriveAcknowledge
import com.gd.rain.event.projection.ProjectionRedriveClaim
import com.gd.rain.event.projection.ProjectionRedriveFailure
import com.gd.rain.event.projection.ProjectionRedriveLease
import com.gd.rain.event.projection.ProjectionRedriveLetter
import com.gd.rain.event.projection.ProjectionRedriveRelease
import com.gd.rain.event.projection.ProjectionSequenceId
import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionPlacement
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Deterministic serializable parking/redrive reference for contract tests, never a production adapter.
 *
 * It models durable rows as immutable letter snapshots guarded by explicit checkpoint and redrive leases. Its
 * placement deliberately has no transaction authority: tests use it to verify the hold protocol itself, while
 * PostgreSQL remains the SAME_UNIT authority reference.
 */
public class InMemoryProjectionHoldStore(
    private val checkpoints: InMemoryProjectionCheckpointStore,
    private val clock: Clock,
    backingName: String = "projection-holds",
) : ProjectionHoldStoreSupport() {
    private val backing: BackingIdentity = BackingIdentity.named("rain.event.memory", backingName)
    private val entries: MutableMap<Address, Entry> = mutableMapOf()
    private val holes: MutableMap<ProjectionHoleId, ProjectionHole> = mutableMapOf()
    private var nextHoleId: Long = 1

    override fun inspectPlacement(): TransactionPlacement = TransactionPlacement(backing, null)

    override fun enqueue(
        lease: ProjectionLease,
        letter: ProjectionLetter,
        failure: ProjectionFailureCode?,
        limits: ProjectionHoldLimits,
    ): ProjectionHoldEnqueue =
        synchronized(entries) {
            if (!checkpoints.isCurrent(lease, clock.instant())) return ProjectionHoldEnqueue.LostLease
            val address = Address(lease.lane, letter.sequence)
            var entry = entries[address]
            if (entry == null) {
                if (failure == null) return ProjectionHoldEnqueue.NotHeld
                entry = Entry(lease.lane, letter.sequence, letter.event.position, failure, clock.instant(), clock.instant())
                entries[address] = entry
            }
            val existing = entry.letters.firstOrNull { it.letter.event.position == letter.event.position }
            if (existing != null) {
                check(existing.letter.checksum == letter.checksum) {
                    "projection letter position has a different immutable envelope"
                }
                return ProjectionHoldEnqueue.Queued(snapshot(entry), repeated = true)
            }
            val lastPosition =
                entry.letters
                    .lastOrNull()
                    ?.letter
                    ?.event
                    ?.position
            require(lastPosition == null || letter.event.position > lastPosition) {
                "projection letters must append in strict event-log order"
            }
            if (entry.letters.size == limits.maxLetters || entry.letterBytes > limits.maxBytes - letter.retainedBytes) {
                return ProjectionHoldEnqueue.CapacityExceeded(snapshot(entry))
            }
            entry.letters += LetterState(letter)
            entry.letterBytes += letter.retainedBytes
            entry.updatedAt = clock.instant()
            ProjectionHoldEnqueue.Queued(snapshot(entry), repeated = false)
        }

    override fun claimRedrive(
        lane: ProjectionLane,
        now: Instant,
        leaseFor: Duration,
    ): ProjectionRedriveClaim =
        synchronized(entries) {
            require(!leaseFor.isNegative && !leaseFor.isZero) { "projection redrive lease duration is positive" }
            val candidate =
                entries.values
                    .asSequence()
                    .filter { entry -> entry.lane == lane && entry.isAvailable(now) }
                    .sortedWith(ENTRY_ORDER)
                    .firstOrNull()
            if (candidate != null) {
                val lease = newRedriveLease(lane, candidate.sequence, candidate.fence + 1)
                candidate.fence = lease.fence
                candidate.active = ActiveRedrive(lease, now.plus(leaseFor))
                candidate.updatedAt = now
                return ProjectionRedriveClaim.Acquired(snapshot(candidate), lease)
            }
            val retryAt =
                entries.values
                    .asSequence()
                    .filter { entry -> entry.lane == lane }
                    .mapNotNull { entry -> entry.active?.expiresAt?.takeIf { it.isAfter(now) } }
                    .minOrNull()
            if (retryAt == null) ProjectionRedriveClaim.Idle else ProjectionRedriveClaim.Busy(retryAt)
        }

    override fun letters(
        lease: ProjectionRedriveLease,
        limit: Int,
    ): List<ProjectionRedriveLetter> =
        synchronized(entries) {
            requireRedriveLease(lease)
            require(limit in 1..MAX_REDRIVE_LETTERS) { "projection redrive letter read limit is outside 1..$MAX_REDRIVE_LETTERS" }
            val entry = current(lease) ?: error("projection redrive lease is no longer current")
            entry.letters.take(limit).map(::snapshot)
        }

    override fun acknowledge(
        lease: ProjectionRedriveLease,
        position: Long,
    ): ProjectionRedriveAcknowledge =
        synchronized(entries) {
            requireRedriveLease(lease)
            require(position > 0) { "projection redrive acknowledgement position is positive" }
            val entry = current(lease) ?: return ProjectionRedriveAcknowledge.LostLease
            val head = checkNotNull(entry.letters.firstOrNull()) { "projection hold has no queue head" }
            require(head.letter.event.position == position) { "projection redrive may only acknowledge the queue head" }
            entry.letters.removeAt(0)
            entry.letterBytes -= head.letter.retainedBytes
            check(entry.letterBytes >= 0) { "projection hold counters do not match stored letters" }
            entry.updatedAt = clock.instant()
            val remaining = entry.letters.size
            if (remaining == 0) entries.remove(Address(entry.lane, entry.sequence))
            ProjectionRedriveAcknowledge.Advanced(remaining)
        }

    override fun release(lease: ProjectionRedriveLease): ProjectionRedriveRelease =
        synchronized(entries) {
            requireRedriveLease(lease)
            val entry = current(lease) ?: return ProjectionRedriveRelease.LOST_LEASE
            entry.active = null
            entry.updatedAt = clock.instant()
            ProjectionRedriveRelease.RELEASED
        }

    override fun fail(
        lease: ProjectionRedriveLease,
        failure: ProjectionFailureCode,
    ): ProjectionRedriveFailure =
        synchronized(entries) {
            requireRedriveLease(lease)
            val entry = current(lease) ?: return ProjectionRedriveFailure.LOST_LEASE
            val head = checkNotNull(entry.letters.firstOrNull()) { "projection hold has no queue head" }
            head.attempts++
            head.lastFailureCode = failure
            head.lastFailedAt = clock.instant()
            entry.active = null
            entry.updatedAt = clock.instant()
            ProjectionRedriveFailure.RECORDED
        }

    override fun evict(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoldEviction =
        synchronized(entries) {
            requireDecision(operator, reason)
            val address = Address(lane, sequence)
            val entry = entries[address] ?: return ProjectionHoldEviction.Missing
            val active = entry.active
            if (active != null && active.expiresAt.isAfter(clock.instant())) return ProjectionHoldEviction.Busy(active.expiresAt)
            val positions = entry.letters.map { it.letter.event.position }
            check(positions.isNotEmpty()) { "projection hold has no retained letters" }
            val hole =
                ProjectionHole(
                    ProjectionHoleId(nextHoleId++),
                    lane,
                    sequence,
                    positions.first(),
                    positions.last(),
                    operator,
                    reason,
                    clock.instant(),
                    null,
                )
            entries.remove(address)
            holes[hole.id] = hole
            ProjectionHoldEviction.Evicted(hole)
        }

    override fun acknowledgeHole(
        id: ProjectionHoleId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoleAcknowledgement =
        synchronized(entries) {
            requireDecision(operator, reason)
            val hole = holes[id] ?: return ProjectionHoleAcknowledgement.Missing
            if (hole.acknowledged) return ProjectionHoleAcknowledgement.AlreadyAcknowledged
            val acknowledged = hole.copy(acknowledgedAt = clock.instant())
            holes[id] = acknowledged
            ProjectionHoleAcknowledgement.Acknowledged(acknowledged)
        }

    override fun holds(
        lane: ProjectionLane,
        limit: Int,
    ): List<ProjectionHold> =
        synchronized(entries) {
            require(limit in 1..MAX_HOLD_STATUS) { "projection hold status limit is outside 1..$MAX_HOLD_STATUS" }
            entries.values
                .filter { it.lane == lane }
                .sortedWith(ENTRY_ORDER)
                .take(limit)
                .map(::snapshot)
        }

    private fun current(lease: ProjectionRedriveLease): Entry? {
        val entry = entries[Address(lease.lane, lease.sequence)] ?: return null
        val active = entry.active ?: return null
        return entry.takeIf { active.lease === lease && active.expiresAt.isAfter(clock.instant()) }
    }

    private fun snapshot(entry: Entry): ProjectionHold =
        ProjectionHold(
            entry.lane,
            entry.sequence,
            entry.firstPosition,
            entry.failureCode,
            if (entry.active == null) ProjectionHoldState.HELD else ProjectionHoldState.REDRIVING,
            entry.letters.size,
            entry.letterBytes,
            entry.fence,
            entry.createdAt,
            entry.updatedAt,
        )

    private fun snapshot(state: LetterState): ProjectionRedriveLetter =
        ProjectionRedriveLetter(state.letter, state.attempts, state.lastFailureCode, state.lastFailedAt)

    private fun requireDecision(
        operator: ProjectionOperator,
        reason: String,
    ) {
        require(operator.id.isNotBlank()) { "projection operator id is blank" }
        require(reason.isNotBlank() && reason.toByteArray(Charsets.UTF_8).size <= ProjectionHole.MAX_REASON_BYTES) {
            "projection hole reason is blank or too large"
        }
    }

    private data class Address(
        val lane: ProjectionLane,
        val sequence: ProjectionSequenceId,
    )

    private class Entry(
        val lane: ProjectionLane,
        val sequence: ProjectionSequenceId,
        val firstPosition: Long,
        val failureCode: ProjectionFailureCode,
        val createdAt: Instant,
        var updatedAt: Instant,
        val letters: MutableList<LetterState> = mutableListOf(),
        var letterBytes: Int = 0,
        var fence: Long = 0,
        var active: ActiveRedrive? = null,
    ) {
        fun isAvailable(now: Instant): Boolean = active == null || !checkNotNull(active).expiresAt.isAfter(now)
    }

    private data class ActiveRedrive(
        val lease: ProjectionRedriveLease,
        val expiresAt: Instant,
    )

    private class LetterState(
        val letter: ProjectionLetter,
        var attempts: Int = 0,
        var lastFailureCode: ProjectionFailureCode? = null,
        var lastFailedAt: Instant? = null,
    )

    private companion object {
        const val MAX_REDRIVE_LETTERS: Int = 1_000
        const val MAX_HOLD_STATUS: Int = 1_000

        val ENTRY_ORDER: Comparator<Entry> =
            compareBy<Entry>(Entry::firstPosition)
                .thenComparator { left, right -> compareBytes(left.sequence.copy(), right.sequence.copy()) }

        fun compareBytes(
            left: ByteArray,
            right: ByteArray,
        ): Int {
            left.zip(right).forEach { (one, other) ->
                val compared = one.toUByte().compareTo(other.toUByte())
                if (compared != 0) return compared
            }
            return left.size.compareTo(right.size)
        }
    }
}
