package com.gd.rain.event.test

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.projection.ProjectionAdvance
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionCheckpointStore
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionTopologyFingerprint
import java.time.Clock
import java.time.Duration

/** Stable outcome vocabulary shared by reusable event conformance launchers. */
public enum class EventConformanceStatus {
    PASSED,
    NOT_CERTIFIED,
    FAILED,
}

/** One bounded section result. Failure detail identifies a violated protocol section without serializing a stack trace. */
public data class EventConformanceSection(
    public val id: String,
    public val status: EventConformanceStatus,
    public val detail: String? = null,
) {
    init {
        require(ID.matches(id)) { "event conformance section id is not stable" }
        require((status == EventConformanceStatus.FAILED) == (detail != null)) {
            "event conformance section failure detail does not match its status"
        }
        require(detail == null || detail.length <= MAX_DETAIL_LENGTH) { "event conformance failure detail is too long" }
    }

    public companion object {
        private val ID: Regex = Regex("^[a-z][a-z0-9.-]{0,127}$")
        private const val MAX_DETAIL_LENGTH: Int = 256
    }
}

/** Complete result of one reusable conformance run. A consumer may report non-certification without converting it to success. */
public data class EventConformanceReport(
    public val target: String,
    public val sections: List<EventConformanceSection>,
) {
    init {
        require(TARGET.matches(target)) { "event conformance target is not stable" }
        require(sections.isNotEmpty()) { "event conformance report has no sections" }
        require(sections.map(EventConformanceSection::id).distinct().size == sections.size) {
            "event conformance report repeats a section"
        }
    }

    /** Fails a test/build when this target did not pass every supported section. */
    public fun requireCertified(): Unit =
        check(sections.all { it.status == EventConformanceStatus.PASSED }) {
            "event conformance target $target is not certified: " +
                sections.filter { it.status != EventConformanceStatus.PASSED }.joinToString { "${it.id}=${it.status}" }
        }

    public companion object {
        private val TARGET: Regex = Regex("^[a-z][a-z0-9.-]{0,127}$")
    }
}

/** Supplies one implementation under test. The caller owns database lifecycle, transactions, and unique [runId]. */
public interface ProjectionCheckpointConformanceTarget {
    public val id: String

    public val origin: EventLogOrigin

    public fun checkpointStore(): ProjectionCheckpointStore
}

/**
 * Reusable core conformance for a fenced checkpoint store.
 *
 * The suite keeps no connection or transaction. Its target can therefore be the deterministic memory reference or a
 * PostgreSQL adapter already wired with an application's transaction authority. [runId] is part of every lane name so
 * one target database can retain prior evidence while multiple runs remain isolated.
 */
