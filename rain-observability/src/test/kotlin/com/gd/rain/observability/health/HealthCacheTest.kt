package com.gd.rain.observability.health

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** The freshness window and the shared flight: scrapes inside one window cost one evaluation. */
class HealthCacheTest {
    private val clock = MutableClock()
    private val passes = AtomicInteger()

    @Test
    fun `a second look inside the freshness window is served from the cache`() {
        val cache = cache()

        assertThat(cache.get()).isEqualTo(1)
        clock.advance(FRESHNESS.minusMillis(1))
        assertThat(cache.get()).isEqualTo(1)
        assertThat(passes).hasValue(1)
    }

    @Test
    fun `the window expires at the freshness boundary and not a millisecond later`() {
        val cache = cache()
        cache.get()

        clock.advance(FRESHNESS)

        assertThat(cache.get()).isEqualTo(2)
        assertThat(passes).hasValue(2)
    }

    @Test
    fun `a pass already in flight is joined rather than duplicated`() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache =
            HealthCache(FRESHNESS, clock) {
                passes.incrementAndGet()
                arrived.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "the flight was never released" }
                passes.get()
            }

        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val first = threads.submit<Int> { cache.get() }
            check(arrived.await(5, TimeUnit.SECONDS)) { "the first pass never started" }
            val joined = threads.submit<Int> { cache.get() }

            assertThatThrownBy { joined.get(250, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            assertThat(passes).hasValue(1)
            release.countDown()

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1)
            assertThat(joined.get(5, TimeUnit.SECONDS)).isEqualTo(1)
        }
        assertThat(passes).hasValue(1)
    }

    /** A failed pass is not a reading: the next caller evaluates rather than reading a stale answer. */
    @Test
    fun `a pass that threw is neither cached nor hidden`() {
        var explode = true
        val cache =
            HealthCache(FRESHNESS, clock) {
                passes.incrementAndGet()
                if (explode) error("the pass itself failed") else passes.get()
            }

        assertThatThrownBy { cache.get() }.isInstanceOf(IllegalStateException::class.java).hasMessage("the pass itself failed")
        explode = false

        assertThat(cache.get()).isEqualTo(2)
    }

    @Test
    fun `a freshness window that is not positive is refused`() {
        assertThatThrownBy { HealthCache(Duration.ZERO, clock) { 1 } }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun cache(): HealthCache<Int> = HealthCache(FRESHNESS, clock) { passes.incrementAndGet() }
}
