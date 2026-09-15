package com.gd.rain.web.filter

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.web.problemCode
import com.gd.rain.web.problemWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequestWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.util.unit.DataSize
import java.util.concurrent.atomic.AtomicReference

/** 413 `too_large`: an announced body before the handler runs, a hidden one while it is read. */
class BodyLimitFilterTest {
    private val filter = BodyLimitFilter(DataSize.ofBytes(LIMIT.toLong()), problemWriter(), multipartParsedByContainer = true)

    @Test
    fun `an announced body over the limit is refused before the handler runs`() {
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(posting(ByteArray(LIMIT + 1)), response, chain)

        assertThat(response.status).isEqualTo(413)
        assertThat(response.problemCode()).isEqualTo("too_large")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `a body at the limit is passed on`() {
        val chain = MockFilterChain()

        filter.doFilter(posting(ByteArray(LIMIT)), MockHttpServletResponse(), chain)

        assertThat(chain.request).isNotNull()
    }

    @Test
    fun `a body that hides its size is cut while it is read, and the request says so`() {
        val request = posting(ByteArray(LIMIT + 64))
        val raised = AtomicReference<Throwable?>()

        filter.doFilter(
            unannounced(request),
            MockHttpServletResponse(),
            FilterChain { bounded, _ ->
                raised.set(
                    runCatching {
                        bounded.inputStream.readAllBytes()
                    }.exceptionOrNull(),
                )
            },
        )

        assertThat(raised.get()).isInstanceOfSatisfying(Fault::class.java) { assertThat(it.kind).isEqualTo(FaultKind.TOO_LARGE) }
        assertThat(TransportRefusal.of(request)?.kind).isEqualTo(FaultKind.TOO_LARGE)
    }

    @Test
    fun `the reader counts the same bytes the stream does`() {
        val request = posting("x".repeat(LIMIT + 8).toByteArray())
        val raised = AtomicReference<Throwable?>()

        filter.doFilter(
            unannounced(request),
            MockHttpServletResponse(),
            FilterChain { bounded, _ ->
                raised.set(
                    runCatching {
                        bounded.reader.readText()
                    }.exceptionOrNull(),
                )
            },
        )

        assertThat(raised.get()).isInstanceOf(Fault::class.java)
    }

    @Test
    fun `a multipart request is left to the container's multipart limits`() {
        val chain = MockFilterChain()
        val request = posting(ByteArray(LIMIT * 4)).apply { contentType = "multipart/form-data; boundary=x" }

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertThat(chain.request).isSameAs(request)
    }

    @Test
    fun `without multipart parsing a multipart body is counted like any other`() {
        val counting = BodyLimitFilter(DataSize.ofBytes(LIMIT.toLong()), problemWriter(), multipartParsedByContainer = false)
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        counting.doFilter(posting(ByteArray(LIMIT * 4)).apply { contentType = "multipart/form-data; boundary=x" }, response, chain)

        assertThat(response.status).isEqualTo(413)
        assertThat(chain.request).isNull()
    }

    private fun posting(body: ByteArray): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/things").apply {
            setContent(body)
            contentType = "application/json"
        }

    private fun unannounced(request: MockHttpServletRequest) =
        object : HttpServletRequestWrapper(request) {
            override fun getContentLengthLong(): Long = -1

            override fun getContentLength(): Int = -1
        }

    private companion object {
        const val LIMIT = 1024
    }
}
