package com.gd.rain.web.filter

import com.gd.rain.web.problemCode
import com.gd.rain.web.problemWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** CORS hides the answer from a foreign page; this refuses the write. */
class CrossSiteFilterTest {
    private val filter = CrossSiteFilter(listOf(ORIGIN), problemWriter())

    @Test
    fun `a write driven by another site never reaches the handler`() {
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(write().apply { addHeader("Origin", "https://evil.example") }, response, chain)

        assertThat(response.status).isEqualTo(403)
        assertThat(response.problemCode()).isEqualTo("cross_site")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `a write from an allowed origin is let through, whatever the case of its spelling`() {
        listOf(ORIGIN, "HTTPS://App.Example").forEach { origin ->
            val chain = MockFilterChain()
            val request = write().apply { addHeader("Origin", origin) }

            filter.doFilter(request, MockHttpServletResponse(), chain)

            assertThat(chain.request).describedAs(origin).isSameAs(request)
        }
    }

    @Test
    fun `a write with no browser headers is let through because no page drove it`() {
        val chain = MockFilterChain()
        val request = write()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertThat(chain.request).isSameAs(request)
    }

    @ParameterizedTest
    @ValueSource(strings = ["same-origin", "none"])
    fun `fetch metadata naming this site lets a write without an origin through`(site: String) {
        val chain = MockFilterChain()
        val request = write().apply { addHeader(CrossSiteFilter.FETCH_SITE, site) }

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertThat(chain.request).isSameAs(request)
    }

    @ParameterizedTest
    @ValueSource(strings = ["cross-site", "same-site"])
    fun `fetch metadata naming another site refuses a write without an origin`(site: String) {
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(write().apply { addHeader(CrossSiteFilter.FETCH_SITE, site) }, response, chain)

        assertThat(response.status).isEqualTo(403)
        assertThat(chain.request).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET", "HEAD", "OPTIONS"])
    fun `a safe method is not guarded`(method: String) {
        val chain = MockFilterChain()
        val request = MockHttpServletRequest(method, "/read").apply { addHeader("Origin", "https://evil.example") }

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertThat(chain.request).isSameAs(request)
    }

    @Test
    fun `a deployment that allows every origin allows every write`() {
        val chain = MockFilterChain()
        val request = write().apply { addHeader("Origin", "https://evil.example") }

        CrossSiteFilter(listOf("*"), problemWriter()).doFilter(request, MockHttpServletResponse(), chain)

        assertThat(chain.request).isSameAs(request)
    }

    private fun write() = MockHttpServletRequest("POST", "/write")

    private companion object {
        const val ORIGIN = "https://app.example"
    }
}
