package com.gd.rain.event.projection

/** Registration never overwrites a generation with different immutable replay semantics. */
public sealed interface ProjectionGenerationRegistration {
    public data class Created(
        public val generation: ProjectionGenerationRecord,
    ) : ProjectionGenerationRegistration

    public data class Repeated(
        public val generation: ProjectionGenerationRecord,
    ) : ProjectionGenerationRegistration

    public data object Collision : ProjectionGenerationRegistration
}

/** Readiness is derived from durable checkpoint watermarks, never supplied as a configuration boolean. */
public sealed interface ProjectionGenerationReadiness {
    public data class Ready(
        public val generation: ProjectionGenerationRecord,
    ) : ProjectionGenerationReadiness

    public data class Behind(
        public val missingLanes: Int,
        public val laggingLanes: Int,
    ) : ProjectionGenerationReadiness

    /** A rebuild cannot become READY while its own generation has an unresolved causal omission or lane stop. */
    public data class Blocked(
        public val holds: Int,
        public val unacknowledgedHoles: Int,
        public val haltedLanes: Int,
    ) : ProjectionGenerationReadiness {
        init {
            require(holds >= 0 && unacknowledgedHoles >= 0 && haltedLanes >= 0) { "projection readiness blockers are non-negative" }
            require(holds + unacknowledgedHoles + haltedLanes > 0) { "projection readiness blocker is empty" }
        }
    }

    public data object ContractDrift : ProjectionGenerationReadiness
}

/** CAS outcome for a generation cutover or bounded rollback to a retained generation. */
public sealed interface ProjectionCutover {
    public data class Activated(
        public val generation: ProjectionGenerationRecord,
    ) : ProjectionCutover

    public data object NotReady : ProjectionCutover

    public data class Blocked(
        public val holds: Int,
        public val unacknowledgedHoles: Int,
        public val haltedLanes: Int,
    ) : ProjectionCutover

    public data object Conflict : ProjectionCutover

    public data object ContractDrift : ProjectionCutover
}

/** Result of the transaction-bound active-generation effect gate. */
public enum class ProjectionEffectAdmission {
    ALLOWED,
    SUPPRESSED_BY_BARRIER,
    INACTIVE_GENERATION,
}

/**
 * Guards durable effect staging. Implementations lock the active-generation pointer in the same
 * unit as the staged effect write, preventing cutover from retiring a generation between check and
 * commit.
 */
public interface ProjectionEffectGate {
    public fun admitEffect(
        projection: ProjectionName,
        generation: ProjectionGeneration,
        position: Long,
    ): ProjectionEffectAdmission
}

/** Durable generation catalog. Implementations must prove checked-cover watermarks before READY. */
public interface ProjectionGenerationStore {
    public fun register(plan: ProjectionGenerationPlan): ProjectionGenerationRegistration

    public fun generation(
        projection: ProjectionName,
        generation: ProjectionGeneration,
    ): ProjectionGenerationRecord?

    /** Returns the durable active generation pointer, not a deployment property or inferred maximum id. */
    public fun active(projection: ProjectionName): ProjectionGenerationRecord?

    public fun markReady(
        plan: ProjectionGenerationPlan,
        cover: ProjectionCover,
    ): ProjectionGenerationReadiness

    /** [expectedActive] makes both forward cutover and rollback a closed compare-and-set command. */
    public fun cutover(
        projection: ProjectionName,
        expectedActive: ProjectionGeneration?,
        candidate: ProjectionGeneration,
    ): ProjectionCutover
}
