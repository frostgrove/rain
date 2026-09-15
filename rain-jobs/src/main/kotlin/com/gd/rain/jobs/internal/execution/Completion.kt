package com.gd.rain.jobs.internal.execution

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.ledger.InvocationSnapshot
import com.github.kagkarlsson.scheduler.task.CompletionHandler
import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import java.time.Duration
import java.time.Instant

/** What happens to the db-scheduler execution after an attempt, a refused claim or a refused write. */
internal sealed interface Completion {
    data object Remove : Completion

    data class Reschedule(
        val at: Instant,
    ) : Completion

    fun handler(): CompletionHandler<JobTaskData> =
        when (this) {
            Remove -> CompletionHandler.OnCompleteRemove()
            is Reschedule -> CompletionHandler.OnCompleteReschedule(AtInstant(at))
        }

    companion object {
        /**
         * The execution of [generation] was refused, or its attempt's write was, and [current] is where the invocation
         * is now. The mapping is total:
         *
         * - no invocation, another generation, or a terminal state: nothing is left for this execution — remove it;
         * - queued: this execution delivers it when it is eligible;
         * - running under an unexpired lease: look again when that lease would expire;
         * - running under a lapsed lease: the reaper returns it to the queue within one reaper interval; look again then.
         */
        fun afterRefusal(
            current: InvocationSnapshot?,
            generation: Int,
            now: Instant,
            reaperInterval: Duration,
        ): Completion {
            if (current == null || current.generation != generation || current.state.terminal) return Remove
            return when (current.state) {
                JobState.QUEUED -> {
                    Reschedule(current.eligibleAt)
                }

                JobState.RUNNING -> {
                    val expires = checkNotNull(current.leaseExpiresAt) { "a running invocation ${current.id} has no lease" }
                    if (expires.isAfter(now)) Reschedule(expires) else Reschedule(now.plus(reaperInterval))
                }

                else -> {
                    error("unreachable: ${current.state} is terminal")
                }
            }
        }
    }
}

/** A schedule that answers one instant: the execution is due exactly then. */
internal class AtInstant(
    private val at: Instant,
) : Schedule {
    override fun getNextExecutionTime(executionComplete: ExecutionComplete): Instant = at

    override fun isDeterministic(): Boolean = true
}
