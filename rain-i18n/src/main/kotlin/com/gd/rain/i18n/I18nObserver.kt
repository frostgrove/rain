package com.gd.rain.i18n

/** The finite operations that can emit an i18n observation without exposing request-local data. */
public enum class I18nObservationOperation {
    LOCALE_RESOLUTION,
    RENDER,
}

/** The low-cardinality outcome of one observed i18n operation. */
public enum class I18nObservationOutcome {
    RESOLVED,
    RENDERED,
    REFUSED,
    FAILED,
}

/**
 * Immutable telemetry input. It deliberately contains no message key, rendered text, arguments,
 * locale spelling, tenant identity, principal or snapshot digest: those are unsuitable metric labels.
 */
public data class I18nObservation(
    public val operation: I18nObservationOperation,
    public val outcome: I18nObservationOutcome,
    public val localeReason: LocaleResolutionReason? = null,
    public val layer: RenderLayer? = null,
)

/**
 * Optional low-level observability hook. An observer has no access to a request context and an
 * observer failure is isolated from catalog behavior; a Micrometer or tracing adapter belongs above
 * this pure kernel contract.
 */
public fun interface I18nObserver {
    public fun observe(event: I18nObservation)

    public companion object {
        public val NONE: I18nObserver = I18nObserver {}
    }
}

internal fun I18nObserver.emit(event: I18nObservation) {
    try {
        observe(event)
    } catch (_: RuntimeException) {
        // Telemetry cannot change a locale choice, render result, or failure contract.
    }
}
