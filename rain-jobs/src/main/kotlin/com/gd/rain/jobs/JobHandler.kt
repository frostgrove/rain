package com.gd.rain.jobs

import com.gd.rain.core.lock.Guard
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The implementation of one [definition]. Exactly one handler bean answers for every declared definition.
 *
 * [handle] runs on a virtual thread of its own. It returns to succeed, throws [JobDeferredException] for "not
 * now", [JobPermanentException] for a refusal retrying cannot fix, and anything else for a charged retry.
 * Handlers are idempotent: an attempt whose effect committed and whose completion did not runs again.
 */
public interface JobHandler<P : Any> {
    public val definition: JobDefinition<P>

    public fun handle(
        payload: P,
        attempt: Attempt,
    )
}

/** What a handler is told about the attempt it runs inside. */
public data class AttemptMeta(
    public val invocation: UUID,
    public val definition: String,
    public val profile: String,
    /** 1 for the first attempt. */
    public val attempt: Int,
    public val retrySpent: Int,
    public val retryLimit: Int,
    public val deferrals: Int,
    /** The instant past which this attempt is a charged timeout. */
    public val deadline: Instant,
    public val subjectKey: SubjectKey?,
) {
    /** On the last charged attempt a failure is a dead invocation, so a handler may prefer to record a partial result. */
    public val lastChargedAttempt: Boolean get() = retrySpent >= retryLimit
}

/** The attempt a handler runs inside: bounded steps and fenced effects, both over this attempt's lease. */
public interface Attempt {
    public val meta: AttemptMeta

    /**
     * Runs [body] with every transaction it opens bounded by `min(budget, deadline − now)`. Throws
     * [LeaseLostException] or [AttemptInterruptedException] instead of running when the attempt has been revoked,
     * and [AttemptTimeoutException] when nothing is left of the attempt.
     */
    public fun <T> step(
        budget: Duration,
        body: () -> T,
    ): T

    /**
     * Runs [effect] in a new transaction that holds [guards], then verifies this attempt still owns the invocation
     * (token, state and unexpired lease, under a row lock), and only then runs the effect — so an effect never
     * commits under a lease another attempt holds. Its statements are bounded by the current statement bound.
     */
    public fun <T> fenced(
        guards: List<Guard>,
        effect: () -> T,
    ): T
}

/** "Not now": no retry is charged; the profile's deferral budget bounds how often. */
public class JobDeferredException(
    public val after: Duration,
    message: String? = null,
) : RuntimeException(message ?: "deferred for $after") {
    init {
        require(after.isPositive) { "a deferral is positive, got $after" }
    }
}

/** A refusal retrying cannot fix: the invocation is failed with no budget spent. */
public class JobPermanentException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** This attempt no longer owns the invocation. Nothing is charged and nothing is written by it. */
public class LeaseLostException(
    public val invocation: UUID,
) : RuntimeException("the lease on invocation $invocation is no longer held by this attempt")

/** The process is stopping: the attempt is released uncharged and the work is due again at once. */
public class AttemptInterruptedException(
    public val invocation: UUID,
) : RuntimeException("the attempt on invocation $invocation was interrupted by shutdown")

/** Nothing is left of the attempt's budget. A charged retry. */
public class AttemptTimeoutException(
    public val budget: Duration,
) : RuntimeException("the attempt did not finish within $budget")

/**
 * Recurring work owned by the cluster rather than by a process: one db-scheduler recurring task, so exactly one
 * worker runs it per [interval]. Its [name] shares the task-name space with job definitions.
 */
public interface RecurringWork {
    public val name: String
    public val interval: Duration

    public fun run()
}
