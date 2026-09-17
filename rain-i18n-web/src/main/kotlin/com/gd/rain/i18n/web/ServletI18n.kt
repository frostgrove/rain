package com.gd.rain.i18n.web

import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.DeferredMessage
import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.I18nView
import com.gd.rain.i18n.LocaleChoice
import com.gd.rain.i18n.LocaleResolution
import com.gd.rain.i18n.LocaleResolutionReason
import com.gd.rain.i18n.LocaleSource
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.Presentation
import com.gd.rain.i18n.RenderedMessage
import com.gd.rain.i18n.ViewSpec
import com.gd.rain.i18n.view
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.ServletRequestListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.LocaleContextResolver
import java.time.ZoneId
import java.util.TimeZone

/** Request-local immutable i18n state; it is stored as a servlet attribute, never as Rain global state. */
public class I18nRequestContext internal constructor(
    public val view: I18nView,
    private val protocolParticipated: Boolean,
) {
    private var templateLocale: LocaleTag? = null
    private var mixedTemplateLocales: Boolean = false

    /** Renders through this request's already resolved view and records a safe response language when unique. */
    public fun render(message: DeferredMessage): RenderedMessage = record(view.render(message))

    /** Atomically records a batch only after every message has rendered successfully. */
    internal fun renderAll(messages: List<DeferredMessage>): List<RenderedMessage> {
        val rendered = messages.map(view::render)
        rendered.forEach(::record)
        return rendered
    }

    private fun record(rendered: RenderedMessage): RenderedMessage {
        val previous = templateLocale
        if (previous == null) templateLocale = rendered.templateLocale
        if (previous != null && previous != rendered.templateLocale) mixedTemplateLocales = true
        return rendered
    }

    internal fun responseTemplateLocale(): LocaleTag? = templateLocale?.takeUnless { mixedTemplateLocales }

    internal fun protocolParticipated(): Boolean = protocolParticipated

    public companion object {
        public const val ATTRIBUTE: String = "com.gd.rain.i18n.web.I18nRequestContext"

        public fun of(request: HttpServletRequest): I18nRequestContext? = request.getAttribute(ATTRIBUTE) as? I18nRequestContext
    }
}

/** Magic-first request-scoped facade; advanced code can always use [I18nView] directly. */
public open class I18nRuntime internal constructor(
    private val context: I18nRequestContext,
) {
    public open val view: I18nView get() = context.view

    public open fun render(message: DeferredMessage): RenderedMessage = context.render(message)
}

/** The one replaceable policy that turns a minted resolution into an explicit locale/zone view. */
public fun interface I18nViewFactory {
    public fun create(
        snapshot: CatalogSnapshot,
        resolution: LocaleResolution.Resolved,
        request: HttpServletRequest,
    ): ViewSpec
}

/** Default view policy: the catalog's declared zone and automatic bidi isolation. */
public object DefaultI18nViewFactory : I18nViewFactory {
    override fun create(
        snapshot: CatalogSnapshot,
        resolution: LocaleResolution.Resolved,
        request: HttpServletRequest,
    ): ViewSpec = ViewSpec(resolution, snapshot.defaultZone, Presentation.AUTOMATIC_ISOLATION)
}

/**
 * Default magic view policy with an optional bounded observer supplied by a separate pairwise
 * integration such as `rain-i18n-observability`.
 */
public class ObservingI18nViewFactory(
    private val observer: I18nObserver,
) : I18nViewFactory {
    override fun create(
        snapshot: CatalogSnapshot,
        resolution: LocaleResolution.Resolved,
        request: HttpServletRequest,
    ): ViewSpec = ViewSpec(resolution, snapshot.defaultZone, Presentation.AUTOMATIC_ISOLATION, observer = observer)
}

/** A source either has no opinion, returns bounded canonical candidates, headers, or a typed refusal. */
public sealed interface ServletLocaleInput {
    public data object Absent : ServletLocaleInput

    public data class Choices(
        public val choices: List<LocaleChoice>,
    ) : ServletLocaleInput

    public data class AcceptLanguage(
        public val fields: List<String>,
    ) : ServletLocaleInput

    public data class Refused(
        public val reason: LocaleResolutionReason,
    ) : ServletLocaleInput
}

/** An application-owned ordered input source. It may not construct a view or access tenant authority. */
public interface ServletLocaleSource : Ordered {
    public val id: String

    public fun resolve(request: HttpServletRequest): ServletLocaleInput

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
}

/** The standard protocol source; it keeps repeated header fields intact for weighted parsing. */
public class AcceptLanguageServletLocaleSource : ServletLocaleSource {
    override val id: String = "accept_language"

