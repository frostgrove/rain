package com.gd.rain.i18n.web

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.ViolationParameters
import com.gd.rain.i18n.ArgumentSpec
import com.gd.rain.i18n.ArgumentType
import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageArguments
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.Mf2Profile
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec
import com.gd.rain.i18n.bind
import com.gd.rain.web.problem.ErrorCodeRegistrar
import com.gd.rain.web.problem.ProblemFormat
import com.gd.rain.web.problem.ProblemRenderer
import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.i18n.SimpleLocaleContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.ZoneId
import java.util.Locale

class I18nRequestFilterTest {
    private val en: LocaleTag = LocaleTag.parse("en")
    private val ru: LocaleTag = LocaleTag.parse("ru")
    private val snapshot: CatalogSnapshot = catalog()
    private val greeting = checkNotNull(snapshot.contract(MessageKey("web", "greeting")))

    @Test
    fun `accept language mints one request view, declares vary, and restores the enclosing Spring context`() {
        val request = MockHttpServletRequest("GET", "/greeting").apply { addHeader("Accept-Language", "ru, en;q=0.2") }
        val response = MockHttpServletResponse()
        val inherited = SimpleLocaleContext(Locale.CANADA)
        LocaleContextHolder.setLocaleContext(inherited)

        try {
            filter().doFilter(request, response) { servletRequest, _ ->
                val context = requireNotNull(I18nRequestContext.of(servletRequest as MockHttpServletRequest))
                assertThat(context.view.spec.resolution.locale).isEqualTo(ru)
                assertThat(
                    context.render(snapshot.bind(greeting, MessageArguments.build { text("name", "Алиса") })).text,
                ).contains("Привет", "Алиса")
                assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.forLanguageTag("ru"))
            }

            assertThat(response.getHeader(I18nRequestFilter.VARY)).isEqualTo(I18nRequestFilter.ACCEPT_LANGUAGE)
            assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isEqualTo("ru")
            destroy(request)
            assertThat(I18nRequestContext.of(request)).isNull()
            assertThat(LocaleContextHolder.getLocaleContext()).isSameAs(inherited)
        } finally {
            LocaleContextHolder.resetLocaleContext()
        }
    }

    @Test
    fun `a supplied observer sees the exact resolution and render without becoming request state`() {
        val observed = mutableListOf<com.gd.rain.i18n.I18nObservation>()
        val request = MockHttpServletRequest("GET", "/greeting").apply { addHeader("Accept-Language", "ru") }

        observingFilter(I18nObserver(observed::add)).doFilter(request, MockHttpServletResponse()) { servletRequest, _ ->
            requireNotNull(I18nRequestContext.of(servletRequest as MockHttpServletRequest))
                .render(snapshot.bind(greeting, MessageArguments.build { text("name", "Алиса") }))
        }

        assertThat(observed.map { event -> event.operation to event.outcome }).containsExactly(
            com.gd.rain.i18n.I18nObservationOperation.LOCALE_RESOLUTION to com.gd.rain.i18n.I18nObservationOutcome.RESOLVED,
            com.gd.rain.i18n.I18nObservationOperation.RENDER to com.gd.rain.i18n.I18nObservationOutcome.RENDERED,
        )
    }

    @Test
    fun `an explicit route locale wins without adding an irrelevant accept language cache dimension`() {
        val request =
            MockHttpServletRequest("GET", "/greeting")
                .apply {
                    setParameter("lang", "en")
                    addHeader("Accept-Language", "ru")
                }
        val response = MockHttpServletResponse()

        filter(ParameterServletLocaleSource("lang"), AcceptLanguageServletLocaleSource()).doFilter(request, response) { servletRequest, _ ->
            val context = requireNotNull(I18nRequestContext.of(servletRequest as MockHttpServletRequest))
            assertThat(context.view.spec.resolution.locale).isEqualTo(en)
            context.render(snapshot.bind(greeting, MessageArguments.build { text("name", "Ada") }))
        }

        assertThat(response.getHeader(I18nRequestFilter.VARY)).isNull()
        assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isEqualTo("en")
    }

    @Test
    fun `an opt in cookie preference wins over protocol negotiation without adding protocol vary`() {
        val request =
            MockHttpServletRequest("GET", "/greeting")
                .apply {
                    setCookies(Cookie("locale", "ru"))
                    addHeader("Accept-Language", "en")
                }
        val response = MockHttpServletResponse()

        filter(CookieServletLocaleSource("locale"), AcceptLanguageServletLocaleSource()).doFilter(request, response) { servletRequest, _ ->
            val context = requireNotNull(I18nRequestContext.of(servletRequest as MockHttpServletRequest))
            assertThat(context.view.spec.resolution.locale).isEqualTo(ru)
            context.render(snapshot.bind(greeting, MessageArguments.build { text("name", "Алиса") }))
        }

        assertThat(response.getHeader(I18nRequestFilter.VARY)).isNull()
        assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isEqualTo("ru")
    }

    @Test
    fun `ambiguous or malformed locale cookies are pre controller refusals`() {
        val duplicate = MockHttpServletRequest("GET", "/greeting").apply { setCookies(Cookie("locale", "en"), Cookie("locale", "ru")) }
        val malformed = MockHttpServletRequest("GET", "/greeting").apply { setCookies(Cookie("locale", "no space allowed")) }

        listOf(duplicate, malformed).forEach { request ->
            val response = MockHttpServletResponse()
            var invoked = false

            filter(CookieServletLocaleSource("locale")).doFilter(request, response) { _, _ -> invoked = true }

            assertThat(invoked).isFalse()
            assertThat(response.status).isEqualTo(406)
        }
    }

    @Test
    fun `a malformed explicit locale is a typed pre-controller refusal`() {
        val request = MockHttpServletRequest("GET", "/greeting").apply { setParameter("lang", "no space allowed") }
        val response = MockHttpServletResponse()
        var invoked = false

        filter(ParameterServletLocaleSource("lang")).doFilter(request, response) { _, _ -> invoked = true }

        assertThat(invoked).isFalse()
        assertThat(response.status).isEqualTo(406)
        assertThat(I18nRequestContext.of(request)).isNull()
    }

    @Test
    fun `an allowlisted problem mapping localizes only human fields and leaves problem contracts intact`() {
        val request = MockHttpServletRequest("GET", "/greeting").apply { addHeader("Accept-Language", "ru") }
        val response = MockHttpServletResponse()
        val localizer =
            I18nProblemLocalizer(
                LocalizedProblemMessages.build {
                    fault(RainErrorCodes.VALIDATION_FAILED) {
                        snapshot.bind(greeting, MessageArguments.build { text("name", "проверьте поля") })
                    }
                    violation(RainErrorCodes.REQUIRED) { violation ->
                        snapshot.bind(
                            greeting,
                            MessageArguments.build { text("name", checkNotNull(violation.parameters.text("field_label"))) },
                        )
                    }
                },
            )
        val renderer = ProblemRenderer(ErrorCodeRegistrar.register(listOf(RainErrorCodes))) { listOf(localizer) }
        val fault =
            Fault.validation(
                listOf(
                    Violation.at(
                        com.gd.rain.core.error
                            .path("email"),
                        RainErrorCodes.REQUIRED,
                        parameters = ViolationParameters.build { text("field_label", "почта") },
                    ),
                ),
            )

        filter().doFilter(request, response) { servletRequest, _ ->
            val rendered = renderer.render(fault, servletRequest as MockHttpServletRequest)
            val errors = checkNotNull(rendered.problem.properties)[ProblemFormat.ERRORS] as List<*>
            val error = errors.single() as Map<*, *>

            assertThat(rendered.status).isEqualTo(422)
            assertThat(rendered.problem.detail).contains("Привет", "проверьте поля")
            assertThat(error["pointer"]).isEqualTo("/email")
            assertThat(error["code"]).isEqualTo("required")
            assertThat(error["message"] as String).contains("Привет", "почта")
            assertThat(rendered.problem.properties?.get(ProblemFormat.CODE)).isEqualTo("validation_failed")
        }

        assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isEqualTo("ru")
    }

    @Test
    fun `internal problems never enter the localization mapping`() {
        val request = MockHttpServletRequest("GET", "/greeting").apply { addHeader("Accept-Language", "ru") }
        val response = MockHttpServletResponse()
        val localizer =
            I18nProblemLocalizer(
                LocalizedProblemMessages.build {
                    fault(RainErrorCodes.INTERNAL) {
                        snapshot.bind(greeting, MessageArguments.build { text("name", "leak") })
                    }
                },
            )
        val renderer = ProblemRenderer(ErrorCodeRegistrar.register(listOf(RainErrorCodes))) { listOf(localizer) }

        filter().doFilter(request, response) { servletRequest, _ ->
            val rendered = renderer.render(Fault(FaultKind.INTERNAL), servletRequest as MockHttpServletRequest)
            assertThat(rendered.problem.detail).isEqualTo("the request failed")
        }

        assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isNull()
    }

    @Test
    fun `an explicit async snapshot carries the immutable view and restores the worker locale holder`() {
        val request = MockHttpServletRequest("GET", "/greeting").apply { addHeader("Accept-Language", "ru") }
        val response = MockHttpServletResponse()
        val inherited = SimpleLocaleContext(Locale.GERMANY)

        filter().doFilter(request, response) { servletRequest, _ ->
            val snapshot = requireNotNull(I18nRequestContext.of(servletRequest as MockHttpServletRequest)).capture()
            LocaleContextHolder.setLocaleContext(inherited)
            try {
                I18nLocaleContextTaskDecorator(snapshot)
                    .decorate {
                        assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.forLanguageTag("ru"))
                        snapshot.withView { view ->
                            assertThat(
                                view.render(this.snapshot.bind(greeting, MessageArguments.build { text("name", "Ada") })).templateLocale,
                            ).isEqualTo(ru)
                        }
                    }.run()
                assertThat(LocaleContextHolder.getLocaleContext()).isSameAs(inherited)
            } finally {
                LocaleContextHolder.resetLocaleContext()
            }
        }
    }

    @Test
    fun `the named executor decorator captures a current request only at task submission`() {
        val request = MockHttpServletRequest("GET", "/greeting").apply { addHeader("Accept-Language", "ru") }
        val response = MockHttpServletResponse()
        val inherited = SimpleLocaleContext(Locale.GERMANY)
        lateinit var decorated: Runnable

        filter().doFilter(request, response) { servletRequest, _ ->
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(servletRequest as MockHttpServletRequest))
            try {
                decorated =
                    I18nRequestLocaleContextTaskDecorator()
                        .decorate(
                            Runnable {
                                assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.forLanguageTag("ru"))
                            },
                        )
            } finally {
                RequestContextHolder.resetRequestAttributes()
            }
        }

        LocaleContextHolder.setLocaleContext(inherited)
        try {
            decorated.run()
            assertThat(LocaleContextHolder.getLocaleContext()).isSameAs(inherited)
        } finally {
            LocaleContextHolder.resetLocaleContext()
        }
    }

    @Test
    fun `an error dispatch receives the same explicit locale policy and still clears its context`() {
        val request =
            MockHttpServletRequest("GET", "/greeting")
                .apply {
                    addHeader("Accept-Language", "ru")
                }
        val response = MockHttpServletResponse()
        val bridge = filter()
        var initial: I18nRequestContext? = null

        bridge.doFilter(request, response) { servletRequest, _ ->
            initial = requireNotNull(I18nRequestContext.of(servletRequest as MockHttpServletRequest))
        }
        request.dispatcherType = DispatcherType.ERROR

        bridge.doFilter(request, response) { servletRequest, _ ->
            val context = requireNotNull(I18nRequestContext.of(servletRequest as MockHttpServletRequest))
            assertThat(context).isSameAs(initial)
            assertThat(context.view.spec.resolution.locale).isEqualTo(ru)
            context.render(snapshot.bind(greeting, MessageArguments.build { text("name", "ошибка") }))
        }

        assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isEqualTo("ru")
        destroy(request)
        assertThat(I18nRequestContext.of(request)).isNull()
    }

    private fun filter(vararg sources: ServletLocaleSource): I18nRequestFilter =
        I18nRequestFilter(
            { snapshot },
            DefaultI18nViewFactory,
            sources.toList().ifEmpty { listOf(AcceptLanguageServletLocaleSource()) },
        )

    private fun observingFilter(observer: I18nObserver): I18nRequestFilter =
        I18nRequestFilter(
            { snapshot },
            ObservingI18nViewFactory(observer),
            listOf(AcceptLanguageServletLocaleSource()),
            observer,
        )

    private fun destroy(request: MockHttpServletRequest) {
        I18nRequestContextCleanupListener().requestDestroyed(ServletRequestEvent(request.servletContext, request))
    }

    private fun catalog(): CatalogSnapshot {
        val source = "Hello, {${'$'}name}"
        val sourceDigest = CatalogDigests.source(Mf2Profile.ID, en, source, "greeting")
        return (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("web", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en, ru), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("web", "greeting"),
                                1,
                                source,
                                "greeting",
                                arguments = listOf(ArgumentSpec("name", ArgumentType.TEXT)),
                                translations =
                                    listOf(
                                        TranslationSpec(
                                            ru,
                                            "Привет, {${'$'}name}",
                                            TranslationReview.APPROVED,
                                            sourceDigest,
                                            CatalogDigests.review(sourceDigest, ru, "Привет, {${'$'}name}"),
                                        ),
                                    ),
                            ),
                        ),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot
    }
}
