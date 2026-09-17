package com.gd.rain.i18n.web

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageArguments
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec
import com.gd.rain.i18n.bind
import com.gd.rain.i18n.web.autoconfigure.RainI18nWebAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import java.time.ZoneId

class I18nMvcDispatchIT {
    private val en = LocaleTag.parse("en")
    private val ru = LocaleTag.parse("ru")
    private val snapshot = catalog()
    private val message = checkNotNull(snapshot.contract(MessageKey("web", "problem")))
    private val runner =
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    HttpMessageConvertersAutoConfiguration::class.java,
                    DispatcherServletAutoConfiguration::class.java,
                    WebMvcAutoConfiguration::class.java,
                    ErrorMvcAutoConfiguration::class.java,
                    RainWebErrorAutoConfiguration::class.java,
                    RainI18nWebAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(Failures::class.java)
            .withBean(CatalogSnapshotProvider::class.java, { CatalogSnapshotProvider { snapshot } })
            .withBean(
                LocalizedProblemMessages::class.java,
                {
                    LocalizedProblemMessages.build {
                        fault(RainErrorCodes.CONFLICT) { snapshot.bind(message, MessageArguments.of()) }
                        fault(RainErrorCodes.NOT_FOUND) { snapshot.bind(message, MessageArguments.of()) }
                    }
                },
            )

    @Test
    fun `the actual MVC exception path renders through its first i18n request view`() {
        runner.run { context ->
            val response = mvc(context).perform(get("/conflict").header("Accept-Language", "ru")).andReturn().response

            assertThat(response.status).isEqualTo(409)
            assertThat(response.contentAsString).contains("Конфликт")
            assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isEqualTo("ru")
            assertThat(response.getHeader(I18nRequestFilter.VARY)).isEqualTo(I18nRequestFilter.ACCEPT_LANGUAGE)
        }
    }

    @Test
    fun `the actual error dispatch uses i18n before Rain error controller serializes its machine contract`() {
        runner.run { context ->
            val response =
                mvc(context)
                    .perform(
                        get("/error")
                            .header("Accept-Language", "ru")
                            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                            .with { request ->
                                request.dispatcherType = DispatcherType.ERROR
                                request
                            },
                    ).andReturn()
                    .response

            assertThat(response.status).isEqualTo(404)
            assertThat(response.contentAsString).contains("Конфликт")
            assertThat(response.contentAsString).contains("\"code\":\"not_found\"")
            assertThat(response.getHeader(I18nRequestFilter.CONTENT_LANGUAGE)).isEqualTo("ru")
        }
    }

    private fun mvc(context: WebApplicationContext): MockMvc {
        val builder = MockMvcBuilders.webAppContextSetup(context)
        context
            .getBeansOfType(FilterRegistrationBean::class.java)
            .values
            .sortedBy(FilterRegistrationBean<*>::getOrder)
            .forEach { registration -> builder.addFilter<DefaultMockMvcBuilder>(checkNotNull(registration.filter)) }
        return builder.build()
    }

    private fun catalog(): CatalogSnapshot {
        val source = "Conflict"
        val sourceDigest =
            com.gd.rain.i18n.CatalogDigests
                .source(com.gd.rain.i18n.Mf2Profile.ID, en, source, "localized problem")
        return (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("web-mvc", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en, ru), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("web", "problem"),
                                1,
                                source,
                                "localized problem",
                                translations =
                                    listOf(
                                        TranslationSpec(
                                            ru,
                                            "Конфликт",
                                            TranslationReview.APPROVED,
                                            sourceDigest,
                                            com.gd.rain.i18n.CatalogDigests
                                                .review(sourceDigest, ru, "Конфликт"),
                                        ),
                                    ),
                            ),
                        ),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot
    }

    @RestController
    class Failures {
        @GetMapping("/conflict")
        fun conflict(): String = throw Fault.conflict()
    }
}
