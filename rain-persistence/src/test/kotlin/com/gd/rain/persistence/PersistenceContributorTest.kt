package com.gd.rain.persistence

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** The persistence sections report every problem of both blocks in one pass. */
class PersistenceContributorTest {
    private val base =
        mapOf(
            "spring.application.name" to "sample",
            "rain.runtime.roles" to "api",
            "rain.deployment.stage" to "test",
        )

    @Test
    fun `a missing statement timeout is required, never defaulted`() {
        val report = validate(base + ("rain.persistence.retry.attempts" to "3"))

        assertThat(report.map { it.path to it.code }).contains("rain.persistence.statement-timeout" to ProblemCode.REQUIRED)
    }

    @Test
    fun `every invalid persistence and lock value is reported together`() {
        val report =
            validate(
                base +
                    mapOf(
                        "rain.persistence.statement-timeout" to "0s",
                        "rain.persistence.retry.attempts" to "0",
                        "rain.persistence.retry.initial-delay" to "100ms",
                        "rain.persistence.retry.max-delay" to "50ms",
                        "rain.locks.timeout" to "0s",
                        "rain.locks.attempts" to "0",
                    ),
            )

        assertThat(report.map { it.path }).containsExactlyInAnyOrder(
            "rain.persistence.statement-timeout",
            "rain.persistence.retry.attempts",
            "rain.persistence.retry.max-delay",
            "rain.locks.timeout",
            "rain.locks.attempts",
        )
    }

    @Test
    fun `valid persistence settings with an absent lock section report nothing`() {
        assertThat(validate(base + ("rain.persistence.statement-timeout" to "30s"))).isEmpty()
    }

    private fun validate(properties: Map<String, String>) =
        StandardEnvironment()
            .apply {
                propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                propertySources.addFirst(MapPropertySource("test", HashMap<String, Any>(properties)))
            }.let { RainConfigurationValidator.validate(it, listOf(PersistenceConfigurationContributor()), emptyList()).fatal }
}
