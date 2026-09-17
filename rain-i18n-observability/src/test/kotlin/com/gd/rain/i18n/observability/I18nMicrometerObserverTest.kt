package com.gd.rain.i18n.observability

import com.gd.rain.i18n.I18nObservation
import com.gd.rain.i18n.I18nObservationOperation
import com.gd.rain.i18n.I18nObservationOutcome
import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.LocaleResolutionReason
import com.gd.rain.i18n.RenderLayer
import com.gd.rain.i18n.observability.autoconfigure.RainI18nObservabilityAutoConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class I18nMicrometerObserverTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainI18nObservabilityAutoConfiguration::class.java))

    @Test
    fun `records only the closed i18n observation dimensions`() {
        val meters = SimpleMeterRegistry()
        val observer = I18nMicrometerObserver(meters)

        observer.observe(
            I18nObservation(
                I18nObservationOperation.RENDER,
                I18nObservationOutcome.RENDERED,
                LocaleResolutionReason.LOOKUP,
                RenderLayer.TENANT,
            ),
        )

        val meter =
            meters
                .find(I18nMicrometerObserver.OPERATIONS)
                .tags(
                    I18nMicrometerObserver.OPERATION,
                    "render",
                    I18nMicrometerObserver.OUTCOME,
                    "rendered",
                    I18nMicrometerObserver.LOCALE_REASON,
                    "lookup",
                    I18nMicrometerObserver.LAYER,
                    "tenant",
                ).counter()

        assertThat(meter?.count()).isEqualTo(1.0)
        val observed = meters.find(I18nMicrometerObserver.OPERATIONS).meters()
        assertThat(observed).hasSize(1)
        assertThat(
            observed
                .single()
                .id.tags
                .map { tag -> tag.key },
        ).containsExactlyInAnyOrder("operation", "outcome", "locale_reason", "layer")
    }

    @Test
    fun `uses none for an observation without a resolution or render layer`() {
        val meters = SimpleMeterRegistry()
        val observer = I18nMicrometerObserver(meters)

        observer.observe(I18nObservation(I18nObservationOperation.LOCALE_RESOLUTION, I18nObservationOutcome.REFUSED))
        observer.observe(
            I18nObservation(
                I18nObservationOperation.LOCALE_RESOLUTION,
                I18nObservationOutcome.REFUSED,
                localeReason = LocaleResolutionReason.LOOKUP,
            ),
        )
        observer.observe(
            I18nObservation(
                I18nObservationOperation.RENDER,
                I18nObservationOutcome.FAILED,
                layer = RenderLayer.APPLICATION,
            ),
        )

        assertThat(
            meters
                .find(I18nMicrometerObserver.OPERATIONS)
                .tags(
                    I18nMicrometerObserver.LOCALE_REASON,
                    I18nMicrometerObserver.NONE,
                    I18nMicrometerObserver.LAYER,
                    I18nMicrometerObserver.NONE,
                ).counter()
                ?.count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `magic auto configuration installs the observer only when metrics are available`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(I18nObserver::class.java)
        }
        runner.withBean(MeterRegistry::class.java, { SimpleMeterRegistry() }).run { context ->
            assertThat(context).hasSingleBean(I18nObserver::class.java)
            assertThat(context.getBean(I18nObserver::class.java)).isInstanceOf(I18nMicrometerObserver::class.java)
        }
    }

    @Test
    fun `application observer replaces the magic default`() {
        val custom = I18nObserver { }

        runner
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(I18nObserver::class.java, { custom })
            .run { context ->
                assertThat(context).hasSingleBean(I18nObserver::class.java)
                assertThat(context.getBean(I18nObserver::class.java)).isSameAs(custom)
            }
    }
}
