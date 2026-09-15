package com.gd.rain.resilience

import com.gd.rain.observability.health.Importance
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

val START: Instant = Instant.parse("2026-09-15T12:00:00Z")
val OPEN_WAIT: Duration = Duration.ofMinutes(1)

/** Just past the open wait: Resilience4j admits strictly after the wait has run out. */
val RESTED: Duration = OPEN_WAIT.plusMillis(1)

/** "[failures] failures in a row open it": a count window of that size at a 100% failure rate. */
fun breakerConfig(
    clock: Clock,
    failures: Int = 5,
    halfOpenPermits: Int = 1,
): CircuitBreakerConfig =
    CircuitBreakerConfig
        .custom()
        .slidingWindow(failures, failures, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .failureRateThreshold(100f)
        .waitDurationInOpenState(OPEN_WAIT)
        .permittedNumberOfCallsInHalfOpenState(halfOpenPermits)
        .clock(clock)
        .build()

fun declaration(name: String): BreakerDeclaration = BreakerDeclaration(BreakerName(name), name, Importance.DEGRADING)

fun containerWith(
    config: CircuitBreakerConfig,
    vararg names: String,
): CircuitBreakerRegistry = CircuitBreakerRegistry.of(config).also { registry -> names.forEach { registry.circuitBreaker(it) } }

fun BreakerRegistry.admitted(name: BreakerName): Reservation.Admitted {
    val reservation = reserve(name)
    check(reservation is Reservation.Admitted) { "expected $name to admit, got $reservation" }
    return reservation
}

fun BreakerRegistry.fail(
    name: BreakerName,
    times: Int,
    message: String = "connection refused",
) {
    repeat(times) { admitted(name).use { failed(it, IllegalStateException(message)) } }
}

/** A breaker that parks one chosen thread right after it reads HALF_OPEN, until the test resumes it. */
class PausingBreaker(
    private val delegate: CircuitBreaker,
) : CircuitBreaker by delegate {
    @Volatile
    private var target: Thread? = null

    val reached = CountDownLatch(1)
    private val resume = CountDownLatch(1)

    fun pauseNextHalfOpenReadOf(thread: Thread) {
        target = thread
    }

    fun resume() {
        resume.countDown()
    }

    override fun getState(): CircuitBreaker.State {
        val state = delegate.state
        if (state == CircuitBreaker.State.HALF_OPEN && Thread.currentThread() === target) {
            target = null
            reached.countDown()
            check(resume.await(5, TimeUnit.SECONDS)) { "the paused thread was never resumed" }
        }
        return state
    }
}
