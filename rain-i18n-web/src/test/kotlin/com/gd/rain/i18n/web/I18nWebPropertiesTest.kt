package com.gd.rain.i18n.web

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class I18nWebPropertiesTest {
    @Test
    fun `disabled servlet bridge refuses source and executor settings instead of ignoring them`() {
        val problems = I18nWebProperties(enabled = false, querySource = "lang", propagateExecutors = true).problems()

        assertThat(problems).singleElement().extracting { problem -> problem.code }.isEqualTo(ProblemCode.CONTRADICTS)
    }

    @Test
    fun `enabled servlet policy retains explicit input names and selected executor bridge`() {
        val settings = I18nWebProperties(querySource = "lang", cookieSource = "locale", propagateExecutors = true).settings()

        assertThat(settings).isEqualTo(I18nWebSettings("lang", "locale", true))
    }

    @Test
    fun `invalid locale input names fail before auto configuration can install a source`() {
        assertThatThrownBy { I18nWebProperties(querySource = "not a token").settings() }
            .isInstanceOf(ConfigurationProblemsException::class.java)
    }

    @Test
    fun `pre-bean contributor claims every servlet key and rejects unknown ones`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "test",
                mapOf(
                    "spring.application.name" to "sample",
                    "rain.runtime.roles" to "api",
                    "rain.deployment.stage" to "test",
                    "rain.i18n.web.query-source" to "lang",
                    "rain.i18n.web.query-soruce" to "typo",
                ),
            ),
        )

        val problems =
            RainConfigurationValidator
                .validate(environment, listOf(I18nWebConfigurationContributor()), emptyList())
                .fatal
                .filter { problem -> problem.path.startsWith(I18nWebProperties.PREFIX) }

        assertThat(problems.map { problem -> problem.path to problem.code })
            .containsExactly("rain.i18n.web.query-soruce" to ProblemCode.UNKNOWN_KEY)
    }
}
