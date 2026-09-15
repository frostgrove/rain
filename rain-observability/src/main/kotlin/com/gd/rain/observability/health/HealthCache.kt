package com.gd.rain.observability.health

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One shared evaluation per freshness window.
 *
 * A readiness endpoint is scraped by every probe, load balancer and dashboard a deployment has; one
 * evaluation per scrape turns a health page into load on the dependency it asks about. Two rules:
 * - a reading younger than [freshness] is returned as it is;
 * - an evaluation already in flight is joined, not duplicated (single flight). A waiter that is
 *   interrupted stops waiting and leaves the flight alone; the flight itself is bounded by the
 *   per-check budgets of the evaluation it runs.
 *
 * An evaluation that threw is not a reading: every waiter of that flight receives the failure and the
 * next caller evaluates again.
 */
public class HealthCache<T : Any>(
    private val freshness: Duration,
    private val clock: Clock,
    private val evaluate: () -> T,
) {
    init {
        require(!freshness.isZero && !freshness.isNegative) { "a freshness window is positive, got $freshness" }
    }

    private val lock = ReentrantLock()
    private var last: Reading<T>? = null
    private var running: Flight<T>? = null

    public fun get(): T {
        val claim =
            lock.withLock {
                last?.takeIf { it.isFresh() }?.let { return it.value }
                running?.let { Claim.Join(it) } ?: Claim.Run(Flight<T>().also { running = it })
            }
        return when (claim) {
            is Claim.Join -> claim.flight.await()
            is Claim.Run -> fly(claim.flight)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fly(flight: Flight<T>): T {
        val outcome =
            try {
                Result.success(evaluate())
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        val observedAt = clock.instant()
        lock.withLock {
            running = null
            outcome.onSuccess { last = Reading(it, observedAt) }
        }
        flight.settle(outcome)
        return outcome.getOrThrow()
    }

    private fun Reading<T>.isFresh(): Boolean = Duration.between(observedAt, clock.instant()) < freshness

    private data class Reading<T : Any>(
        val value: T,
        val observedAt: Instant,
    )

    private sealed interface Claim<T : Any> {
        data class Join<T : Any>(
            val flight: Flight<T>,
        ) : Claim<T>

        data class Run<T : Any>(
            val flight: Flight<T>,
        ) : Claim<T>
    }

    private class Flight<T : Any> {
        private val done = CountDownLatch(1)

        @Volatile
        private var outcome: Result<T>? = null

        fun settle(settled: Result<T>) {
            outcome = settled
            done.countDown()
        }

        fun await(): T {
            done.await()
            return checkNotNull(outcome) { "a settled flight has an outcome" }.getOrThrow()
        }
    }
}
