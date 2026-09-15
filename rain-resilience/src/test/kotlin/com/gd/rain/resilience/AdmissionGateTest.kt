package com.gd.rain.resilience

import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * One probe per cooldown: what a caller may start when the dependency may be down.
 *
 * The rule that makes an outage cheap is that a probe handed out is the only one for a whole open wait —
 * even when it is given back unused — and that one process never has two probes in flight.
 */
class AdmissionGateTest {
    private val clock = MutableClock(START)
    private val container = containerWith(breakerConfig(clock, failures = 1, halfOpenPermits = 2), "converter")
    private val breakers = BreakerRegistry(container, listOf(declaration("converter")))
    private val gate = AdmissionGate(breakers)
    private val converter = BreakerName("converter")

    @Test
    fun `a healthy dependency admits everything`() {
        assertThat(gate.admission(converter)).isEqualTo(AdmissionState.Unrestricted)
        val first = gate.reserve(converter)
        val second = gate.reserve(converter)

        assertThat(first).isInstanceOf(Reservation.Admitted::class.java)
        assertThat(second).isInstanceOf(Reservation.Admitted::class.java)
    }

    @Test
    fun `a tripped dependency holds work for the wait the caller should honour`() {
        breakers.fail(converter, 1)

        assertThat(gate.admission(converter)).isEqualTo(AdmissionState.Held(OPEN_WAIT))
        assertThat(gate.reserve(converter)).isEqualTo(Reservation.Held(OPEN_WAIT))
    }

    @Test
    fun `a rested dependency admits exactly one probe even when the breaker would permit two`() {
        breakers.fail(converter, 1)
        clock.advance(RESTED)
        assertThat(gate.admission(converter)).isEqualTo(AdmissionState.Probing)

        val first = gate.reserve(converter)
        val second = gate.reserve(converter)

        assertThat(first).isInstanceOf(Reservation.Admitted::class.java)
        assertThat(second).isEqualTo(Reservation.Held(OPEN_WAIT))
        assertThat(gate.admission(converter)).isEqualTo(AdmissionState.Held(OPEN_WAIT))
    }

    @Test
    fun `a probe given back unused is still the only probe of its cooldown`() {
        breakers.fail(converter, 1)
        clock.advance(RESTED)
        (gate.reserve(converter) as Reservation.Admitted).close()

        clock.advance(Duration.ofSeconds(1))
        assertThat(gate.reserve(converter)).isEqualTo(Reservation.Held(OPEN_WAIT.minusSeconds(1)))

        clock.advance(OPEN_WAIT.minusSeconds(1))
        assertThat(gate.reserve(converter)).isInstanceOf(Reservation.Admitted::class.java)
    }

    @Test
    fun `a probe in flight holds every other probe of this process, even across cooldowns`() {
        breakers.fail(converter, 1)
        clock.advance(RESTED)
        val inFlight = gate.reserve(converter) as Reservation.Admitted

        clock.advance(OPEN_WAIT.multipliedBy(3))

        assertThat(gate.reserve(converter)).describedAs("one call in flight per process").isEqualTo(Reservation.Held(OPEN_WAIT))
        inFlight.close()
    }

    @Test
    fun `a probe the gate refuses gives the breaker's permission back`() {
        breakers.fail(converter, 1)
        clock.advance(RESTED)
        val inFlight = gate.reserve(converter) as Reservation.Admitted
        gate.reserve(converter)

        val raw = container.find("converter").get()
        assertThat(raw.tryAcquirePermission()).describedAs("the refused probe's permission is free again").isTrue()
        assertThat(raw.tryAcquirePermission()).describedAs("and only that one").isFalse()
        raw.releasePermission()
        inFlight.close()
    }

    @Test
    fun `a failed probe starts a whole wait again`() {
        breakers.fail(converter, 1)
        clock.advance(RESTED)
        (gate.reserve(converter) as Reservation.Admitted).use { breakers.failed(it, IllegalStateException("still down")) }

        clock.advance(Duration.ofSeconds(30))

        assertThat(gate.admission(converter)).isEqualTo(AdmissionState.Held(Duration.ofSeconds(30)))
    }

    @Test
    fun `a probe that answers opens the gate completely`() {
        breakers.fail(converter, 1)
        clock.advance(RESTED)
        repeat(2) {
            (gate.reserve(converter) as Reservation.Admitted).use { breakers.succeeded(it) }
            clock.advance(OPEN_WAIT)
        }

        assertThat(gate.admission(converter)).isEqualTo(AdmissionState.Unrestricted)
    }
}
