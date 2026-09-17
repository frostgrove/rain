package com.gd.rain.i18n.observability

import com.gd.rain.i18n.I18nObservation
import com.gd.rain.i18n.I18nObserver
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.Locale

/**
 * The optional low-level i18n-to-Micrometer adapter.
 *
 * Its four labels are closed enums or [NONE], so catalog keys, translations, requested locale
 * strings, tenant IDs, snapshots and message values cannot enter metric cardinality. Applications
 * can install another [I18nObserver] instead without changing any i18n view or transport contract.
 */
public class I18nMicrometerObserver(
    private val meters: MeterRegistry,
) : I18nObserver {
    override fun observe(event: I18nObservation) {
        Counter
            .builder(OPERATIONS)
            .tag(OPERATION, event.operation.name.metricValue())
            .tag(OUTCOME, event.outcome.name.metricValue())
            .tag(LOCALE_REASON, event.localeReason?.let { reason -> reason.name.metricValue() } ?: NONE)
            .tag(LAYER, event.layer?.let { layer -> layer.name.metricValue() } ?: NONE)
            .register(meters)
            .increment()
    }

    public companion object {
        /** Count of locale-resolution and render outcomes. */
        public const val OPERATIONS: String = "rain.i18n.operations"
        public const val OPERATION: String = "operation"
        public const val OUTCOME: String = "outcome"
        public const val LOCALE_REASON: String = "locale_reason"
        public const val LAYER: String = "layer"
        public const val NONE: String = "none"
    }
}

private fun String.metricValue(): String = lowercase(Locale.ROOT)
