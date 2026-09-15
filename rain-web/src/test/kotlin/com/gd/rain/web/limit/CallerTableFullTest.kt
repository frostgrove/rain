package com.gd.rain.web.limit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Gap 33: a full table refuses only callers it does not hold, counts every such refusal, keeps serving
 * the callers it holds, and pays one look — not a pass over the table — to find out it is full.
 */
class CallerTableFullTest {
    private var now: Long = 5_000_000_000L

    @Test
    fun `a new caller meeting a table of drained buckets is refused and counted`() {
        val throttle = TokenBucketThrottle(perMinute = 60, burst = 1, callers = 2) { now }
        throttle.allow("seated-1")
        throttle.allow("seated-2")

        repeat(3) { assertThat(throttle.allow("stranger-$it")).isFalse() }

        assertThat(throttle.refusedBecauseFull).isEqualTo(3)
        assertThat(throttle.tracked).isEqualTo(2)
    }

    @Test
    fun `a seated caller is still served by its own bucket while the table is full`() {
        val throttle = TokenBucketThrottle(perMinute = 60, burst = 1, callers = 2) { now }
        throttle.allow("seated-1")
        throttle.allow("seated-2")
        assertThat(throttle.allow("stranger")).isFalse()

        assertThat(throttle.allow("seated-1")).describedAs("an empty bucket is refused by the bucket, not by the table").isFalse()
        now += Duration.ofSeconds(1).toNanos() - 1
        assertThat(throttle.allow("seated-2")).isFalse()
        now += 1
        assertThat(throttle.allow("seated-1")).describedAs("a seated caller whose token came back was refused").isTrue()

        assertThat(throttle.refusedBecauseFull).describedAs("refusals of seated callers are not full-table refusals").isEqualTo(1)
    }

    @Test
    fun `finding room costs one look whatever the size of the table`() {
        val small = examinedToRefuse(seats = 32, strangers = 1_000)
        val large = examinedToRefuse(seats = 50_000, strangers = 1_000)

        assertThat(small).isEqualTo(1_000)
        assertThat(large).isEqualTo(1_000)
    }

    @Test
    fun `a table admits a seat only once per caller`() {
        val table = CallerTable(2)
        table.admit("a", 1.0, now)

        assertThat(runCatching { table.admit("a", 1.0, now) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun examinedToRefuse(
        seats: Int,
        strangers: Int,
    ): Long {
        val table = CallerTable(seats)
        repeat(seats) { seat ->
            val allowance = checkNotNull(table.admit("seated-$seat", 0.0, now))
            table.reschedule(allowance, Long.MAX_VALUE)
        }
        val before = table.examinedForRoom
        repeat(strangers) { assertThat(table.admit("stranger-$it", 1.0, now)).isNull() }
        assertThat(table.refusedBecauseFull).isEqualTo(strangers.toLong())
        return table.examinedForRoom - before
    }
}
