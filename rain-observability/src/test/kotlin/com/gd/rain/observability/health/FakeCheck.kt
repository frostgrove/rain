package com.gd.rain.observability.health

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** A contribution whose answer the test decides, and which counts how often it was asked. */
class FakeCheck(
    override val name: String,
    override val code: String? = name,
    override val importance: Importance = Importance.REQUIRED,
    override val timeout: Duration? = null,
    private val answer: () -> Unit = {},
) : HealthContribution {
    val asked: AtomicInteger = AtomicInteger()

    override fun probe() {
        asked.incrementAndGet()
        answer()
    }
}

fun failing(
    name: String,
    importance: Importance = Importance.REQUIRED,
    code: String? = name,
    message: String = "connection refused",
): FakeCheck = FakeCheck(name, code, importance) { throw IllegalStateException(message) }

fun passing(
    name: String,
    importance: Importance = Importance.REQUIRED,
    code: String? = name,
): FakeCheck = FakeCheck(name, code, importance)

val CHECK_TIMEOUT: Duration = HealthProperties.DEFAULT_CHECK_TIMEOUT
val FRESHNESS: Duration = HealthProperties.DEFAULT_FRESHNESS

fun registryOf(
    vararg checks: HealthContribution,
    clock: java.time.Clock = MutableClock(),
): HealthRegistry = HealthRegistry(checks.toList(), CHECK_TIMEOUT, FRESHNESS, clock)
