package com.gd.rain.observability.health

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/** Gap 44: the check timeout and the freshness window are declared, validated settings, and the database's importance is stated. */
class HealthPropertiesValidationTest {
    @Test
    fun `an absent section binds the declared defaults and reports nothing`() {
        assertThat(problemsUnderHealth()).isEmpty()
        runner.run { context ->
            val properties = context.getBean(HealthProperties::class.java)

            assertThat(properties.checkTimeout).isEqualTo(Duration.ofSeconds(2))
            assertThat(properties.freshness).isEqualTo(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `stated values are the ones the registry runs with`() {
        runner.withPropertyValues("rain.health.check-timeout=750ms", "rain.health.freshness=5s").run { context ->
            assertThat(context.getBean(HealthProperties::class.java))
                .isEqualTo(HealthProperties(checkTimeout = Duration.ofMillis(750), freshness = Duration.ofSeconds(5)))
        }
    }

    @Test
    fun `a zero timeout and a negative freshness are both refused in one pass`() {
        assertThat(problemsUnderHealth("rain.health.check-timeout" to "0s", "rain.health.freshness" to "-1s").map { it.path to it.code })
            .containsExactly(
                "rain.health.check-timeout" to ProblemCode.INVALID,
                "rain.health.freshness" to ProblemCode.INVALID,
            )
    }

    @Test
    fun `an importance that is not one of the four is refused`() {
        assertThat(problemsUnderHealth("rain.health.database.importance" to "sometimes").map { it.path to it.code })
            .containsExactly("rain.health.database.importance" to ProblemCode.INVALID)
    }

    @Test
    fun `a database section without an importance is refused`() {
        assertThat(HealthProperties(database = DatabaseHealthProperties(importance = null)).problems())
            .containsExactly(
                ConfigurationProblem(
                    "rain.health.database.importance",
                    ProblemCode.REQUIRED,
                    "no value is provided; state one of required, degrading, informational, disabled",
                ),
            )
    }

    @Test
    fun `an unknown key under the section is reported`() {
        assertThat(problemsUnderHealth("rain.health.check-timout" to "1s").map { it.path to it.code })
            .containsExactly("rain.health.check-timout" to ProblemCode.UNKNOWN_KEY)
    }

    @Test
    fun `a process with a DataSource and no stated importance does not start`() {
        runner.withBean(DataSource::class.java, { mockk<DataSource>() }).run { context ->
            assertThat(context).hasFailed()
            val refusal =
                generateSequence(
                    context.startupFailure,
                    Throwable::cause,
                ).filterIsInstance<ConfigurationProblemsException>().first()
            assertThat(refusal.problems.map { it.path to it.code })
                .containsExactly("rain.health.database.importance" to ProblemCode.REQUIRED)
        }
    }

    @Test
    fun `a process with a DataSource contributes the database check at the stated importance`() {
        runner
            .withBean(DataSource::class.java, { mockk<DataSource>() })
            .withPropertyValues("rain.health.database.importance=degrading")
            .run { context ->
                assertThat(context).hasNotFailed()
                val database = context.getBean(HealthRegistry::class.java).contributions().single()

                assertThat(database).isInstanceOf(DatabaseHealthContribution::class.java)
                assertThat(database.importance).isEqualTo(Importance.DEGRADING)
                assertThat(database.timeout).isEqualTo(Duration.ofSeconds(2))
            }
    }

    @Test
    fun `a process without a DataSource has no database check and no database rule`() {
        runner.run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(DatabaseImportanceCheck::class.java)
            assertThat(context.getBean(HealthRegistry::class.java).contributions()).isEmpty()
        }
    }

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainRuntimeAutoConfiguration::class.java, RainHealthAutoConfiguration::class.java))
            .withBean(Clock::class.java, { MutableClock() })
            .withPropertyValues("rain.runtime.roles=api", "rain.deployment.stage=test")

    private fun problemsUnderHealth(vararg properties: Pair<String, String>): List<ConfigurationProblem> {
        val environment = StandardEnvironment()
        val values =
            mapOf(
                "spring.application.name" to "sample",
                "rain.runtime.roles" to "api",
                "rain.deployment.stage" to "test",
            ) + properties
        environment.propertySources.addFirst(MapPropertySource("test", values))
        return RainConfigurationValidator
            .validate(environment, listOf(RainHealthConfigurationContributor()), emptyList())
            .problems
            .filter { it.path.startsWith(HealthProperties.PREFIX) }
    }
}
