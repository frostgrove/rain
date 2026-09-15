package com.gd.rain.resilience

import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Gap 11: admission is decided and the permission taken in one step.
 *
 * The old gate read the breaker's admission, then acted on that reading — in the unrestricted branch it
 * ignored what `admit()` answered. Here a decorator parks thread A right after it reads HALF_OPEN, another
 * reservation runs to completion meanwhile, and A is resumed: exactly one probe is admitted and no
 * permission is left taken.
 */
class AdmissionGateRaceTest {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val clock = MutableClock(START)
    private val name = BreakerName("dependency")

    @AfterEach
    fun stop() {
        executor.shutdownNow()
    }

    @Test
    fun `a reservation paused after reading half-open does not become a second probe`() {
        val container = containerWith(breakerConfig(clock, failures = 1, halfOpenPermits = 2), name.value)
        val raw = container.find(name.value).get()
        val pausing = PausingBreaker(raw)
        container.replace(name.value, pausing)
        val breakers = BreakerRegistry(container, listOf(declaration(name.value)))
        val gate = AdmissionGate(breakers)
        breakers.fail(name, 1)
        clock.advance(RESTED)

        val first =
            executor.submit(
                Callable {
                    pausing.pauseNextHalfOpenReadOf(Thread.currentThread())
                    gate.reserve(name)
                },
            )
        assertThat(pausing.reached.await(5, TimeUnit.SECONDS)).describedAs("thread A read HALF_OPEN").isTrue()

        val second = gate.reserve(name)
        pausing.resume()
        val firstOutcome = first.get(5, TimeUnit.SECONDS)

        assertThat(listOf(firstOutcome, second).filterIsInstance<Reservation.Admitted>()).hasSize(1)
        assertThat(second).isInstanceOf(Reservation.Admitted::class.java)
        assertThat(firstOutcome).isEqualTo(Reservation.Held(OPEN_WAIT))

        assertThat(raw.tryAcquirePermission()).describedAs("A's permission was given back").isTrue()
        assertThat(raw.tryAcquirePermission()).describedAs("the admitted probe still holds the other").isFalse()
        raw.releasePermission()

        (second as Reservation.Admitted).close()
        assertThat(raw.tryAcquirePermission()).isTrue()
        assertThat(raw.tryAcquirePermission()).describedAs("no permission leaked, none invented").isTrue()
        assertThat(raw.tryAcquirePermission()).isFalse()
    }

    @Test
    fun `a reservation paused while holding the only permission leaves the other caller held`() {
        val container = containerWith(breakerConfig(clock, failures = 1, halfOpenPermits = 1), name.value)
        val raw = container.find(name.value).get()
        val pausing = PausingBreaker(raw)
        container.replace(name.value, pausing)
        val breakers = BreakerRegistry(container, listOf(declaration(name.value)))
        val gate = AdmissionGate(breakers)
        breakers.fail(name, 1)
        clock.advance(RESTED)

        val first =
            executor.submit(
                Callable {
                    pausing.pauseNextHalfOpenReadOf(Thread.currentThread())
                    gate.reserve(name)
                },
            )
        assertThat(pausing.reached.await(5, TimeUnit.SECONDS)).isTrue()

        val second = gate.reserve(name)
        pausing.resume()
        val firstOutcome = first.get(5, TimeUnit.SECONDS)

        assertThat(second).isEqualTo(Reservation.Held(OPEN_WAIT))
        assertThat(firstOutcome).isInstanceOf(Reservation.Admitted::class.java)
        (firstOutcome as Reservation.Admitted).close()
        assertThat(raw.tryAcquirePermission()).describedAs("the probe's permission came back on close").isTrue()
        assertThat(raw.tryAcquirePermission()).isFalse()
    }
}
