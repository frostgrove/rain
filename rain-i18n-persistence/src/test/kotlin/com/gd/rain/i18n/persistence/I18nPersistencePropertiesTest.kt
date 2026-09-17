package com.gd.rain.i18n.persistence

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class I18nPersistencePropertiesTest {
    @Test
    fun `disabled persistence refuses operational leaves instead of silently ignoring them`() {
        val problems = I18nPersistenceProperties(enabled = false, maxPins = 10).problems()

        assertThat(problems).singleElement().extracting { problem -> problem.code }.isEqualTo(ProblemCode.CONTRADICTS)
    }

    @Test
    fun `enabled persistence requires runtime identity and every lifecycle ceiling`() {
        assertThatThrownBy { I18nPersistenceSettings.of(I18nPersistenceProperties(enabled = true)) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
    }

    @Test
    fun `enabled persistence yields exact runtime and finite store limits`() {
        val settings = I18nPersistenceSettings.of(properties())

        assertThat(settings.runtimeIdentity.icuClDrTzdbIdentity).isEqualTo("icu4j-78.3")
        assertThat(settings.limits).isEqualTo(CatalogPersistenceLimits(4, 20, Duration.ofHours(2), 50))
    }

    private fun properties(): I18nPersistenceProperties =
        I18nPersistenceProperties(
            enabled = true,
            runtime = I18nPersistenceProperties.Runtime("rain-mf2/v1", "rain-i18n/1", "icu4j-78.3"),
            maxRetained = 4,
            maxPins = 20,
            maxPinLifetime = Duration.ofHours(2),
            changeFeedBatch = 50,
        )
}