    override fun resolve(request: HttpServletRequest): ServletLocaleInput {
        val fields = request.getHeaders(HEADER).toList()
        return if (fields.isEmpty()) ServletLocaleInput.Absent else ServletLocaleInput.AcceptLanguage(fields)
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    public companion object {
        public const val HEADER: String = "Accept-Language"
    }
}

/** Opt-in explicit query/route adapter; applications add it only on routes where locale is a declared input. */
public class ParameterServletLocaleSource(
    private val parameter: String,
    private val source: LocaleSource = LocaleSource.EXPLICIT,
    private val order: Int = Ordered.HIGHEST_PRECEDENCE,
) : ServletLocaleSource {
    override val id: String = "parameter_$parameter"

    init {
        require(I18nWebProperties.SOURCE_NAME.matches(parameter)) { "a locale parameter name is an HTTP token" }
    }

    override fun resolve(request: HttpServletRequest): ServletLocaleInput {
        val raw = request.getParameter(parameter) ?: return ServletLocaleInput.Absent
        val locale =
            try {
                LocaleTag.parse(raw)
            } catch (_: IllegalArgumentException) {
                return ServletLocaleInput.Refused(LocaleResolutionReason.MALFORMED)
            }
        return ServletLocaleInput.Choices(listOf(LocaleChoice(source, locale)))
    }

    override fun getOrder(): Int = order
}

/** Opt-in saved-preference adapter; ambiguous duplicate cookies are refused rather than guessed. */
public class CookieServletLocaleSource(
    private val cookie: String,
    private val source: LocaleSource = LocaleSource.USER,
    private val order: Int = Ordered.HIGHEST_PRECEDENCE + 1,
) : ServletLocaleSource {
    override val id: String = "cookie_$cookie"

    init {
        require(I18nWebProperties.SOURCE_NAME.matches(cookie)) { "a locale cookie name is an HTTP token" }
    }

    override fun resolve(request: HttpServletRequest): ServletLocaleInput {
        val matches = request.cookies?.filter { candidate -> candidate.name == cookie }.orEmpty()
        if (matches.isEmpty()) return ServletLocaleInput.Absent
        if (matches.size > 1) return ServletLocaleInput.Refused(LocaleResolutionReason.MALFORMED)
        val locale =
            try {
                LocaleTag.parse(matches.single().value)
            } catch (_: IllegalArgumentException) {
                return ServletLocaleInput.Refused(LocaleResolutionReason.MALFORMED)
            }
        return ServletLocaleInput.Choices(listOf(LocaleChoice(source, locale)))
    }

    override fun getOrder(): Int = order
}

/**
 * Servlet bridge that mints exactly one request context and clears Spring's legacy holder in
 * `finally`. The context deliberately remains a request attribute until request destruction so
 * ASYNC and ERROR redispatches render with the first dispatch's exact catalog/view.
 */
public class I18nRequestFilter(
    private val catalogs: CatalogSnapshotProvider,
    private val viewFactory: I18nViewFactory,
    sources: List<ServletLocaleSource>,
    private val observer: I18nObserver = I18nObserver.NONE,
) : OncePerRequestFilter() {
    private val sources: List<ServletLocaleSource> = sources.sortedWith(compareBy<ServletLocaleSource> { it.order }.thenBy { it.id })

    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    /** Error rendering still needs the request's exact catalog/view; never fall back to JVM locale. */
    override fun shouldNotFilterErrorDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val context = I18nRequestContext.of(request) ?: mint(request, response) ?: return
        if (context.protocolParticipated()) appendVary(response, ACCEPT_LANGUAGE)
        val previousLocaleContext = LocaleContextHolder.getLocaleContext()
        LocaleContextHolder.setLocaleContext(
            SimpleTimeZoneAwareLocaleContext(
                java.util.Locale.forLanguageTag(context.view.spec.resolution.locale.value),
                TimeZone.getTimeZone(context.view.spec.zone),
            ),
        )
        try {
            val localizedResponse = I18nContentLanguageResponse(response, context)
            filterChain.doFilter(request, localizedResponse)
            localizedResponse.applyContentLanguage()
        } finally {
            if (previousLocaleContext ==
                null
            ) {
                LocaleContextHolder.resetLocaleContext()
            } else {
                LocaleContextHolder.setLocaleContext(previousLocaleContext)
            }
        }
    }

