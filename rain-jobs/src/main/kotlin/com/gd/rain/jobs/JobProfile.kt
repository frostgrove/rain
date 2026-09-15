package com.gd.rain.jobs

import java.time.Duration

/**
 * A service class work runs in, declared by the application as a bean. rain ships none.
 *
 * Each declared profile gets one db-scheduler `Scheduler` whose thread count is the sum of its definitions'
 * worker ceilings (`rain.jobs.workers.<definition>`).
 *
 * @property attemptTimeout the wall-clock budget of one attempt; past it the attempt is a charged retry.
 * @property stepTimeout the statement bound of work an attempt does outside an explicit step, and the upper
 *   bound of a fenced effect's statements; always further capped by what is left of the attempt.
 * @property backoff the ladder a charged retry waits on.
 * @property retries charged retries after the first attempt; an invocation has `retries + 1` attempts.
 * @property deferrals how often a handler may say "not now" before the invocation is dead.
 * @property retention how long terminal invocations and released reservations of this profile are kept.
 */
public data class JobProfile(
    public val id: String,
    public val attemptTimeout: Duration,
    public val stepTimeout: Duration,
    public val backoff: BackoffLadder,
    public val retries: Int,
    public val deferrals: Int,
    public val retention: Duration,
) {
    init {
        require(ID.matches(id)) { "a job profile id matches ${ID.pattern}, got \"$id\"" }
        require(attemptTimeout.isPositive) { "profile $id: the attempt timeout is positive, got $attemptTimeout" }
        require(stepTimeout.isPositive) { "profile $id: the step timeout is positive, got $stepTimeout" }
        require(stepTimeout <= attemptTimeout) {
            "profile $id: the step timeout $stepTimeout exceeds the attempt timeout $attemptTimeout it runs inside"
        }
        require(retries >= 0) { "profile $id: retries are not negative, got $retries" }
        require(deferrals >= 0) { "profile $id: deferrals are not negative, got $deferrals" }
        require(retention.isPositive) { "profile $id: the retention is positive, got $retention" }
    }

    public companion object {
        public val ID: Regex = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}

/**
 * Exponential backoff with full jitter: before retry `spent + 1` the ceiling is `initial · 2^spent` capped at
 * [maximum], and the delay is drawn uniformly from `[initial, ceiling]`. The floor is the ladder's own first
 * rung, so a charged retry never comes back sooner than the profile declared.
 */
public data class BackoffLadder(
    public val initial: Duration,
    public val maximum: Duration,
) {
    init {
        require(initial.isPositive) { "a backoff starts positive, got $initial" }
        require(maximum >= initial) { "a backoff maximum $maximum is not below its initial $initial" }
    }

    public fun ceiling(spent: Int): Duration {
        require(spent >= 0) { "spent retries are not negative, got $spent" }
        val initialMillis = initial.toMillis()
        val maximumMillis = maximum.toMillis()
        var ceiling = initialMillis
        repeat(minOf(spent, Long.SIZE_BITS)) {
            if (ceiling >= maximumMillis) return maximum
            ceiling = if (ceiling > maximumMillis / 2) maximumMillis else ceiling * 2
        }
        return Duration.ofMillis(minOf(ceiling, maximumMillis))
    }

    /** [jitter] answers a value in `[0, bound)`. */
    public fun delay(
        spent: Int,
        jitter: Jitter,
    ): Duration {
        val floor = initial.toMillis()
        val width = ceiling(spent).toMillis() - floor + 1
        val drawn = jitter.draw(width)
        check(drawn in 0 until width) { "jitter drew $drawn outside [0, $width)" }
        return Duration.ofMillis(floor + drawn)
    }
}

/** A uniform draw in `[0, bound)`; injected so a test decides the draw. */
public fun interface Jitter {
    public fun draw(bound: Long): Long
}
