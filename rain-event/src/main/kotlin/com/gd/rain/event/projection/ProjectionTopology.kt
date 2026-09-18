package com.gd.rain.event.projection

import com.gd.rain.event.EventLogCursor
import java.time.Instant

/** Immutable declaration used to register and evolve one generation's checked partition cover. */
public data class ProjectionTopologyDeclaration(
    public val projection: ProjectionName,
    public val generation: ProjectionGeneration,
    public val contract: ProjectionCheckpointContract,
    public val cover: ProjectionCover,
) {
    init {
        require(contract.topology == cover.fingerprint) {
            "projection topology declaration fingerprint does not match its checked cover"
        }
    }
}

/** Durable membership state; a retired parent is retained as lineage but can never claim a checkpoint again. */
public enum class ProjectionTopologyMemberState {
    LIVE,
    RETIRED,
}

/** Bounded, payload-free status for one partition in a durable topology. */
public data class ProjectionTopologyMember(
    public val partition: ProjectionPartition,
    public val state: ProjectionTopologyMemberState,
)

/** Durable, checked partition cover and its stable hasher identity. */
public data class ProjectionTopology(
    public val projection: ProjectionName,
    public val generation: ProjectionGeneration,
    public val contract: ProjectionCheckpointContract,
    public val hasher: SequenceKeyHasherId,
    public val members: List<ProjectionTopologyMember>,
    public val updatedAt: Instant,
) {
    init {
        require(members.isNotEmpty()) { "projection topology has no members" }
        require(members.map(ProjectionTopologyMember::partition).distinct().size == members.size) {
            "projection topology has duplicate members"
        }
    }
}

/** Registration never replaces a persisted cover or hasher identity with deployment configuration. */
public sealed interface ProjectionTopologyRegistration {
    public data class Created(
        public val topology: ProjectionTopology,
    ) : ProjectionTopologyRegistration

    public data class Repeated(
        public val topology: ProjectionTopology,
    ) : ProjectionTopologyRegistration

    public data object Conflict : ProjectionTopologyRegistration
}

/** Immutable evidence of one parent-to-children split, including the exact inherited global cursor. */
public data class ProjectionTopologyRetirement(
    public val projection: ProjectionName,
    public val generation: ProjectionGeneration,
    public val parent: ProjectionPartition,
    public val firstChild: ProjectionPartition,
    public val secondChild: ProjectionPartition,
    public val inheritedCursor: EventLogCursor,
    public val parentFence: Long,
    public val retiredAt: Instant,
) {
    init {
        val children = parent.split()
        require(setOf(firstChild, secondChild) == setOf(children.first, children.second)) {
            "projection topology retirement children do not exactly split their parent"
        }
        require(parentFence > 0) { "projection topology retirement parent fence is positive" }
    }
}

/** A split refuses unsafe source state instead of relocating a causal hold/hole/halt by guesswork. */
public data class ProjectionTopologyBlockers(
    public val holds: Int,
    public val holes: Int,
    public val halts: Int,
) {
    init {
        require(holds >= 0 && holes >= 0 && halts >= 0) { "projection topology blockers are non-negative" }
        require(holds + holes + halts > 0) { "projection topology blockers are empty" }
    }
}

/** Result of the one-way, transaction-bound parent split command. */
public sealed interface ProjectionTopologySplit {
    public data class Split(
        public val topology: ProjectionTopology,
        public val retirement: ProjectionTopologyRetirement,
    ) : ProjectionTopologySplit

    /** A live parent checkpoint lease owns the lane; retry only after the reported durable expiry. */
    public data class Busy(
        public val retryAt: Instant,
    ) : ProjectionTopologySplit

    public data class Blocked(
        public val blockers: ProjectionTopologyBlockers,
    ) : ProjectionTopologySplit

    public data object ParentMissing : ProjectionTopologySplit

    public data object ContractDrift : ProjectionTopologySplit
}

/**
 * Durable exact-cover registry and one-way split command.
 *
 * Mutations must join the caller's existing control/checkpoint transaction; adapters never open or retry one. The
 * command writes child checkpoints, parent retirement lineage, topology fingerprint and generation fingerprint as one
 * unit, so no committed state lets a sequence disappear or run in both parent and child lanes.
 */
public interface ProjectionTopologyStore {
    public fun register(declaration: ProjectionTopologyDeclaration): ProjectionTopologyRegistration

    /** Returns status only; it acquires neither a worker lease nor an administrative lock. */
    public fun topology(
        projection: ProjectionName,
        generation: ProjectionGeneration,
    ): ProjectionTopology?

    /** Splits [parent] from [declaration.cover] into its two exact children in the caller-owned transaction. */
    public fun split(
        declaration: ProjectionTopologyDeclaration,
        parent: ProjectionPartition,
    ): ProjectionTopologySplit
}
