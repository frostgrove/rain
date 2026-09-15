package com.gd.rain.test

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock a test moves by hand, so time-dependent behaviour is asserted without waiting. */
public class MutableClock(
    @Volatile private var now: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    public fun advance(by: Duration) {
        require(!by.isNegative) { "a clock only moves forward, got $by" }
        now = now.plus(by)
    }

    public fun set(to: Instant) {
        require(!to.isBefore(now)) { "a clock only moves forward, from $now to $to" }
        now = to
    }
}
