package com.gd.rain.resilience

import com.gd.rain.observability.health.Importance
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An open breaker fails its readiness check, saying since when and why. */
class BreakerHealthCheckTest {
    private val clock = MutableClock(START)
    private val declaration = BreakerDeclaration(BreakerName("converter"), "converter_unavailable")
    private val breakers = BreakerRegistry(containerWith(breakerConfig(clock, failures = 2), "converter"), listOf(declaration))
    private val check = BreakerHealthCheck(declaration, breakers)

    @Test
    fun `a closed breaker passes`() {
        assertThatCode { check.probe() }.doesNotThrowAnyException()
    }

    @Test
    fun `an open breaker fails the check, naming its state, when it opened and what the dependency said`() {
        breakers.fail(declaration.name, 2, "the converter answered 503")

        assertThatThrownBy { check.probe() }
            .hasMessageContaining("breaker converter is open")
            .hasMessageContaining("2026-09-15T12:00:00Z")
            .hasMessageContaining("the converter answered 503")
    }

    @Test
    fun `name and code come from the declaration`() {
        assertThat(check.name).isEqualTo("breaker.converter")
        assertThat(check.code).isEqualTo("converter_unavailable")
        assertThat(check.timeout).isNull()
    }

    @Test
    fun `the check passes again as soon as a probe answers`() {
        breakers.fail(declaration.name, 2)
        assertThatThrownBy { check.probe() }.isInstanceOf(IllegalStateException::class.java)

        clock.advance(RESTED)
        breakers.admitted(declaration.name).use { breakers.succeeded(it) }

        assertThatCode { check.probe() }.doesNotThrowAnyException()
    }
}
