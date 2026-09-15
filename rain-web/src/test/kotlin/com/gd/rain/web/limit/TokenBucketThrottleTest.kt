package com.gd.rain.web.limit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** The bucket, the bound and the eviction rule, on a clock the test moves. */
class TokenBucketThrottleTest {
    private var now: Long = 1_000_000_000L

    private fun throttle(
        perMinute: Int,
        burst: Int,
        callers: Int,
    ) = TokenBucketThrottle(perMinute, burst, callers) { now }

    private fun advance(duration: Duration) {
        now += duration.toNanos()
    }

    @Test
    fun `a bucket is spent down to the burst and refills with time`() {
        val spending = throttle(perMinute = 60, burst = 2, callers = 100)

        assertThat(spending.allow("1.2.3.4")).isTrue()
        assertThat(spending.allow("1.2.3.4")).isTrue()
        assertThat(spending.allow("1.2.3.4")).describedAs("a caller spent its burst and kept going").isFalse()
        assertThat(spending.allow("5.6.7.8")).describedAs("one caller's burst was charged to another").isTrue()

        advance(Duration.ofSeconds(1))

        assertThat(spending.allow("1.2.3.4")).describedAs("a second passed at sixty a minute and no token came back").isTrue()
    }

    @Test
    fun `half a second buys half a token and two halves buy one`() {
        val spending = throttle(perMinute = 120, burst = 1, callers = 100)
        assertThat(spending.allow("1.2.3.4")).isTrue()

        advance(Duration.ofMillis(400))
        assertThat(spending.allow("1.2.3.4")).isFalse()

        advance(Duration.ofMillis(200))
        assertThat(spending.allow("1.2.3.4")).isTrue()
    }

    @Test
    fun `the caller table refuses rather than growing without a bound`() {
        val spending = throttle(perMinute = 60, burst = 1, callers = 2)

        assertThat(spending.allow("1.1.1.1")).isTrue()
        assertThat(spending.allow("2.2.2.2")).isTrue()
        assertThat(spending.allow("3.3.3.3")).isFalse()
        assertThat(spending.tracked).isEqualTo(2)
    }

    @Test
    fun `a caller that owes nothing is forgotten to make room for a new one`() {
        val spending = throttle(perMinute = 60, burst = 1, callers = 2)
        spending.allow("1.1.1.1")
        spending.allow("2.2.2.2")

        advance(Duration.ofMinutes(1))

        assertThat(spending.allow("3.3.3.3")).isTrue()
        assertThat(spending.refusedBecauseFull).isZero()
    }

    @Test
    fun `a drained bucket is never forgotten to make room`() {
        val spending = throttle(perMinute = 60, burst = 1, callers = 1)
        assertThat(spending.allow("1.1.1.1")).isTrue()

        repeat(3) { assertThat(spending.allow("2.2.2.2")).isFalse() }

        assertThat(spending.tracked).isEqualTo(1)
        assertThat(spending.allow("1.1.1.1")).isFalse()
    }

    @Test
    fun `a refused caller is told whole seconds rounded up, and a throttle that never refills names no wait`() {
        assertThat(throttle(perMinute = 120, burst = 60, callers = 10).retryAfterSeconds()).isEqualTo(1)
        assertThat(throttle(perMinute = 6, burst = 2, callers = 10).retryAfterSeconds()).isEqualTo(10)
        assertThat(throttle(perMinute = 7, burst = 2, callers = 10).retryAfterSeconds()).isEqualTo(9)
        assertThat(throttle(perMinute = 0, burst = 1, callers = 10).retryAfterSeconds()).isNull()
        assertThat(throttle(perMinute = 0, burst = 1, callers = 10).interval()).isNull()
        assertThat(throttle(perMinute = 60, burst = 1, callers = 10).interval()).isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `a burst nobody could spend and a negative refill are refused at construction`() {
        assertThatThrownBy {
            TokenBucketThrottle(
                perMinute = 60,
                burst = 0,
                callers = 10,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TokenBucketThrottle(
                perMinute = -1,
                burst = 1,
                callers = 10,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TokenBucketThrottle(
                perMinute = 60,
                burst = 1,
                callers = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
