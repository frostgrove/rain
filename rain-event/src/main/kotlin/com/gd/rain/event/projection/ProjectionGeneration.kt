package com.gd.rain.event.projection

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogOrigin
import java.time.Instant

/** Durable lifecycle of one side-by-side projection generation. */
public enum class ProjectionGenerationState {
    BUILDING,
    READY,
    ACTIVE,
    RETIRED,
    FAILED,
}

/** A terminal transition is intentionally absent: retired storage is cleaned up by a separate bounded admin operation. */
public object ProjectionGenerationTransitions {
    public fun allows(
        from: ProjectionGenerationState,
        to: ProjectionGenerationState,
    ): Boolean =
        when (from) {
            ProjectionGenerationState.BUILDING -> to in setOf(ProjectionGenerationState.READY, ProjectionGenerationState.FAILED)
            ProjectionGenerationState.READY -> to in setOf(ProjectionGenerationState.ACTIVE, ProjectionGenerationState.FAILED)
            ProjectionGenerationState.ACTIVE -> to == ProjectionGenerationState.RETIRED
            ProjectionGenerationState.RETIRED -> to == ProjectionGenerationState.ACTIVE
            ProjectionGenerationState.FAILED -> false
        }
}

/** Immutable barrier used to suppress external effects from historical replay. */
public data class ProjectionEffectBarrier(
    public val origin: EventLogOrigin,
    public val afterPosition: Long,
) {
    init {
        require(afterPosition >= 0) { "projection effect barrier position is not negative" }
    }

    public companion object {
        /** Effects may be staged only for records strictly after the captured global cursor. */
        public fun after(cursor: EventLogCursor): ProjectionEffectBarrier = ProjectionEffectBarrier(cursor.origin, cursor.deliveredPosition)
    }
}

/**
 * Immutable registration data for one rebuild generation.
 *
 * [source] records where rebuilding starts; [barrier] records the captured live-log point that a
 * complete checked cover must reach before cutover. For staged durable effects the same barrier is
 * persisted and can never be lowered by a later release or runtime setting.
 */
public class ProjectionGenerationPlan(
    public val projection: ProjectionName,
    public val generation: ProjectionGeneration,
    public val contract: ProjectionCheckpointContract,
    public val source: EventLogCursor,
    public val barrier: EventLogCursor,
    public val effectPolicy: ProjectionEffectPolicy,
    public val effectBarrier: ProjectionEffectBarrier?,
) {
    init {
        require(source.origin == contract.origin && barrier.origin == contract.origin) {
            "projection generation cursors belong to another event log"
        }
        require(source.deliveredPosition <= barrier.deliveredPosition) {
            "projection generation source is after its barrier"
        }
        when (effectPolicy) {
            ProjectionEffectPolicy.DISABLED -> {
                require(effectBarrier == null) { "effects-disabled generation has an effect barrier" }
            }

            ProjectionEffectPolicy.STAGED_DURABLE -> {
                requireNotNull(effectBarrier) { "staged-effect generation has no effect barrier" }
                require(effectBarrier.origin == contract.origin) { "projection effect barrier belongs to another event log" }
                require(effectBarrier.afterPosition == barrier.deliveredPosition) {
                    "projection effect barrier must equal the immutable rebuild barrier"
                }
            }
        }
    }

    public companion object {
        public fun create(
            projection: ProjectionName,
            generation: ProjectionGeneration,
            contract: ProjectionCheckpointContract,
            source: EventLogCursor,
            barrier: EventLogCursor,
            effectPolicy: ProjectionEffectPolicy,
        ): ProjectionGenerationPlan =
            ProjectionGenerationPlan(
                projection,
                generation,
                contract,
                source,
                barrier,
                effectPolicy,
                if (effectPolicy == ProjectionEffectPolicy.STAGED_DURABLE) ProjectionEffectBarrier.after(barrier) else null,
            )
    }
}

/** Durable read model for a registered generation; all replay-critical values are immutable after registration. */
public data class ProjectionGenerationRecord(
    public val plan: ProjectionGenerationPlan,
    public val state: ProjectionGenerationState,
    public val createdAt: Instant,
    public val cutoverAt: Instant? = null,
    public val retiredAt: Instant? = null,
) {
    init {
        require(!(state == ProjectionGenerationState.ACTIVE && cutoverAt == null)) { "active projection generation has no cutover time" }
        require(
            !(state == ProjectionGenerationState.RETIRED && retiredAt == null),
        ) { "retired projection generation has no retirement time" }
        require(
            !(state != ProjectionGenerationState.RETIRED && retiredAt != null),
        ) { "non-retired projection generation has retirement time" }
    }
}
