package com.gd.rain.event.projection

import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.EnqueueOutcome
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobPermanentException
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.WorkQueue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * JSON-safe durable request for one projection lane pass.
 *
 * [expectedCheckpoint] is a SHA-256 concurrency hint, not an authority token. A worker recomputes it before it
 * invokes a runner, so an old queued request cannot apply against an unobserved checkpoint revision. The checkpoint
 * lease remains the final concurrency fence.
 */
public data class ProjectionPassRequest(
    public val projection: String,
    public val generation: Long,
    public val partitionDepth: Int,
    public val partitionPrefix: Long,
    public val expectedCheckpoint: String?,
) {
    init {
        ProjectionName.of(projection)
        ProjectionGeneration(generation)
        ProjectionPartition(partitionDepth, partitionPrefix)
        require(expectedCheckpoint == null || CHECKPOINT_DIGEST.matches(expectedCheckpoint)) {
            "projection pass checkpoint digest is not a SHA-256 hex value"
        }
    }

    public fun lane(): ProjectionLane =
        ProjectionLane(
            ProjectionName.of(projection),
            ProjectionGeneration(generation),
            ProjectionPartition(partitionDepth, partitionPrefix),
        )

    /** Checks that a queued pass still describes this exact observed checkpoint, including its fence. */
    public fun matches(checkpoint: ProjectionCheckpoint?): Boolean = checkpoint?.lane == lane() && expectedCheckpoint == digest(checkpoint)

    public companion object {
        private val CHECKPOINT_DIGEST: Regex = Regex("^[0-9a-f]{64}$")

        public fun forLane(
            lane: ProjectionLane,
            checkpoint: ProjectionCheckpoint?,
        ): ProjectionPassRequest {
            require(checkpoint == null || checkpoint.lane == lane) { "projection pass checkpoint belongs to another lane" }
            return ProjectionPassRequest(
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                checkpoint?.let(::digest),
            )
        }

        private fun digest(checkpoint: ProjectionCheckpoint): String {
            val digest = MessageDigest.getInstance("SHA-256")
            bytes(digest, "rain.event.projection.pass.checkpoint.v1".toByteArray(StandardCharsets.UTF_8))
            bytes(
                digest,
                checkpoint.lane.projection
                    .text()
                    .toByteArray(StandardCharsets.UTF_8),
            )
            long(digest, checkpoint.lane.generation.value)
            integer(digest, checkpoint.lane.partition.depth)
            long(digest, checkpoint.lane.partition.prefix)
            bytes(
                digest,
                checkpoint.contract.origin.logId
                    .copy(),
            )
            integer(digest, checkpoint.contract.revision.value)
            bytes(digest, checkpoint.contract.topology.copy())
            long(digest, checkpoint.cursor.deliveredPosition)
            val bound = checkpoint.cursor.settlement.boundXid
            integer(digest, if (bound == null) -1 else 1)
            if (bound != null) bytes(digest, bound.toByteArray(StandardCharsets.UTF_8))
            long(digest, checkpoint.cursor.settlement.reach)
            long(digest, checkpoint.fence)
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun bytes(
            digest: MessageDigest,
            value: ByteArray,
        ) {
            integer(digest, value.size)
            digest.update(value)
        }

        private fun integer(
            digest: MessageDigest,
            value: Int,
        ) {
            digest.update((value ushr 24).toByte())
            digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte())
            digest.update(value.toByte())
        }

        private fun long(
            digest: MessageDigest,
            value: Long,
        ) {
            repeat(Long.SIZE_BYTES) { offset ->
                digest.update((value ushr ((Long.SIZE_BYTES - offset - 1) * Byte.SIZE_BITS)).toByte())
            }
        }
    }
}

/** The normalized outcome of one finite runner invocation for the durable jobs adapter. */
public sealed interface ProjectionPass {
    public data class Progress(
        public val checkpoint: ProjectionCheckpoint,
        public val hasMore: Boolean,
    ) : ProjectionPass

    public data class Idle(
        public val checkpoint: ProjectionCheckpoint,
    ) : ProjectionPass

    public data class Busy(
        public val retryAt: Instant,
    ) : ProjectionPass

    public data class Halted(
        public val halt: ProjectionHalt,
    ) : ProjectionPass

    public data object Retired : ProjectionPass

    public data object ContractDrift : ProjectionPass

    /** Another runner advanced after this worker observed the lane; schedule from the fresh checkpoint. */
    public data object Rebased : ProjectionPass

