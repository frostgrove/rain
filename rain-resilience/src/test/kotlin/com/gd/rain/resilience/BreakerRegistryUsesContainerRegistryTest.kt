package com.gd.rain.resilience

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.observability.health.Importance
import com.gd.rain.resilience.autoconfigure.RainResilienceAutoConfiguration
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/**
 * Gap 12: rain's breakers are the container's `CircuitBreakerRegistry` instances, configured by
 * `resilience4j.circuitbreaker.instances.<name>` — there is no private `ofDefaults()` registry.
 */
class BreakerRegistryUsesContainerRegistryTest {
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
            ).withPropertyValues(
                "rain.runtime.roles=api",
                "rain.deployment.stage=test",
                "rain.health.checks.breaker.payments=degrading",
                "resilience4j.circuitbreaker.instances.payments.sliding-window-size=1",
                "resilience4j.circuitbreaker.instances.payments.minimum-number-of-calls=1",
                "resilience4j.circuitbreaker.instances.payments.wait-duration-in-open-state=45s",
            ).withBean(
                "paymentsBreaker",
                BreakerDeclaration::class.java,
                { BreakerDeclaration(payments, "payments") },
            )

    @Test
    fun `a failure recorded through rain opens the container's own instance`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val breakers = context.getBean(BreakerRegistry::class.java)

            breakers.admitted(payments).use { breakers.failed(it, IllegalStateException("payments are down")) }

            val instance = context.getBean(CircuitBreakerRegistry::class.java).find("payments").get()
            assertThat(instance.state).isEqualTo(CircuitBreaker.State.OPEN)
            assertThat(breakers.state(payments).state).isEqualTo(CircuitBreaker.State.OPEN)
        }
    }

    @Test
    fun `the instance carries the stated configuration, not the library default`() {
        runner.run { context ->
            assertThat(context.getBean(BreakerRegistry::class.java).openWait(payments)).isEqualTo(Duration.ofSeconds(45))
        }
    }

    @Test
    fun `rain creates no breaker instance of its own`() {
        runner.run { context ->
            context.getBean(BreakerRegistry::class.java).state(payments)

            assertThat(context.getBean(CircuitBreakerRegistry::class.java).allCircuitBreakers.map { it.name }).containsExactly("payments")
        }
    }

    @Test
    fun `an application's own registry bean replaces the starter's and is the one rain uses`() {
        val own =
            CircuitBreakerRegistry.of(
                CircuitBreakerConfig
                    .custom()
                    .slidingWindowSize(1)
                    .minimumNumberOfCalls(1)
                    .waitDurationInOpenState(Duration.ofSeconds(7))
                    .build(),
            )
        own.circuitBreaker("payments")

        runner.withBean(CircuitBreakerRegistry::class.java, { own }).run { context ->
            assertThat(context).hasNotFailed()
            val breakers = context.getBean(BreakerRegistry::class.java)

            breakers.admitted(payments).use { breakers.failed(it, IllegalStateException("down")) }

            assertThat(own.find("payments").get().state).isEqualTo(CircuitBreaker.State.OPEN)
            assertThat(breakers.openWait(payments)).isEqualTo(Duration.ofSeconds(7))
        }
    }

    @Test
    fun `every declaration is published to readiness under its own name, at the importance the application states`() {
        runner.run { context ->
            val registry = context.getBean(com.gd.rain.observability.health.HealthRegistry::class.java)
            val contribution = registry.contributions().single()

            assertThat(contribution.name).isEqualTo("breaker.payments")
            assertThat(contribution.importance).isEqualTo(Importance.DEGRADING)
        }
    }
}
