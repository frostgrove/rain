package com.gd.rain.access.attempt

import com.gd.rain.access.internal.attempt.Admission
import com.gd.rain.access.internal.attempt.Attempt
import com.gd.rain.access.internal.attempt.AttemptKeyKind
import com.gd.rain.access.internal.attempt.AttemptKeys
import com.gd.rain.access.internal.attempt.AttemptPolicy
import com.gd.rain.access.internal.attempt.MemoryAttemptLimiter
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.SERVICE
import com.gd.rain.access.support.START
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

private val POLICY = AttemptPolicy(perIdentifier = 3, perAddress = 10, window = Duration.ofMinutes(15), lockFor = Duration.ofMinutes(5))

private fun attempt(
    identifier: String = "ada@example.test",
    address: String = "203.0.113.7",
) = Attempt(AGENT, identifier, address)

/** The limiter's rules, one at a time. */
class MemoryAttemptLimiterTest {
    private val clock = MutableClock(START)
    private val limiter = MemoryAttemptLimiter(POLICY, 10_000, clock)

    @Test
    fun `an attempt nothing has counted is admitted`() {
        assertThat(limiter.admit(attempt())).isEqualTo(Admission.Admitted)
    }

    @Test
    fun `the failure that reaches the identifier ceiling locks it, once, for lock-for`() {
        val recorded = List(POLICY.perIdentifier) { limiter.recordFailure(attempt()) }

        assertThat(recorded.dropLast(1)).allMatch { it.opened.isEmpty() }
        assertThat(recorded.last().opened.map { it.kind }).containsExactly(AttemptKeyKind.IDENTIFIER)
        assertThat(limiter.admit(attempt())).isEqualTo(Admission.Refused(POLICY.lockFor))
    }

    @Test
    fun `the address ceiling counts across identifiers`() {
        repeat(POLICY.perAddress) { limiter.recordFailure(attempt(identifier = "person-$it@example.test")) }

        assertThat(limiter.admit(attempt(identifier = "someone-else@example.test"))).isInstanceOf(Admission.Refused::class.java)
        assertThat(limiter.admit(attempt(identifier = "someone-else@example.test", address = "198.51.100.1"))).isEqualTo(Admission.Admitted)
    }

    @Test
    fun `asking for admission counts nothing, however often a locked caller knocks`() {
        repeat(POLICY.perIdentifier) { limiter.recordFailure(attempt()) }
        repeat(50) { limiter.admit(attempt()) }

        clock.advance(POLICY.lockFor)

        assertThat(limiter.admit(attempt())).describedAs("the lock ran its declared length and no more").isEqualTo(Admission.Admitted)
    }

    @Test
    fun `a success clears the identifier and leaves the address counting`() {
        repeat(POLICY.perIdentifier - 1) { limiter.recordFailure(attempt()) }
        limiter.recordSuccess(attempt())
        repeat(POLICY.perIdentifier - 1) { limiter.recordFailure(attempt()) }

        assertThat(limiter.admit(attempt())).isEqualTo(Admission.Admitted)
        repeat(POLICY.perAddress - 2 * (POLICY.perIdentifier - 1)) { limiter.recordFailure(attempt(identifier = "other-$it@example.test")) }
        assertThat(limiter.admit(attempt(identifier = "new@example.test"))).isInstanceOf(Admission.Refused::class.java)
    }

    @Test
    fun `failures after the window open a fresh counter`() {
        repeat(POLICY.perIdentifier - 1) { limiter.recordFailure(attempt()) }
        clock.advance(POLICY.window)

        limiter.recordFailure(attempt())

        assertThat(limiter.admit(attempt())).isEqualTo(Admission.Admitted)
    }

    @Test
    fun `an identifier cannot reach another subject type's counter, and no key carries the identifier`() {
        repeat(POLICY.perIdentifier) { limiter.recordFailure(attempt()) }

        assertThat(limiter.admit(Attempt(SERVICE, "ada@example.test", "198.51.100.9"))).isEqualTo(Admission.Admitted)
        assertThat(AttemptKeys.of(attempt(), POLICY).map { it.id }).noneMatch { it.contains("ada") }
    }
}

/** Gap 18: the memory table is bounded, making room costs a heap root, and a full table refuses rather than forgets. */
class AttemptLimiterCapacityTest {
    private val clock = MutableClock(START)

    @Test
    fun `a full table of live counters refuses an attempt it would have to seat, and keeps counting those it holds`() {
        val limiter = MemoryAttemptLimiter(POLICY, maximumKeys = 2, clock = clock)
        limiter.recordFailure(attempt(identifier = "held@example.test"))

        val stranger = limiter.admit(attempt(identifier = "stranger@example.test"))

        assertThat(stranger).describedAs("the stranger needs a seat for its identifier").isInstanceOf(Admission.Refused::class.java)
        assertThat((stranger as Admission.Refused).retryAfter).isEqualTo(POLICY.window)
        assertThat(limiter.admit(attempt(identifier = "held@example.test"))).isEqualTo(Admission.Admitted)
        repeat(POLICY.perIdentifier - 1) { limiter.recordFailure(attempt(identifier = "held@example.test")) }
        assertThat(
            limiter.admit(attempt(identifier = "held@example.test")),
        ).describedAs("a held counter still locks").isInstanceOf(Admission.Refused::class.java)
    }

    @Test
    fun `an expired counter makes room and a live one never does`() {
        val limiter = MemoryAttemptLimiter(POLICY, maximumKeys = 2, clock = clock)
        limiter.recordFailure(attempt(identifier = "first@example.test"))

        clock.advance(POLICY.window)

        assertThat(limiter.admit(attempt(identifier = "second@example.test"))).isEqualTo(Admission.Admitted)
        limiter.recordFailure(attempt(identifier = "second@example.test"))
        assertThat(limiter.tracked).isEqualTo(2)
    }

    @Test
    fun `making room examines one root per refusal, whatever the table's size`() {
        val limiter = MemoryAttemptLimiter(POLICY, maximumKeys = 20_001, clock = clock)
        repeat(20_000) { limiter.recordFailure(attempt(identifier = "person-$it@example.test", address = "198.51.100.1")) }
        val examined = limiter.examinedForRoom

        repeat(100) {
            assertThat(
                limiter.admit(attempt(identifier = "stranger-$it@example.test", address = "198.51.100.2")),
            ).isInstanceOf(Admission.Refused::class.java)
        }

        assertThat(limiter.examinedForRoom - examined).isEqualTo(100)
    }

    @Test
    fun `a failure the full table cannot seat is reported as uncounted`() {
        val limiter = MemoryAttemptLimiter(POLICY, maximumKeys = 2, clock = clock)
        limiter.recordFailure(attempt(identifier = "held@example.test"))

        val recorded = limiter.recordFailure(attempt(identifier = "raced@example.test"))

        assertThat(recorded.uncounted.map { it.kind }).containsExactly(AttemptKeyKind.IDENTIFIER)
    }
}
