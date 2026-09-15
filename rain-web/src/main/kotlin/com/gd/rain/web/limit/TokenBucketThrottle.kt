package com.gd.rain.web.limit

import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.min

/**
 * A continuously refilling token bucket per caller over a bounded [CallerTable].
 *
 * `perMinute` tokens flow back continuously (half a second at 120 a minute buys one token), up to
 * `burst`; each admitted request spends one. A fixed-window permit counter cannot express this, which
 * is why the bucket is written out here. The caller key is the caller's choice of identity — an
 * address, an account identifier — and is decided by whoever mounts the throttle.
 *
 * The clock is a monotonic nanosecond source, injected so time-driven behaviour is tested by moving it.
 */
public class TokenBucketThrottle(
    perMinute: Int,
    burst: Int,
    callers: Int,
    private val clock: () -> Long = System::nanoTime,
) {
    init {
        require(perMinute >= 0) { "a throttle does not refill a negative number of tokens, got $perMinute" }
        require(burst > 0) { "a burst of $burst refuses every caller including the first" }
    }

    private val perSecond: Double = perMinute.toDouble() / SECONDS_PER_MINUTE
    private val burstTokens: Double = burst.toDouble()
    private val table = CallerTable(callers)
    private val lock = ReentrantLock()

    /** How many callers the table currently remembers. */
    public val tracked: Int get() = lock.withLock { table.size }

    /** How many new callers were refused because the table was full. */
    public val refusedBecauseFull: Long get() = lock.withLock { table.refusedBecauseFull }

    /** Spends one token of [caller]'s bucket; false when the bucket is empty or the caller finds the table full. */
    public fun allow(caller: String): Boolean {
        val now = clock()
        return lock.withLock {
            val spending = table[caller] ?: table.admit(caller, burstTokens, now) ?: return@withLock false
            spending.tokens = min(burstTokens, refilled(spending, now))
            spending.at = now
            val allowed = spending.tokens >= 1
            if (allowed) spending.tokens -= 1
            table.reschedule(spending, refillsAt(spending))
            allowed
        }
    }

    /** The gap between two tokens, or null for a throttle that never refills. */
    public fun interval(): Duration? = if (perSecond <= 0) null else Duration.ofNanos((NANOS_PER_SECOND / perSecond).toLong())

    /**
     * What a refused caller is told to wait, in whole seconds rounded up — the same rounding the problem
     * renderer applies to `Retry-After`. Null for a throttle that never refills: there is no honest
     * number to send, so no header is sent.
     */
    public fun retryAfterSeconds(): Long? = if (perSecond <= 0) null else ceil(1.0 / perSecond).toLong()

    private fun refilled(
        spending: CallerTable.Allowance,
        now: Long,
    ): Double = spending.tokens + (now - spending.at) / NANOS_PER_SECOND * perSecond

    /** When this bucket is full again; one that owes nothing is full now, one that never refills is ordered last. */
    private fun refillsAt(spending: CallerTable.Allowance): Long {
        val owed = burstTokens - spending.tokens
        return when {
            owed <= 0 -> spending.at
            perSecond <= 0 -> Long.MAX_VALUE
            else -> spending.at + (owed / perSecond * NANOS_PER_SECOND).toLong()
        }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60.0
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
