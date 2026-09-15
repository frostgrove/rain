package com.gd.rain.observability.health.actuator

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.observability.autoconfigure.RainActuatorHealthAutoConfiguration
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.observability.health.FakeCheck
import com.gd.rain.observability.health.HealthContribution
import com.gd.rain.observability.health.Importance
import com.gd.rain.observability.health.MutableClock
import com.gd.rain.observability.health.failing
import com.gd.rain.observability.health.passing
import com.gd.rain.observability.health.registryOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.health.registry.HealthContributorRegistry
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

/** The Actuator view of the registry's readings: published under each contribution's name, never probing again. */
class ActuatorHealthTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RainRuntimeAutoConfiguration::class.java,
                    RainHealthAutoConfiguration::class.java,
                    HealthContributorRegistryAutoConfiguration::class.java,
                    RainActuatorHealthAutoConfiguration::class.java,
                ),
            ).withBean(Clock::class.java, { MutableClock() })
            .withBean("jobsWorkers", HealthContribution::class.java, { failing("jobs.workers", Importance.DEGRADING, code = "jobs") })
            .withPropertyValues("rain.runtime.roles=worker", "rain.deployment.stage=test")

    @Test
    fun `the contributed check is published under its own name and reports degraded`() {
        runner.run { context ->
            val indicator = context.getBean(HealthContributorRegistry::class.java).getContributor("jobs.workers")

            assertThat(indicator).isInstanceOf(HealthContributionIndicator::class.java)
            val health = checkNotNull((indicator as HealthIndicator).health())
            assertThat(health.status).isEqualTo(HealthContributionIndicator.DEGRADED)
            assertThat(health.details).containsEntry("code", "jobs").containsEntry("importance", "degrading")
        }
    }

    @Test
    fun `the actuator view does not ask the dependency a second time`() {
        runner.run { context ->
            val check = context.getBean("jobsWorkers", FakeCheck::class.java)
            val indicator = context.getBean(HealthContributorRegistry::class.java).getContributor("jobs.workers") as HealthIndicator

            indicator.health()
            val asked = check.asked.get()
            indicator.health()
            indicator.health()

            assertThat(asked).isEqualTo(1)
            assertThat(check.asked).hasValue(asked)
        }
    }

    @Test
    fun `without an actuator registry nothing is published`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RainRuntimeAutoConfiguration::class.java,
                    RainHealthAutoConfiguration::class.java,
                    RainActuatorHealthAutoConfiguration::class.java,
                ),
            ).withPropertyValues("rain.runtime.roles=worker", "rain.deployment.stage=test")
            .run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(ActuatorHealthBridge::class.java) }
    }
}

/** The status each importance's failure is published with, with no context in the way. */
class HealthContributionStatusTest {
    @Test
    fun `each importance maps its failure to a declared status`() {
        assertThat(statusOf(failing("database", Importance.REQUIRED))).isEqualTo(Status.DOWN)
        assertThat(statusOf(failing("jobs", Importance.DEGRADING))).isEqualTo(HealthContributionIndicator.DEGRADED)
        assertThat(statusOf(failing("note", Importance.INFORMATIONAL))).isEqualTo(Status.UP)
        assertThat(statusOf(passing("database"))).isEqualTo(Status.UP)
    }

    @Test
    fun `a disabled check is reported and not probed`() {
        val disabled = FakeCheck("legacy", importance = Importance.DISABLED) { error("never") }

        assertThat(statusOf(disabled)).isEqualTo(Status.UNKNOWN)
        assertThat(disabled.asked).hasValue(0)
    }

    @Test
    fun `the details name the code, the importance and the error`() {
        registryOf(failing("database", message = "connection refused")).use {
            val details = HealthContributionIndicator(it.contributions().single(), it).health().details

            assertThat(details)
                .containsEntry("code", "database")
                .containsEntry("importance", "required")
                .containsEntry("error", "connection refused")
        }
    }

    private fun statusOf(contribution: HealthContribution): Status =
        registryOf(contribution).use { HealthContributionIndicator(contribution, it).health().status }
}