    /** A lease was lost after a handler attempt; the next pass must re-observe the fenced checkpoint. */
    public data object LostLease : ProjectionPass
}

/** Runs exactly one bounded source page and never retries a handler itself. */
public fun interface ProjectionPassRunner {
    public fun run(lane: ProjectionLane): ProjectionPass
}

/** One registered runner and its declared per-job bound. */
public data class ProjectionPassBinding(
    public val projection: ProjectionName,
    public val runner: ProjectionPassRunner,
    public val maxPages: Int,
) {
    init {
        require(maxPages in 1..ProjectionRunBudget.MAX_PAGES) { "projection pass max pages is outside the declared bound" }
    }

    public companion object {
        /** Couples the jobs worker bound to the same immutable declaration used by the runner. */
        public fun forSpec(
            spec: ProjectionSpec,
            runner: ProjectionPassRunner,
        ): ProjectionPassBinding = ProjectionPassBinding(spec.name, runner, spec.budget.maxPages)
    }
}

/** Explicit runner lookup; no projection handler is discovered from a job payload or reflection. */
public class ProjectionPassRunners(
    bindings: Set<ProjectionPassBinding>,
) {
    private val bindings: Map<ProjectionName, ProjectionPassBinding> = bindings.associateBy(ProjectionPassBinding::projection)

    init {
        require(bindings.isNotEmpty()) { "projection pass runners are empty" }
        require(this.bindings.size == bindings.size) { "projection pass runners duplicate a projection name" }
    }

    public fun binding(lane: ProjectionLane): ProjectionPassBinding? = bindings[lane.projection]
}

/** Worker policy that bounds job work and declares the rebuild log-read throttle. */
public data class ProjectionPassScheduling(
    public val priority: JobPriority,
    public val stepBudget: Duration,
    public val rebuildReadPace: Duration,
) {
    init {
        require(stepBudget.isPositive) { "projection pass step budget is positive" }
        require(rebuildReadPace.isPositive) { "projection rebuild read pace is positive" }
    }
}

/** Why a scheduler request did or did not create or coalesce one durable jobs invocation. */
public sealed interface ProjectionPassKick {
    public data class Queued(
        public val outcome: EnqueueOutcome,
    ) : ProjectionPassKick

    public data object GenerationMissing : ProjectionPassKick

    public data class NotRunnable(
        public val state: ProjectionGenerationState,
    ) : ProjectionPassKick
}

/**
 * One `rain-jobs` handler for bounded projection passes.
 *
 * Each runner call is bounded by [ProjectionPassScheduling.stepBudget]. Active generations may consume their
 * declared [ProjectionPassBinding.maxPages] in one jobs attempt; rebuilding generations always consume one source
 * page and queue any successor no sooner than their durable checkpoint's `updated_at + rebuildReadPace`.
 *
 * The lane key intentionally uses [Dedupe.Collapse]. A pass must create its successor before the current jobs
 * attempt reaches its terminal state; `Unique` would absorb that successor into the running invocation. Collapse
 * retains one queued kick but releases on claim, while the projection checkpoint lease remains the concurrency fence.
 */
