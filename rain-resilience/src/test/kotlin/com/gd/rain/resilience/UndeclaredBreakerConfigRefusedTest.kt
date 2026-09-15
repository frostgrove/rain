package com.gd.rain.resilience

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.observability.health.Importance
import com.gd.rain.resilience.autoconfigure.RainResilienceAutoConfiguration
import com.gd.rain.test.MutableClock
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import io.github.resilience4j.common.circuitbreaker.configuration.CommonCircuitBreakerConfigurationProperties.InstanceProperties
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * Gap 12: a declared breaker without an explicit `resilience4j.circuitbreaker.instances.<name>` entry is a
 * start-up refusal, never the library's default configuration.
 */
class UndeclaredBreakerConfigRefusedTest {
    private val payments = BreakerName("payments")

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration::class.java,
                    RainRuntimeAutoConfiguration::class.java,
                    RainHealthAutoConfiguration::class.java,
                    RainResilienceAutoConfiguration::class.java,
                ),
            ).withPropertyValues("rain.runtime.roles=api", "rain.deployment.stage=test")
            .withBean("paymentsBreaker", BreakerDeclaration::class.java, { BreakerDeclaration(payments, "payments", Importance.DEGRADING) })

    @Test
    fun `a declared breaker with no instance configuration refuses start-up`() {
        runner.run { context ->
            assertThat(context).hasFailed()
            val refusal =
                generateSequence(
                    context.startupFailure,
                    Throwable::cause,
                ).filterIsInstance<ConfigurationProblemsException>().first()
            assertThat(refusal.problems.map { it.path to it.code })
                .containsExactly("resilience4j.circuitbreaker.instances.payments" to ProblemCode.REQUIRED)
        }
    }

    @Test
    fun `the registry never creates the missing instance from the default configuration`() {
        val container = CircuitBreakerRegistry.of(breakerConfig(MutableClock(START)))
        val breakers = BreakerRegistry(container, listOf(declaration("payments")))

        assertThatThrownBy { breakers.reserve(payments) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("resilience4j.circuitbreaker.instances.payments")
        assertThat(container.find("payments")).isEmpty()
    }

    @Test
    fun `an instance that states nothing about its open wait is refused`() {
        val properties =
            CircuitBreakerConfigurationProperties().apply {
                instances["payments"] =
                    InstanceProperties().setSlidingWindowSize(5)
            }

        assertThat(check(properties).problems())
            .containsExactly(
                ConfigurationProblem(
                    "resilience4j.circuitbreaker.instances.payments.wait-duration-in-open-state",
                    ProblemCode.REQUIRED,
                    "no value is provided on the instance",
                ),
            )
    }

    @Test
    fun `an open wait stated only on the inherited configuration is refused, because that configuration may vary it`() {
        val properties =
            CircuitBreakerConfigurationProperties().apply {
                configs["shared"] =
                    InstanceProperties().setWaitDurationInOpenState(Duration.ofSeconds(10)).setEnableExponentialBackoff(true)
                instances["payments"] = InstanceProperties().setBaseConfig("shared")
            }

        assertThat(check(properties).problems().map { it.path to it.code })
            .containsExactly("resilience4j.circuitbreaker.instances.payments.wait-duration-in-open-state" to ProblemCode.REQUIRED)
    }

    /** Pins rule 3 to the library: the instance's own wait replaces an inherited varying wait with a fixed one. */
    @Test
    fun `an instance that states its own wait has a fixed open wait even over an exponential base`() {
        val properties =
            CircuitBreakerConfigurationProperties().apply {
                configs["shared"] =
                    InstanceProperties().setWaitDurationInOpenState(Duration.ofSeconds(10)).setEnableExponentialBackoff(true)
                instances["payments"] = InstanceProperties().setBaseConfig("shared").setWaitDurationInOpenState(Duration.ofSeconds(3))
            }
        val instance = checkNotNull(properties.instances["payments"])

        val built =
            properties.createCircuitBreakerConfig(
                "payments",
                instance,
                io.github.resilience4j.common
                    .CompositeCustomizer(emptyList()),
            )

        assertThat(check(properties).problems()).isEmpty()
        assertThat((1..6).map { built.waitIntervalFunctionInOpenState.apply(it) }).containsOnly(3_000L)
    }

    @Test
    fun `a varying open wait is refused`() {
        val properties =
            CircuitBreakerConfigurationProperties().apply {
                instances["payments"] =
                    InstanceProperties()
                        .setWaitDurationInOpenState(Duration.ofSeconds(10))
                        .setEnableExponentialBackoff(true)
                        .setEnableRandomizedWait(true)
            }

        assertThat(check(properties).problems().map { it.path to it.code }).containsExactly(
            "resilience4j.circuitbreaker.instances.payments.enable-exponential-backoff" to ProblemCode.INVALID,
            "resilience4j.circuitbreaker.instances.payments.enable-randomized-wait" to ProblemCode.INVALID,
        )
    }

    @Test
    fun `a customizer for a declared breaker and a duplicate declaration are both refused`() {
        val properties =
            CircuitBreakerConfigurationProperties().apply {
                instances["payments"] = InstanceProperties().setWaitDurationInOpenState(Duration.ofSeconds(10))
            }
        val check =
            BreakerConfigurationCheck(
                listOf(declaration("payments"), declaration("payments")),
                properties,
                listOf(CircuitBreakerConfigCustomizer.of("payments") { it.waitDurationInOpenState(Duration.ofSeconds(1)) }),
            )

        assertThat(check.problems().map { it.path to it.code }).containsExactly(
            "breaker:payments" to ProblemCode.CONTRADICTS,
            "resilience4j.circuitbreaker.instances.payments" to ProblemCode.CONTRADICTS,
        )
    }

    private fun check(properties: CircuitBreakerConfigurationProperties): BreakerConfigurationCheck =
        BreakerConfigurationCheck(listOf(declaration("payments")), properties, emptyList())
}
