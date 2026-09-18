package com.gd.rain.event.test

import com.gd.rain.event.projection.ProjectionFailureCode
import com.gd.rain.event.projection.ProjectionHold
import com.gd.rain.event.projection.ProjectionHoldEnqueue
import com.gd.rain.event.projection.ProjectionHoldEviction
import com.gd.rain.event.projection.ProjectionHoldLimits
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
import com.gd.rain.event.projection.SameUnitProjectionHoldStore
import com.gd.rain.persistence.tx.TransactionPlacement
import java.time.Duration
import java.time.Instant

/**
 * Deliberately broken conformance fixture: a stale redrive acknowledgement uses the replacement lease's queue head.
 *
 * It exists only to prove that [ProjectionHoldConformance] detects redrive fencing. Applications must never use it.
 */
public class UnfencedProjectionHoldStore(
    private val delegate: InMemoryProjectionHoldStore,
) : SameUnitProjectionHoldStore {
    private val currentLeases: MutableMap<Address, ProjectionRedriveLease> = mutableMapOf()

    override fun inspectPlacement(): TransactionPlacement = delegate.inspectPlacement()

    override fun enqueue(
        lease: ProjectionLease,
        letter: ProjectionLetter,
        failure: ProjectionFailureCode?,
        limits: ProjectionHoldLimits,
    ): ProjectionHoldEnqueue = delegate.enqueue(lease, letter, failure, limits)

    override fun claimRedrive(
        lane: ProjectionLane,
        now: Instant,
        leaseFor: Duration,
    ): ProjectionRedriveClaim =
        delegate.claimRedrive(lane, now, leaseFor).also { claim ->
            if (claim is ProjectionRedriveClaim.Acquired) currentLeases[Address(lane, claim.lease.sequence)] = claim.lease
        }

    override fun letters(
        lease: ProjectionRedriveLease,
        limit: Int,
    ): List<ProjectionRedriveLetter> = delegate.letters(lease, limit)

    override fun acknowledge(
        lease: ProjectionRedriveLease,
        position: Long,
    ): ProjectionRedriveAcknowledge {
        val acknowledged = delegate.acknowledge(lease, position)
        if (acknowledged != ProjectionRedriveAcknowledge.LostLease) return acknowledged
        val replacement = currentLeases[Address(lease.lane, lease.sequence)] ?: return acknowledged
        return delegate.acknowledge(replacement, position)
    }

    override fun release(lease: ProjectionRedriveLease): ProjectionRedriveRelease = delegate.release(lease)

    override fun fail(
        lease: ProjectionRedriveLease,
        failure: ProjectionFailureCode,
    ): ProjectionRedriveFailure = delegate.fail(lease, failure)

    override fun evict(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoldEviction = delegate.evict(lane, sequence, operator, reason)

    override fun acknowledgeHole(
        id: ProjectionHoleId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoleAcknowledgement = delegate.acknowledgeHole(id, operator, reason)

    override fun holds(
        lane: ProjectionLane,
        limit: Int,
    ): List<ProjectionHold> = delegate.holds(lane, limit)

    private data class Address(
        val lane: ProjectionLane,
        val sequence: ProjectionSequenceId,
    )
}