public class ProjectionPassWorker(
    override val definition: JobDefinition<ProjectionPassRequest>,
    private val checkpoints: ProjectionCheckpointStore,
    private val generations: ProjectionGenerationStore,
    private val runners: ProjectionPassRunners,
    private val queue: WorkQueue,
    private val clock: Clock,
    private val scheduling: ProjectionPassScheduling,
) : JobHandler<ProjectionPassRequest> {
    /** Adds one durable pass request, or reports a generation that cannot run. Safe to call after a committed write. */
    public fun kick(lane: ProjectionLane): ProjectionPassKick = schedule(lane, Duration.ZERO)

    override fun handle(
        payload: ProjectionPassRequest,
        attempt: Attempt,
    ) {
        val lane = payload.lane()
        val generation = runnable(lane) ?: return
        val binding =
            runners.binding(lane)
                ?: throw JobPermanentException("projection pass job has no runner for ${lane.projection.text()}")
        if (!payload.matches(checkpoints.checkpoint(lane))) {
            schedule(lane, Duration.ZERO)
            return
        }
        val maxPages = if (generation.state == ProjectionGenerationState.BUILDING) 1 else binding.maxPages
        repeat(maxPages) { page ->
            when (val pass = attempt.step(scheduling.stepBudget) { binding.runner.run(lane) }) {
                is ProjectionPass.Progress -> {
                    if (pass.hasMore && page + 1 == maxPages) schedule(lane, Duration.ZERO)
                    if (!pass.hasMore || page + 1 == maxPages) return
                }

                is ProjectionPass.Idle,
                is ProjectionPass.Halted,
                ProjectionPass.Retired,
                ProjectionPass.ContractDrift,
                -> {
                    return
                }

                is ProjectionPass.Busy -> {
                    schedule(lane, retryDelay(pass.retryAt))
                }

                ProjectionPass.Rebased,
                ProjectionPass.LostLease,
                -> {
                    schedule(lane, Duration.ZERO)
                }
            }
        }
    }

    private fun schedule(
        lane: ProjectionLane,
        minimumDelay: Duration,
    ): ProjectionPassKick {
        val generation = generations.generation(lane.projection, lane.generation) ?: return ProjectionPassKick.GenerationMissing
        if (!generation.runnable()) return ProjectionPassKick.NotRunnable(generation.state)
        val checkpoint = checkpoints.checkpoint(lane)
        val delay = maxOf(minimumDelay, rebuildPace(generation, checkpoint))
        return ProjectionPassKick.Queued(
            queue.enqueue(
                definition,
                ProjectionPassRequest.forLane(lane, checkpoint),
                EnqueueOptions(
                    dedupe = Dedupe.Collapse(dedupeKey(lane)),
                    priority = scheduling.priority,
                    after = delay,
                    subjectKey = SubjectKey(dedupeKey(lane)),
                ),
            ),
        )
    }

    private fun runnable(lane: ProjectionLane): ProjectionGenerationRecord? =
        generations
            .generation(lane.projection, lane.generation)
            ?.takeIf { it.runnable() }

    private fun ProjectionGenerationRecord.runnable(): Boolean = state in RUNNABLE_STATES

    private fun rebuildPace(
        generation: ProjectionGenerationRecord,
        checkpoint: ProjectionCheckpoint?,
    ): Duration {
        if (generation.state != ProjectionGenerationState.BUILDING || checkpoint == null) return Duration.ZERO
        val nextReadAt = checkpoint.updatedAt.plus(scheduling.rebuildReadPace)
        return Duration.between(clock.instant(), nextReadAt).takeIf { it.isPositive } ?: Duration.ZERO
    }

    private fun retryDelay(retryAt: Instant): Duration =
        Duration.between(clock.instant(), retryAt).takeIf { it.isPositive } ?: Duration.ZERO

    private fun dedupeKey(lane: ProjectionLane): String =
        "projection-pass:${lane.projection.text()}:${lane.generation.value}:${lane.partition.canonical}"

    private companion object {
        val RUNNABLE_STATES: Set<ProjectionGenerationState> = setOf(ProjectionGenerationState.BUILDING, ProjectionGenerationState.ACTIVE)
    }
}

/** Supplies one bounded, ordered page of durable lanes for [ProjectionPassSweep]. */
public fun interface ProjectionPassLaneSource {
    /** Returns lanes strictly after [after], in stable lane order, and never more than [limit]. */
    public fun lanesAfter(
        after: ProjectionLane?,
        limit: Int,
    ): List<ProjectionLane>
}

/**
 * Reads current live members from durable topology rather than guessing lanes from a deployment's old cover.
 *
 * A generation without a registered topology intentionally contributes no lane: an explicit kick can still expose a
 * legacy configuration error, but the recurring recovery path must not recreate a partition from configuration.
 */
