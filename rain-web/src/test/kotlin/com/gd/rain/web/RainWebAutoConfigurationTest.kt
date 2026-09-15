package com.gd.rain.web

import com.gd.rain.core.error.ErrorCodeRegistry
import com.gd.rain.web.autoconfigure.RainProbeAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebErrorAutoConfiguration
import com.gd.rain.web.autoconfigure.RainWebFilterAutoConfiguration
import com.gd.rain.web.error.RainErrorController
import com.gd.rain.web.error.RainExceptionHandler
import com.gd.rain.web.filter.BodyLimitFilter
import com.gd.rain.web.filter.CrossSiteFilter
import com.gd.rain.web.filter.RequestBudgetFilter
import com.gd.rain.web.filter.RequestLogFilter
import com.gd.rain.web.filter.SecurityHeadersFilter
import com.gd.rain.web.filter.WebFilterOrder
import com.gd.rain.web.probe.ProbeController
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.route.EndpointDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.web.filter.CorsFilter

/** What each auto-configuration contributes, and what it leaves to an application. */
class RainWebAutoConfigurationTest {
    class OwnErrorController : ErrorController

    @Test
    fun `a servlet application gets the handler, the error controller, the writer, the probes and every filter in order`() {
        webRunner().run { context ->
            assertThat(context)
                .hasSingleBean(RainExceptionHandler::class.java)
                .hasSingleBean(RainErrorController::class.java)
                .hasSingleBean(ProblemWriter::class.java)
                .hasSingleBean(ProbeController::class.java)
            val orders =
                context.getBeansOfType(FilterRegistrationBean::class.java).values.map {
                    checkNotNull(it.filter).javaClass.name to
                        it.order
                }

            assertThat(orders).containsExactlyInAnyOrder(
                SecurityHeadersFilter::class.java.name to WebFilterOrder.SECURITY_HEADERS,
                RequestLogFilter::class.java.name to WebFilterOrder.REQUEST_LOG,
                RequestBudgetFilter::class.java.name to WebFilterOrder.REQUEST_BUDGET,
                CorsFilter::class.java.name to WebFilterOrder.CORS,
                BodyLimitFilter::class.java.name to WebFilterOrder.BODY_LIMIT,
                CrossSiteFilter::class.java.name to WebFilterOrder.CROSS_SITE,
            )
        }
    }

    @Test
    fun `an application's own error controller replaces rain's`() {
        webRunner().withBean(OwnErrorController::class.java).run { context ->
            assertThat(context).hasSingleBean(ErrorController::class.java).doesNotHaveBean(RainErrorController::class.java)
        }
    }

    @Test
    fun `a process that is not a web application gets the registry and no web beans`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RainWebErrorAutoConfiguration::class.java,
                    RainWebFilterAutoConfiguration::class.java,
                    RainProbeAutoConfiguration::class.java,
                ),
            ).run { context ->
                assertThat(context)
                    .hasSingleBean(ErrorCodeRegistry::class.java)
                    .doesNotHaveBean(RainExceptionHandler::class.java)
                    .doesNotHaveBean(FilterRegistrationBean::class.java)
                    .doesNotHaveBean(ProbeController::class.java)
            }
    }

    @Test
    fun `every registered auto-configuration is one`() {
        val imports =
            checkNotNull(
                javaClass.classLoader.getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"),
            ).readText()
                .lines()
                .filter(String::isNotBlank)

        assertThat(imports).containsExactly(
            RainWebErrorAutoConfiguration::class.java.name,
            RainWebFilterAutoConfiguration::class.java.name,
            RainProbeAutoConfiguration::class.java.name,
        )
        imports.forEach { assertThat(Class.forName(it).isAnnotationPresent(AutoConfiguration::class.java)).describedAs(it).isTrue() }
    }

    @Test
    fun `an endpoint declaration refuses every incoherent combination`() {
        assertThat(EndpointDeclaration("GET", "/things", permissions = listOf("things.read")).problems()).isEmpty()
        assertThat(EndpointDeclaration("GET", "/open", public = true, why = "a probe has no account").problems()).isEmpty()
        assertThat(EndpointDeclaration("GET", "/nothing").problems()).containsExactly("GET /nothing is mounted and declares no access")
        assertThat(EndpointDeclaration("GET", "/both", public = true, authenticated = true, why = "x").problems())
            .containsExactly("GET /both is declared both public and authenticated")
        assertThat(EndpointDeclaration("POST", "/mixed", permissions = listOf("a", " "), public = true).problems()).containsExactly(
            "POST /mixed is declared public and also requires a,  ",
            "POST /mixed declares an empty permission",
        )
        assertThat(EndpointDeclaration("GET", "/me", authenticated = true).problems())
            .containsExactly("GET /me is declared authenticated and says nothing about why")
    }
}
