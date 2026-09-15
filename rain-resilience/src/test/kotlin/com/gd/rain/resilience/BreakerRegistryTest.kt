package com.gd.rain.resilience

import com.gd.rain.test.MutableClock
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** The breaker's life through rain's reservation, against a clock the test moves. */
class BreakerRegistryTest {
    private val clock = MutableClock(START)
    private val container = containerWith(breakerConfig(clock, failures = 5), "converter", "unconfigured-twin")
    private val breakers = BreakerRegistry(container, listOf(declaration("converter")))
    private val converter = BreakerName("converter")

    @Test
    fun `four failures in a row are not enough to withhold calls`() {
        breakers.fail(converter, 4)

        assertThat(breakers.state(converter).withholding).isFalse()
        assertThat(breakers.reserve(converter)).isInstanceOf(Reservation.Admitted::class.java)
    }

    @Test
    fun `the fifth failure withholds calls for the open wait, naming when and why`() {
        breakers.fail(converter, 5, "the converter answered 503")

        assertThat(breakers.reserve(converter)).isEqualTo(Reservation.Held(OPEN_WAIT))
        val state = breakers.state(converter)
        assertThat(state.state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThat(state.since).isEqualTo(START)
        assertThat(state.reason).isEqualTo("the converter answered 503")
    }

    @Test
    fun `a held caller is told the rest of the open wait`() {
        breakers.fail(converter, 5)
        clock.advance(Duration.ofSeconds(20))

        assertThat(breakers.reserve(converter)).isEqualTo(Reservation.Held(Duration.ofSeconds(40)))
    }

    @Test
    fun `one success inside the window ends the run`() {
        breakers.fail(converter, 4)
        breakers.admitted(converter).use { breakers.succeeded(it) }
        breakers.fail(converter, 4)

        assertThat(breakers.state(converter).state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    @Test
    fun `after the open wait exactly one probe is permitted`() {
        breakers.fail(converter, 5)
        clock.advance(RESTED)

        val probe = breakers.reserve(converter)
        val second = breakers.reserve(converter)

        assertThat(probe).isInstanceOf(Reservation.Admitted::class.java)
        assertThat(second).describedAs("the probe was not exclusive").isEqualTo(Reservation.Held(OPEN_WAIT))
    }

    @Test
    fun `a probe that answers closes the breaker and ends the episode`() {
        breakers.fail(converter, 5)
        clock.advance(RESTED)

        breakers.admitted(converter).use { breakers.succeeded(it) }

        val state = breakers.state(converter)
        assertThat(state.state).isEqualTo(CircuitBreaker.State.CLOSED)
        assertThat(state.since).isNull()
        assertThat(state.reason).isNull()
    }

    @Test
    fun `a probe that fails opens the breaker again for a whole wait and extends the episode`() {
        breakers.fail(converter, 5)
        clock.advance(RESTED)
        breakers.admitted(converter).use { breakers.failed(it, IllegalStateException("still refused")) }

        clock.advance(Duration.ofSeconds(30))

        assertThat(breakers.reserve(converter)).isEqualTo(Reservation.Held(Duration.ofSeconds(30)))
        assertThat(breakers.state(converter).since).describedAs("a failed probe extends the outage").isEqualTo(START)
        assertThat(breakers.state(converter).reason).isEqualTo("still refused")
    }

    @Test
    fun `a probe closed without an outcome gives its permission back and records nothing`() {
        breakers.fail(converter, 5)
        clock.advance(RESTED)
        breakers.admitted(converter).close()

        assertThat(breakers.reserve(converter)).isInstanceOf(Reservation.Admitted::class.java)
        assertThat(
            container
                .find("converter")
                .get()
                .metrics.numberOfFailedCalls,
        ).isZero()
    }

    @Test
    fun `an outcome is recorded once, and never after the reservation was closed`() {
        val permit = breakers.admitted(converter)
        breakers.succeeded(permit)

        assertThatThrownBy { breakers.failed(permit, IllegalStateException("late")) }.isInstanceOf(IllegalStateException::class.java)

        val closed = breakers.admitted(converter)
        closed.close()
        assertThatThrownBy { breakers.succeeded(closed) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a breaker forced open withholds calls with no scheduled end`() {
        container.find("converter").get().transitionToForcedOpenState()

        assertThat(breakers.reserve(converter)).isEqualTo(Reservation.ForcedOpen)
        assertThat(breakers.state(converter).since).isEqualTo(START)
    }

    @Test
    fun `an undeclared breaker is a wiring mistake, not an open gate`() {
        assertThatThrownBy { breakers.reserve(BreakerName("unconfigured-twin")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unconfigured-twin")
            .hasMessageContaining("converter")
    }

    @Test
    fun `the open wait is the breaker's configured one`() {
        assertThat(breakers.openWait(converter)).isEqualTo(OPEN_WAIT)
    }

    @Test
    fun `a reservation made by another registry is refused`() {
        val other = BreakerRegistry(containerWith(breakerConfig(clock), "converter"), listOf(declaration("converter")))
        val foreign = other.admitted(converter)

        assertThatThrownBy { breakers.succeeded(foreign) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
