package com.gd.rain.observability.health

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A clock the test moves by hand: a one-second freshness window is not tested by waiting a second. */
class MutableClock(
    @Volatile private var instant: Instant = Instant.parse("2026-09-12T09:00:00Z"),
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = instant

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

    fun advance(by: Duration) {
        instant = instant.plus(by)
    }
}
