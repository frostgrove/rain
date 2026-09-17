package com.gd.rain.i18n.web

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class I18nArtifactPropertiesTest {
    @Test
    fun `disabled local bootstrap refuses its operational leaves instead of silently ignoring them`() {
        val problems = I18nArtifactProperties(artifactLocation = "classpath:catalog.json").problems()

        assertThat(problems).singleElement().extracting { problem -> problem.code }.isEqualTo(ProblemCode.CONTRADICTS)
    }

    @Test
    fun `enabled local bootstrap requires a complete exact runtime identity`() {
        assertThatThrownBy { I18nArtifactProperties(enabled = true).settings() }
            .isInstanceOf(ConfigurationProblemsException::class.java)
    }

    @Test
    fun `complete local bootstrap retains its explicit low level settings`() {
        val settings =
            I18nArtifactProperties(
                enabled = true,
                artifactLocation = "classpath:catalog.rain-i18n",
                runtime = I18nArtifactProperties.Runtime("rain-mf2/v1", "rain-i18n/1", "icu4j-78.3"),
            ).settings()

        assertThat(settings.artifactLocation).isEqualTo("classpath:catalog.rain-i18n")
        assertThat(settings.runtimeIdentity.icuClDrTzdbIdentity).isEqualTo("icu4j-78.3")
    }

    @Test
    fun `pre-bean contributor claims nested runtime keys and rejects unknown local bootstrap keys`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "test",
                mapOf(
                    "spring.application.name" to "sample",
                    "rain.runtime.roles" to "api",
                    "rain.deployment.stage" to "test",
                    "rain.i18n.enabled" to "true",
                    "rain.i18n.artifact-location" to "classpath:catalog.rain-i18n",
                    "rain.i18n.runtime.profile" to "rain-mf2/v1",
                    "rain.i18n.runtime.engine" to "rain-i18n/1",
                    "rain.i18n.runtime.icu-cl-dr-tzdb-identity" to "icu4j-78.3",
                    "rain.i18n.runtime.engnie" to "typo",
                ),
            ),
        )

        val problems =
            RainConfigurationValidator
                .validate(environment, listOf(I18nArtifactConfigurationContributor()), emptyList())
                .fatal
                .filter { problem -> problem.path.startsWith("rain.i18n") }

        assertThat(problems.map { problem -> problem.path to problem.code })
            .containsExactly("rain.i18n.runtime.engnie" to ProblemCode.UNKNOWN_KEY)
    }
}