public class DurableProjectionPassLaneSource(
    private val catalogue: ProjectionCatalogue,
    private val generations: ProjectionGenerationStore,
    private val topologies: ProjectionTopologyStore,
    private val maxGenerationsPerProjection: Int = DEFAULT_MAX_GENERATIONS_PER_PROJECTION,
) : ProjectionPassLaneSource {
    init {
        require(maxGenerationsPerProjection in 1..MAX_GENERATIONS_PER_PROJECTION) {
            "projection pass source generation limit is outside 1..$MAX_GENERATIONS_PER_PROJECTION"
        }
    }

    override fun lanesAfter(
        after: ProjectionLane?,
        limit: Int,
    ): List<ProjectionLane> {
        require(limit in 1..ProjectionCover.MAX_MEMBERS) { "projection pass sweep limit is outside 1..${ProjectionCover.MAX_MEMBERS}" }
        return catalogue.definitions
            .asSequence()
            .map { it.spec.name }
            .sortedBy(ProjectionName::text)
            .flatMap { name ->
                generations
                    .runnable(name, maxGenerationsPerProjection)
                    .asSequence()
                    .flatMap { generation ->
                        topologies
                            .topology(name, generation.plan.generation)
                            ?.members
                            ?.asSequence()
                            ?.filter { it.state == ProjectionTopologyMemberState.LIVE }
                            ?.map { member -> ProjectionLane(name, generation.plan.generation, member.partition) }
                            .orEmpty()
                    }
            }.sortedWith(LANE_ORDER)
            .filter { after == null || LANE_ORDER.compare(it, after) > 0 }
            .take(limit)
            .toList()
    }

    private companion object {
        const val DEFAULT_MAX_GENERATIONS_PER_PROJECTION: Int = 16
        const val MAX_GENERATIONS_PER_PROJECTION: Int = 128

        val LANE_ORDER: Comparator<ProjectionLane> =
            compareBy(
                { it.projection.text() },
                { it.generation.value },
                { it.partition.depth },
                { it.partition.prefix },
            )
    }
}

/**
 * Cluster-owned recurring recovery for lost projection kicks.
 *
 * It enqueues only [maxLanes] lanes per run and retains a local keyset cursor. The cursor is merely a fairness aid:
 * each actual lane request is durable in `rain-jobs`, and a process restart begins another complete bounded walk.
 */
public class ProjectionPassSweep(
    override val name: String,
    override val interval: Duration,
    private val source: ProjectionPassLaneSource,
    private val worker: ProjectionPassWorker,
    private val maxLanes: Int,
) : RecurringWork {
    private var cursor: ProjectionLane? = null

    init {
        require(JobDefinition.NAME.matches(name)) { "projection pass sweep name is not stable" }
        require(interval.isPositive) { "projection pass sweep interval is positive" }
        require(
            maxLanes in 1..ProjectionCover.MAX_MEMBERS,
        ) { "projection pass sweep lane limit is outside 1..${ProjectionCover.MAX_MEMBERS}" }
    }

    override fun run() {
        synchronized(this) {
            var lanes = source.lanesAfter(cursor, maxLanes)
            if (lanes.isEmpty() && cursor != null) {
                cursor = null
                lanes = source.lanesAfter(null, maxLanes)
            }
            lanes.forEach { lane ->
                worker.kick(lane)
                cursor = lane
            }
        }
    }
}

/** Adapts the at-least-once runner to the jobs worker without changing its delivery semantics. */
public fun AfterApplyProjectionRunner.asPassRunner(): ProjectionPassRunner =
    ProjectionPassRunner { lane ->
        when (val pass = run(lane)) {
            is AfterApplyPass.Advanced -> ProjectionPass.Progress(pass.checkpoint, pass.hasMore)
            is AfterApplyPass.Idle -> ProjectionPass.Idle(pass.checkpoint)
            is AfterApplyPass.Busy -> ProjectionPass.Busy(pass.retryAt)
            is AfterApplyPass.Halted -> ProjectionPass.Halted(pass.halt)
            AfterApplyPass.Retired -> ProjectionPass.Retired
            AfterApplyPass.ContractDrift -> ProjectionPass.ContractDrift
            AfterApplyPass.LostLease -> ProjectionPass.LostLease
        }
    }

/** Adapts the caller-transaction SAME_UNIT runner to the jobs worker without a private retry loop. */
public fun SameUnitProjectionRunner.asPassRunner(): ProjectionPassRunner =
    ProjectionPassRunner { lane ->
        when (val pass = run(lane)) {
            is SameUnitPass.Advanced -> ProjectionPass.Progress(pass.checkpoint, pass.hasMore)
            is SameUnitPass.Parked -> ProjectionPass.Progress(pass.checkpoint, pass.hasMore)
            is SameUnitPass.Idle -> ProjectionPass.Idle(pass.checkpoint)
            is SameUnitPass.Busy -> ProjectionPass.Busy(pass.retryAt)
            is SameUnitPass.Halted -> ProjectionPass.Halted(pass.halt)
            SameUnitPass.Retired -> ProjectionPass.Retired
            SameUnitPass.ContractDrift -> ProjectionPass.ContractDrift
            SameUnitPass.Rebased -> ProjectionPass.Rebased
            SameUnitPass.LostLease -> ProjectionPass.LostLease
        }
    }
