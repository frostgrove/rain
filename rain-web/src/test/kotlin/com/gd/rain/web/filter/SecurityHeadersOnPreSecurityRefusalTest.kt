package com.gd.rain.web.filter

import com.gd.rain.web.config.RainWebProperties
import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.webRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Gap 34: the security headers filter is outermost, so every refusal made before Spring Security — by
 * CORS, the cross-site guard, the body limit, the probe-only gate — carries the headers, as does a
 * refusal the exception handler rendered.
 */
class SecurityHeadersOnPreSecurityRefusalTest {
    @RestController
    class Things {
        @PostMapping("/things")
        fun create(): String = "created"
    }

    @Test
    fun `every pre-security refusal carries the configured headers`() {
        webRunner().withUserConfiguration(Things::class.java).run { context ->
            val mvc = mockMvcOf(context)
            val refusals =
                mapOf(
                    "cross-site write" to mvc.perform(post("/things").header("Origin", "https://evil.example")).andReturn().response,
                    "cors rejection" to mvc.perform(get("/things").header("Origin", "https://evil.example")).andReturn().response,
                    "body over the limit" to
                        mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("x".repeat(2048))).andReturn().response,
                    "handler refusal" to mvc.perform(get("/nothing-here")).andReturn().response,
                )

            refusals.forEach { (refusal, response) ->
                assertThat(response.status).describedAs(refusal).isGreaterThanOrEqualTo(400)
                assertDefaultHeaders(refusal, response)
            }
        }
    }

    @Test
    fun `a worker's probe-only 404 carries the headers`() {
        webRunner().withPropertyValues("rain.runtime.roles=worker").run { context ->
            val response = mockMvcOf(context).perform(get("/things")).andReturn().response

            assertThat(response.status).isEqualTo(404)
            assertDefaultHeaders("probe-only refusal", response)
        }
    }

    @Test
    fun `stating the header map replaces the declared defaults`() {
        webRunner().withPropertyValues("rain.web.security-headers.x-frame-options=SAMEORIGIN").run { context ->
            val response = mockMvcOf(context).perform(get("/nothing-here")).andReturn().response

            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN")
            assertThat(response.getHeader("Content-Security-Policy")).isNull()
        }
    }

    @Test
    fun `the security headers filter is registered before every other rain filter and before Spring Security`() {
        webRunner().run { context ->
            val orders =
                context.getBeansOfType(FilterRegistrationBean::class.java).values.associate {
                    checkNotNull(it.filter).javaClass.name to
                        it.order
                }

            assertThat(orders[SecurityHeadersFilter::class.java.name]).isEqualTo(WebFilterOrder.SECURITY_HEADERS)
            assertThat(orders.filterKeys { it != SecurityHeadersFilter::class.java.name }.values).allSatisfy { order ->
                assertThat(order).isGreaterThan(WebFilterOrder.SECURITY_HEADERS)
            }
            assertThat(WebFilterOrder.CROSS_SITE).isLessThan(SPRING_SECURITY_FILTER_ORDER)
        }
    }

    @Test
    fun `the headers are on the response before anything further in can answer`() {
        val response = MockHttpServletResponse()
        var seen: String? = null

        SecurityHeadersFilter(RainWebProperties.DEFAULT_SECURITY_HEADERS).doFilter(
            MockHttpServletRequest("GET", "/nope"),
            response,
        ) { _, answer -> seen = (answer as MockHttpServletResponse).getHeader("X-Frame-Options") }

        assertThat(seen).isEqualTo("DENY")
    }

    private fun assertDefaultHeaders(
        refusal: String,
        response: MockHttpServletResponse,
    ) {
        RainWebProperties.DEFAULT_SECURITY_HEADERS.forEach { (name, value) ->
            assertThat(response.getHeader(name)).describedAs("%s: %s", refusal, name).isEqualTo(value)
        }
    }

    private companion object {
        /** `SecurityProperties.DEFAULT_FILTER_ORDER` of Spring Boot's security auto-configuration. */
        const val SPRING_SECURITY_FILTER_ORDER = -100
    }
}
