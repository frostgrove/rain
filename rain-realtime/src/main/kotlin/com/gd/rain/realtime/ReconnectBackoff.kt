package com.gd.rain.realtime

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The reconnect ladder between the declared `min-backoff` and `max-backoff`.
 *
 * The wait after a session that connected is [min]; each further failure to connect doubles it, never
 * beyond [max]. The ladder resets on a connection, not when the listening loop returns, so a drop
 * after an hour of health costs [min] rather than the ceiling.
 */
public class ReconnectBackoff(
    public val min: Duration,
    public val max: Duration,
) {
    init {
        require(!min.isNegative && !min.isZero) { "realtime: min-backoff has to be positive, got $min" }
        require(max >= min) { "realtime: max-backoff $max is below min-backoff $min" }
    }

    /** The wait after [previous] when the attempt it preceded failed as well. */
    public fun next(previous: Duration): Duration = if (previous > max.dividedBy(2)) max else previous.multipliedBy(2)
}

/** How the listener waits between sessions. Injected so the ladder is tested without waiting. */
internal fun interface ReconnectWait {
    /** Waits [duration] unless [stop] opens first; answers whether to try again. */
    fun await(
        duration: Duration,
        stop: CountDownLatch,
    ): Boolean

    companion object {
        val UNTIL_STOPPED: ReconnectWait = ReconnectWait { duration, stop -> !stop.await(duration.toNanos(), TimeUnit.NANOSECONDS) }
    }
}