    private fun mint(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): I18nRequestContext? {
        val snapshot = catalogs.current()
        val selection = select(snapshot, request)
        if (selection.resolution is LocaleResolution.Refused) {
            response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE)
            return null
        }
        val resolution = selection.resolution as LocaleResolution.Resolved
        return I18nRequestContext(
            snapshot.view(viewFactory.create(snapshot, resolution, request)),
            selection.acceptLanguageParticipated,
        ).also { context -> request.setAttribute(I18nRequestContext.ATTRIBUTE, context) }
    }

    private fun select(
        snapshot: CatalogSnapshot,
        request: HttpServletRequest,
    ): LocaleSelection {
        sources.forEach { source ->
            when (val input = source.resolve(request)) {
                ServletLocaleInput.Absent -> {}

                is ServletLocaleInput.Refused -> {
                    return LocaleSelection(LocaleResolution.Refused(input.reason), acceptLanguageParticipated = false)
                }

                is ServletLocaleInput.Choices -> {
                    val result = snapshot.resolve(input.choices, observer)
                    if (result !is LocaleResolution.Resolved || result.reason != LocaleResolutionReason.POLICY_DEFAULT) {
                        return LocaleSelection(result, acceptLanguageParticipated = false)
                    }
                }

                is ServletLocaleInput.AcceptLanguage -> {
                    val result = snapshot.resolveAcceptLanguage(input.fields, observer)
                    if (result !is LocaleResolution.Resolved || result.reason != LocaleResolutionReason.POLICY_DEFAULT) {
                        return LocaleSelection(result, acceptLanguageParticipated = true)
                    }
                }
            }
        }
        return LocaleSelection(snapshot.resolve(emptyList(), observer), acceptLanguageParticipated = false)
    }

    private fun appendVary(
        response: HttpServletResponse,
        value: String,
    ) {
        val existing = response.getHeader(VARY)
        if (existing.isNullOrBlank()) {
            response.setHeader(VARY, value)
        } else if (existing.split(',').none { it.trim().equals(value, ignoreCase = true) }) {
            response.setHeader(VARY, "$existing, $value")
        }
    }

    public companion object {
        public const val CONTENT_LANGUAGE: String = "Content-Language"
        public const val ACCEPT_LANGUAGE: String = "Accept-Language"
        public const val VARY: String = "Vary"
    }

    private data class LocaleSelection(
        val resolution: LocaleResolution,
        val acceptLanguageParticipated: Boolean,
    )
}

/**
 * Commits `Content-Language` immediately before Spring obtains a body writer or stream.
 *
 * The rendered template locale is known by then for ordinary MVC/Problem serialization, whereas a
 * post-chain assignment is too late because Spring may already have committed the response. A
 * handler that begins streaming before it renders carries no locale header; it must use an explicit
 * view and set its declared representation metadata before writing.
 */
private class I18nContentLanguageResponse(
    response: HttpServletResponse,
    private val context: I18nRequestContext,
) : HttpServletResponseWrapper(response) {
    private var languageApplied: Boolean = false

    override fun getOutputStream(): jakarta.servlet.ServletOutputStream {
        applyContentLanguage()
        return super.getOutputStream()
    }

    override fun getWriter(): java.io.PrintWriter {
        applyContentLanguage()
        return super.getWriter()
    }

    override fun flushBuffer() {
        applyContentLanguage()
        super.flushBuffer()
    }

    fun applyContentLanguage() {
        if (languageApplied || isCommitted) return
        context.responseTemplateLocale()?.let { locale ->
            setHeader(I18nRequestFilter.CONTENT_LANGUAGE, locale.value)
            languageApplied = true
        }
    }
}

/** Clears the retained request attribute only after every servlet dispatch, including async/error, is done. */
public class I18nRequestContextCleanupListener : ServletRequestListener {
    override fun requestDestroyed(event: ServletRequestEvent) {
        event.servletRequest.removeAttribute(I18nRequestContext.ATTRIBUTE)
    }
}

/** Spring MVC compatibility bridge; setting a locale is deliberately unsupported because it cannot mutate a snapshot. */
public class I18nLocaleContextResolver : LocaleContextResolver {
    override fun resolveLocaleContext(request: HttpServletRequest): org.springframework.context.i18n.LocaleContext {
        val context =
            I18nRequestContext.of(request)
                ?: return SimpleTimeZoneAwareLocaleContext(java.util.Locale.ROOT, TimeZone.getTimeZone(ZoneId.of("UTC")))
        return SimpleTimeZoneAwareLocaleContext(
            java.util.Locale.forLanguageTag(context.view.spec.resolution.locale.value),
            TimeZone.getTimeZone(context.view.spec.zone),
        )
    }

    override fun setLocaleContext(
        request: HttpServletRequest,
        response: HttpServletResponse?,
        localeContext: org.springframework.context.i18n.LocaleContext?,
    ): Unit = throw UnsupportedOperationException("Rain i18n locale is resolved before MVC and cannot be mutated by a controller")
}
