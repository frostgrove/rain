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
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/** Gap 44: the check timeout and the freshness window are declared, validated settings, and every check's importance is stated. */
class HealthPropertiesValidationTest {
    @Test
    fun `an absent section binds the declared defaults and reports nothing`() {
        assertThat(problemsUnderHealth()).isEmpty()
        runner.run { context ->
            val properties = context.getBean(HealthProperties::class.java)

            assertThat(properties.checkTimeout).isEqualTo(Duration.ofSeconds(2))
            assertThat(properties.freshness).isEqualTo(Duration.ofSeconds(1))
            assertThat(properties.checks).isEmpty()
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
        assertThat(problemsUnderHealth("rain.health.checks.database" to "sometimes").map { it.path to it.code })
            .containsExactly("rain.health.checks.database" to ProblemCode.INVALID)
    }

    @Test
    fun `a check name with dots is one key`() {
        assertThat(problemsUnderHealth("rain.health.checks.realtime.listener" to "degrading")).isEmpty()

        val bound =
            Binder
                .get(
                    environmentOf("rain.health.checks.realtime.listener" to "degrading"),
                ).bind(HealthProperties.PREFIX, HealthProperties::class.java)
                .get()
        assertThat(bound.checks).containsExactlyEntriesOf(mapOf("realtime.listener" to Importance.DEGRADING))
    }

    @Test
    fun `a check name outside the name alphabet is refused`() {
        assertThat(HealthProperties(checks = mapOf("data base" to Importance.REQUIRED)).problems().map { it.path to it.code })
            .containsExactly("rain.health.checks.data base" to ProblemCode.INVALID)
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
                .containsExactly("rain.health.checks.database" to ProblemCode.REQUIRED)
        }
    }

    @Test
    fun `a process with a DataSource runs the database check at the stated importance`() {
        runner
            .withBean(DataSource::class.java, { mockk<DataSource>() })
            .withPropertyValues("rain.health.checks.database=degrading")
            .run { context ->
                assertThat(context).hasNotFailed()
                val database = context.getBean(HealthRegistry::class.java).contributions().single()

                assertThat(database.name).isEqualTo(DatabaseHealthCheck.NAME)
                assertThat(database.importance).isEqualTo(Importance.DEGRADING)
                assertThat(database.timeout).isEqualTo(Duration.ofSeconds(2))
            }
    }

    @Test
    fun `a process without a DataSource has no database check`() {
        runner.run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(DatabaseHealthCheck::class.java)
            assertThat(context.getBean(HealthRegistry::class.java).contributions()).isEmpty()
        }
    }

    @Test
    fun `an application's own check joins the registry at its stated importance`() {
        runner
            .withBean("searchCheck", HealthCheck::class.java, { OwnCheck("search") })
            .withPropertyValues("rain.health.checks.search=informational")
            .run { context ->
                assertThat(context).hasNotFailed()
                val search = context.getBean(HealthRegistry::class.java).contributions().single()

                assertThat(search.name).isEqualTo("search")
                assertThat(search.importance).isEqualTo(Importance.INFORMATIONAL)
            }
    }

    @Test
    fun `an importance for a check this process does not run is not evaluated and does not stop the start`() {
        assertThat(HealthCheckImportanceCheck(emptyList(), emptyList(), mapOf("jobs" to Importance.REQUIRED)).problems())
            .containsExactly(
                ConfigurationProblem("rain.health.checks.jobs", ProblemCode.NOT_EVALUATED, "names no health check this process runs"),
            )
        runner.withPropertyValues("rain.health.checks.jobs=required").run { context ->
            assertThat(context).hasNotFailed()
        }
    }

    @Test
    fun `an importance for a contribution that states its own contradicts it`() {
        assertThat(
            HealthCheckImportanceCheck(emptyList(), listOf(passing("jobs")), mapOf("jobs" to Importance.REQUIRED)).problems().map {
                it.path to
                    it.code
            },
        ).containsExactly("rain.health.checks.jobs" to ProblemCode.CONTRADICTS)
    }

    private class OwnCheck(
        override val name: String,
    ) : HealthCheck {
        override val code: String = name
        override val timeout: Duration? = null

        override fun probe() {}
    }

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainRuntimeAutoConfiguration::class.java, RainHealthAutoConfiguration::class.java))
            .withBean(Clock::class.java, { MutableClock() })
            .withPropertyValues("rain.runtime.roles=api", "rain.deployment.stage=test")

    private fun environmentOf(vararg properties: Pair<String, String>): StandardEnvironment {
        val environment = StandardEnvironment()
        val values =
            mapOf(
                "spring.application.name" to "sample",
                "rain.runtime.roles" to "api",
                "rain.deployment.stage" to "test",
            ) + properties
        environment.propertySources.addFirst(MapPropertySource("test", values))
        return environment
    }

    private fun problemsUnderHealth(vararg properties: Pair<String, String>): List<ConfigurationProblem> =
        RainConfigurationValidator
            .validate(environmentOf(*properties), listOf(RainHealthConfigurationContributor()), emptyList())
            .problems
            .filter { it.path.startsWith(HealthProperties.PREFIX) }
}
