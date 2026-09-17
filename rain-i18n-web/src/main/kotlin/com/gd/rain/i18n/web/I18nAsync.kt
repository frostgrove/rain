package com.gd.rain.i18n.web

import com.gd.rain.i18n.I18nView
import org.springframework.context.i18n.LocaleContext
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext
import org.springframework.core.task.TaskDecorator
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.TimeZone

/**
 * An explicit immutable hand-off of one already minted view. It is for in-process asynchronous work,
 * not durable delivery: jobs must persist a release reference rather than retain an object graph.
 */
public class I18nContextSnapshot internal constructor(
    public val view: I18nView,
) {
    /** Keeps the view explicit at the task boundary; no Rain thread-local becomes authoritative. */
    public fun <T> withView(block: (I18nView) -> T): T = block(view)
}

/** Captures the exact request view after locale and catalog selection have already completed. */
public fun I18nRequestContext.capture(): I18nContextSnapshot = I18nContextSnapshot(view)

/**
 * Optional Spring executor bridge for code that still reads LocaleContextHolder. The captured Rain
 * view stays explicit through I18nContextSnapshot; the holder is compatibility state only and is
 * restored on every normal or exceptional completion.
 */
public class I18nLocaleContextTaskDecorator(
    private val snapshot: I18nContextSnapshot,
) : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable =
        Runnable {
            val previous: LocaleContext? = LocaleContextHolder.getLocaleContext()
            LocaleContextHolder.setLocaleContext(
                SimpleTimeZoneAwareLocaleContext(
                    java.util.Locale.forLanguageTag(snapshot.view.spec.resolution.locale.value),
                    TimeZone.getTimeZone(snapshot.view.spec.zone),
                ),
            )
            try {
                runnable.run()
            } finally {
                if (previous == null) LocaleContextHolder.resetLocaleContext() else LocaleContextHolder.setLocaleContext(previous)
            }
        }
}

/**
 * Named opt-in bridge for one application-selected executor.
 *
 * It captures only an already-minted servlet view at submission time and forwards legacy Spring
 * locale compatibility state. A worker receives no request-scoped `I18nRuntime`; code that renders
 * there uses the explicit [I18nContextSnapshot] it intentionally captured.
 */
public class I18nRequestLocaleContextTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val snapshot =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
                ?.request
                ?.let(I18nRequestContext::of)
                ?.capture()
        return snapshot?.let { I18nLocaleContextTaskDecorator(it).decorate(runnable) } ?: runnable
    }
}
