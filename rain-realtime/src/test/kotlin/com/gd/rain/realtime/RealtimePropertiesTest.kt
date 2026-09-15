package com.gd.rain.realtime

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** `rain.realtime` through the same validator an application start runs. */
class RealtimePropertiesTest {
    private val base =
        mapOf(
            "spring.application.name" to "sample",
            "rain.runtime.roles" to "api",
            "rain.deployment.stage" to "test",
        )

    private val valid =
        base +
            mapOf(
                "rain.realtime.pool-name" to "sample-realtime",
                "rain.realtime.subscriber-buffer" to "64",
                "rain.realtime.max-subscriptions" to "1000",
            )

    @Test
    fun `an application without the section has nothing to report`() {
        assertThat(validate(base)).isEmpty()
    }

    @Test
    fun `a valid section reports nothing`() {
        assertThat(validate(valid)).isEmpty()
    }

    @Test
    fun `a stated section requires the pool name and both capacity bounds`() {
        val report = validate(base + ("rain.realtime.poll-interval" to "100ms"))

        assertThat(report.map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.realtime.pool-name" to ProblemCode.REQUIRED,
            "rain.realtime.subscriber-buffer" to ProblemCode.REQUIRED,
            "rain.realtime.max-subscriptions" to ProblemCode.REQUIRED,
        )
    }

    @Test
    fun `every invalid value is reported together`() {
        val report =
            validate(
                valid +
                    mapOf(
                        "rain.realtime.pool-name" to " ",
                        "rain.realtime.subscriber-buffer" to "0",
                        "rain.realtime.max-subscriptions" to "-1",
                        "rain.realtime.min-backoff" to "2s",
                        "rain.realtime.max-backoff" to "1s",
                        "rain.realtime.poll-interval" to "1500us",
                        "rain.realtime.connect-timeout" to "100ms",
                    ),
            )

        assertThat(report.map { it.path }).containsExactlyInAnyOrder(
            "rain.realtime.pool-name",
            "rain.realtime.subscriber-buffer",
            "rain.realtime.max-subscriptions",
            "rain.realtime.max-backoff",
            "rain.realtime.poll-interval",
            "rain.realtime.connect-timeout",
        )
        assertThat(report.map { it.code }).containsOnly(ProblemCode.INVALID)
    }

    @Test
    fun `a zero backoff and a zero poll interval are refused`() {
        val report = validate(valid + mapOf("rain.realtime.min-backoff" to "0s", "rain.realtime.poll-interval" to "0ms"))

        assertThat(report.map { it.path }).containsExactlyInAnyOrder("rain.realtime.min-backoff", "rain.realtime.poll-interval")
    }

    @Test
    fun `a key the section does not declare is unknown`() {
        val report = validate(valid + ("rain.realtime.pool-size" to "2"))

        assertThat(report.map { it.path to it.code }).containsExactly("rain.realtime.pool-size" to ProblemCode.UNKNOWN_KEY)
    }

    private fun validate(properties: Map<String, String>): List<ConfigurationProblem> =
        StandardEnvironment()
            .apply {
                propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                propertySources.addFirst(MapPropertySource("test", HashMap<String, Any>(properties)))
            }.let { RainConfigurationValidator.validate(it, listOf(RealtimeConfigurationContributor()), emptyList()).fatal }
}
