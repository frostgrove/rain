package com.gd.rain.event.projection

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Bounded result of waiting for an asynchronous view to durably pass a global event-log mark. */
public sealed interface ProjectionWaitOutcome {
    public data class Reached(
        public val generation: ProjectionGeneration,
    ) : ProjectionWaitOutcome

    public data object TimedOut : ProjectionWaitOutcome

    public data object GenerationUnavailable : ProjectionWaitOutcome

    public data object ContractDrift : ProjectionWaitOutcome
}

/** Handle for one wait; a lost realtime hint is harmless because scheduled durable polling remains active. */
public class ProjectionWaitHandle internal constructor(
    public val completion: CompletionStage<ProjectionWaitOutcome>,
    private val wake: () -> Unit,
) {
    /** Requests an immediate durable recheck, for example after an optional realtime notification. */
    public fun recheck(): Unit = wake()
}

/**
 * Non-blocking bounded read-your-writes waiter.
 *
 * Each check performs only durable catalog/checkpoint reads. It never holds a transaction,
 * connection, or worker lease while waiting. The caller owns the scheduler and may pair it with a
 * realtime wake-up by invoking [recheck]; polling remains the correctness source when a hint drops.
 */
public class ProjectionWaiter(
    private val generations: ProjectionGenerationStore,
    private val checkpoints: ProjectionCheckpointStore,
    private val clock: Clock,
    private val scheduler: ScheduledExecutorService,
    private val pollInterval: Duration,
    private val maximumWait: Duration,
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero) { "projection waiter poll interval is positive" }
        require(!maximumWait.isNegative && !maximumWait.isZero) { "projection waiter maximum deadline is positive" }
    }

    /** Schedules checks until the durable full-cover watermark reaches [mark] or [deadline] expires. */
    public fun await(
        projection: ProjectionName,
        cover: ProjectionCover,
        mark: ProjectionMark,
        deadline: Instant,
    ): ProjectionWaitHandle {
        val now = clock.instant()
        require(deadline.isAfter(now)) { "projection waiter deadline is in the past" }
        require(!deadline.isAfter(now.plus(maximumWait))) { "projection waiter deadline exceeds $maximumWait" }
        val pending = CompletableFuture<ProjectionWaitOutcome>()
        val poller = Poller(projection, cover, mark, deadline, pending)
        poller.recheck()
        return ProjectionWaitHandle(pending, poller::recheck)
    }

    private inner class Poller(
        private val projection: ProjectionName,
        private val cover: ProjectionCover,
        private val mark: ProjectionMark,
        private val deadline: Instant,
        private val result: CompletableFuture<ProjectionWaitOutcome>,
    ) {
        fun recheck() {
            if (result.isDone) return
            try {
                val outcome = check()
                if (outcome != null) {
                    result.complete(outcome)
                } else {
                    val remaining = Duration.between(clock.instant(), deadline)
                    if (remaining.isNegative || remaining.isZero) {
                        result.complete(ProjectionWaitOutcome.TimedOut)
                    } else {
                        scheduler.schedule(::recheck, minOf(remaining, pollInterval).toNanos(), TimeUnit.NANOSECONDS)
                    }
                }
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            }
        }

        private fun check(): ProjectionWaitOutcome? {
            val active = generations.active(projection) ?: return ProjectionWaitOutcome.GenerationUnavailable
            if (active.plan.contract.origin != mark.origin) return ProjectionWaitOutcome.GenerationUnavailable
            if (active.plan.contract.topology != cover.fingerprint) return ProjectionWaitOutcome.ContractDrift
            val reached =
                cover.members.all { partition ->
                    val checkpoint = checkpoints.checkpoint(ProjectionLane(projection, active.plan.generation, partition))
                    checkpoint != null &&
                        checkpoint.contract == active.plan.contract &&
                        checkpoint.cursor.deliveredPosition >= mark.position
                }
            return if (reached) ProjectionWaitOutcome.Reached(active.plan.generation) else null
        }
    }
}