public class ProjectionCheckpointConformance(
    private val target: ProjectionCheckpointConformanceTarget,
    private val runId: String,
    private val clock: Clock,
) {
    init {
        require(RUN_ID.matches(runId)) { "projection checkpoint conformance run id is not stable" }
        require(TARGET_ID.matches(target.id)) { "projection checkpoint conformance target id is not stable" }
    }

    public fun verify(): EventConformanceReport =
        EventConformanceReport(
            target.id,
            listOf(
                verify(LEASE_EXCLUSIVITY, ::leaseExclusivity),
                verify(FENCED_ADVANCE, ::fencedAdvance),
                verify(FENCED_RELEASE, ::fencedRelease),
                verify(CONTRACT_DRIFT, ::contractDrift),
                verify(MONOTONIC_CURSOR, ::monotonicCursor),
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

    private fun leaseExclusivity() {
        val store = target.checkpointStore()
        val setup = setup(LEASE_EXCLUSIVITY)
        val first = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        check(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE) is ProjectionClaim.Busy) {
            "a live checkpoint lease did not exclude a second owner"
        }
        store.release(first.lease)
        val second = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        check(second.lease.fence > first.lease.fence) { "checkpoint reclaim did not advance its fence" }
    }

    private fun fencedAdvance() {
        val store = target.checkpointStore()
        val setup = setup(FENCED_ADVANCE)
        val first = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        store.release(first.lease)
        val second = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        val next = EventLogCursor.after(setup.initial, 1)
        check(store.advance(first.lease, next, now()) == ProjectionAdvance.LostLease) {
            "stale checkpoint lease advanced a cursor"
        }
        check(store.checkpoint(setup.lane)?.cursor == setup.initial) { "stale checkpoint lease changed durable cursor" }
        check(store.advance(second.lease, next, now()) is ProjectionAdvance.Advanced) { "current checkpoint lease could not advance" }
    }

    private fun fencedRelease() {
        val store = target.checkpointStore()
        val setup = setup(FENCED_RELEASE)
        val first = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        store.release(first.lease)
        val second = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        store.release(first.lease)
        check(store.advance(second.lease, EventLogCursor.after(setup.initial, 1), now()) is ProjectionAdvance.Advanced) {
            "stale checkpoint release cleared a newer lease"
        }
    }

    private fun contractDrift() {
        val store = target.checkpointStore()
        val setup = setup(CONTRACT_DRIFT)
        val claim = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        store.release(claim.lease)
        val drifted = setup.contract.copy(revision = ProjectionContractRevision(2))
        check(store.claim(setup.lane, drifted, setup.initial, now(), LEASE) == ProjectionClaim.ContractDrift) {
            "checkpoint resumed with a different replay contract"
        }
    }

    private fun monotonicCursor() {
        val store = target.checkpointStore()
        val setup = setup(MONOTONIC_CURSOR)
        val first = acquired(store.claim(setup.lane, setup.contract, setup.initial, now(), LEASE))
        val forward = EventLogCursor.after(setup.initial, 2)
        check(store.advance(first.lease, forward, now()) is ProjectionAdvance.Advanced) { "checkpoint could not advance forward" }
        store.release(first.lease)
        val second = acquired(store.claim(setup.lane, setup.contract, forward, now(), LEASE))
        val backward = runCatching { store.advance(second.lease, EventLogCursor.after(setup.initial, 1), now()) }
        check(backward.isFailure) { "checkpoint accepted a backward cursor" }
        check(store.checkpoint(setup.lane)?.cursor == forward) { "backward checkpoint request changed durable cursor" }
    }

    private fun setup(section: String): Setup {
        val lane =
            ProjectionLane(
                ProjectionName.of("conformance-$runId-$section"),
                ProjectionGeneration(1),
                ProjectionPartition.WHOLE,
            )
        val contract =
            ProjectionCheckpointContract(
                target.origin,
                ProjectionContractRevision(1),
                ProjectionTopologyFingerprint.of(ByteArray(ProjectionTopologyFingerprint.BYTES) { section.length.toByte() }),
            )
        return Setup(lane, contract, EventLogCursor.start(target.origin))
    }

    private fun acquired(claim: ProjectionClaim): ProjectionClaim.Acquired =
        claim as? ProjectionClaim.Acquired ?: error("checkpoint conformance expected an acquired lease, got ${claim::class.simpleName}")

    private fun now(): java.time.Instant = clock.instant()

    private data class Setup(
        val lane: ProjectionLane,
        val contract: ProjectionCheckpointContract,
        val initial: EventLogCursor,
    )

    private companion object {
        val TARGET_ID: Regex = Regex("^[a-z][a-z0-9.-]{0,127}$")
        val RUN_ID: Regex = Regex("^[a-z][a-z0-9-]{0,48}$")
        const val LEASE_EXCLUSIVITY: String = "projection.checkpoint.lease-exclusivity"
        const val FENCED_ADVANCE: String = "projection.checkpoint.fenced-advance"
        const val FENCED_RELEASE: String = "projection.checkpoint.fenced-release"
        const val CONTRACT_DRIFT: String = "projection.checkpoint.contract-drift"
        const val MONOTONIC_CURSOR: String = "projection.checkpoint.monotonic-cursor"
        val LEASE: Duration = Duration.ofMinutes(1)
        const val MAX_FAILURE_TYPE: Int = 256
    }
}
