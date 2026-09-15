package com.gd.rain.boot.runtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** Uses the declarations rain-boot ships in `META-INF/spring.factories`: `config-check` and `seed`. */
class RainRuntimeEnvironmentPostProcessorTest {
    private val processor = RainRuntimeEnvironmentPostProcessor()

    @Test
    fun `a command process has no web server, stated with the highest precedence`() {
        val environment = environmentOf("rain.runtime.command" to "seed")

        processor.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.propertySources.first().name).isEqualTo(RainRuntimeEnvironmentPostProcessor.SOURCE_NAME)
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none")
    }

    @Test
    fun `a command process prints no banner, whatever the deployment or the declaration states`() {
        val environment = environmentOf("rain.runtime.command" to "seed", "spring.main.banner-mode" to "console")

        processor.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.getProperty(RainRuntimeEnvironmentPostProcessor.BANNER_MODE)).isEqualTo("off")
    }

    @Test
    fun `a process with roles is left as configured`() {
        val environment = environmentOf("rain.runtime.roles" to "api")

        processor.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.propertySources.contains(RainRuntimeEnvironmentPostProcessor.SOURCE_NAME)).isFalse()
        assertThat(environment.getProperty(RainRuntimeEnvironmentPostProcessor.BANNER_MODE)).isNull()
    }

    @Test
    fun `a command's declared properties take precedence over the deployment's`() {
        val environment = environmentOf("rain.runtime.command" to "seed", "spring.main.web-application-type" to "servlet")

        processor.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none")
    }

    @Test
    fun `an invalid selection is left for the validator to report with everything else, and prints no banner before it`() {
        val undeclared = environmentOf("rain.runtime.command" to "nope", "spring.main.banner-mode" to "console")
        val withRoles = environmentOf("rain.runtime.command" to "seed", "rain.runtime.roles" to "api")

        processor.postProcessEnvironment(undeclared, SpringApplication())
        processor.postProcessEnvironment(withRoles, SpringApplication())

        listOf(undeclared, withRoles).forEach { environment ->
            assertThat(environment.getProperty(RainRuntimeEnvironmentPostProcessor.BANNER_MODE)).isEqualTo("off")
            assertThat(environment.getProperty("spring.main.web-application-type")).isNull()
        }
    }

    @Test
    fun `it runs after configuration files are loaded`() {
        assertThat(processor.order).isGreaterThan(org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER)
    }

    private fun environmentOf(vararg properties: Pair<String, String>): StandardEnvironment =
        StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("test", properties.toMap())) }
}
