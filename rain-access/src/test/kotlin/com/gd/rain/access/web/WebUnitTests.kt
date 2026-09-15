package com.gd.rain.access.web

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.CredentialDelivery
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.usecase.IssuedCredentials
import com.gd.rain.access.internal.web.CanonicalIds
import com.gd.rain.access.internal.web.CredentialCookies
import com.gd.rain.access.internal.web.CredentialSurface
import com.gd.rain.access.internal.web.CredentialThrottleFilter
import com.gd.rain.access.internal.web.DeliveryDecision
import com.gd.rain.access.internal.web.JsonOnlyFilter
import com.gd.rain.access.internal.web.PresentedToken
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.START
import com.gd.rain.access.support.problemWriter
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.path
import com.gd.rain.test.MutableClock
import com.gd.rain.web.limit.TokenBucketThrottle
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

private val READER = JsonMapper.builder().build()

private fun MockHttpServletResponse.code(): String = READER.readTree(contentAsByteArray)["code"].asString()

class CredentialCookiesTest {
    private val clock = MutableClock(START)
    private val cookies = CredentialCookies("/api/auth/refresh", clock)
    private val issued =
        IssuedCredentials(
            SubjectRef(AGENT, UUID.randomUUID()),
            UUID.randomUUID(),
            START,
            "the-access-token",
            START.plusSeconds(300),
            "1.x.y",
            START.plus(Duration.ofDays(30)),
        )

    @Test
    fun `the access cookie is host-scoped on every path and the refresh cookie lives on the refresh route only`() {
        val written = cookies.issue(issued)

        assertThat(written.map { it.name to it.path }).containsExactly(
            "__Host-rain-access" to "/",
            "__Secure-rain-refresh" to "/api/auth/refresh",
        )
        assertThat(written).allMatch { it.isHttpOnly && it.isSecure && it.sameSite == "Strict" }
        assertThat(written.map { it.maxAge }).containsExactly(Duration.ofSeconds(300), Duration.ofDays(30))
    }

    @Test
    fun `clearing writes both empty with a zero lifetime`() {
        assertThat(cookies.clear()).allMatch { it.value.isEmpty() && it.maxAge.isZero && it.toString().contains("Max-Age=0") }
    }
}

class DeliveryDecisionTest {
    private fun request(vararg headers: String) =
        MockHttpServletRequest().apply { headers.forEach { addHeader(DeliveryDecision.HEADER, it) } }

    @Test
    fun `a fixed delivery ignores the header`() {
        assertThat(DeliveryDecision.forSignIn(CredentialDelivery.COOKIES, request("body"))).isEqualTo(CredentialDelivery.COOKIES)
        assertThat(DeliveryDecision.forSignIn(CredentialDelivery.BODY, request("cookies"))).isEqualTo(CredentialDelivery.BODY)
    }

    @Test
    fun `both honours a header naming cookies or body`() {
        assertThat(DeliveryDecision.forSignIn(CredentialDelivery.BOTH, request("cookies"))).isEqualTo(CredentialDelivery.COOKIES)
        assertThat(DeliveryDecision.forSignIn(CredentialDelivery.BOTH, request("body"))).isEqualTo(CredentialDelivery.BODY)
    }

    @Test
    fun `both refuses an absent, repeated or unknown header instead of choosing one`() {
        listOf(request(), request("body", "body"), request("both"), request("Cookies")).forEach { refused ->
            assertThatThrownBy { DeliveryDecision.forSignIn(CredentialDelivery.BOTH, refused) }
                .matches(
                    { (it as Fault).code == AccessErrorCodes.INVALID_DELIVERY && it.kind == FaultKind.BAD_REQUEST },
                    "invalid delivery",
                )
        }
    }
}

class PresentedTokenTest {
    private fun request(
        cookies: List<String> = emptyList(),
        authorization: List<String> = emptyList(),
    ) = MockHttpServletRequest().apply {
        if (cookies.isNotEmpty()) setCookies(*cookies.map { Cookie(CredentialCookies.ACCESS, it) }.toTypedArray())
        authorization.forEach { addHeader("Authorization", it) }
    }

    @Test
    fun `each delivery reads only the channel it opens`() {
        assertThat(PresentedToken(CredentialDelivery.COOKIES).of(request(cookies = listOf("c")))).isEqualTo("c")
        assertThat(PresentedToken(CredentialDelivery.COOKIES).of(request(authorization = listOf("Bearer h")))).isNull()
        assertThat(PresentedToken(CredentialDelivery.BODY).of(request(authorization = listOf("bearer h")))).isEqualTo("h")
        assertThat(PresentedToken(CredentialDelivery.BODY).of(request(cookies = listOf("c")))).isNull()
    }

    @Test
    fun `two values in a channel, or a token in both channels, is refused`() {
        val both = PresentedToken(CredentialDelivery.BOTH)

        listOf(
            request(cookies = listOf("a", "b")),
            request(authorization = listOf("Bearer a", "Bearer a")),
            request(listOf("c"), listOf("Bearer h")),
        ).forEach { refused ->
            assertThatThrownBy { both.of(refused) }.matches({ (it as Fault).code == RainErrorCodes.UNAUTHENTICATED }, "401")
        }
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = ["Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "Bearer two words", "token"])
    fun `a header that is not one bearer token presents nothing`(header: String) {
        assertThat(PresentedToken(CredentialDelivery.BOTH).of(request(authorization = listOf(header)))).isNull()
    }
}

class CredentialGateFiltersTest {
    private val surface = CredentialSurface("/api/auth")

    private fun post(
        path: String,
        type: String?,
        body: String?,
    ) = MockHttpServletRequest("POST", path).apply {
        type?.let { addHeader("Content-Type", it) }
        body?.let {
            setContent(it.toByteArray())
            addHeader("Content-Length", it.toByteArray().size.toString())
        }
        remoteAddr = "203.0.113.7"
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = ["application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "text/plain;charset=UTF-8", "not a media type"],
    )
    fun `a credential route takes nothing a form can send`(type: String) {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        JsonOnlyFilter(surface, problemWriter()).doFilter(post("/api/auth/agent/login", type, "identifier=a"), response, chain)

        assertThat(response.status).isEqualTo(415)
        assertThat(response.code()).isEqualTo("unsupported_media_type")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `JSON, no body, a read and a route outside the surface pass`() {
        val filter = JsonOnlyFilter(surface, problemWriter())
        val passing =
            listOf(
                post("/api/auth/agent/login", "application/json; charset=utf-8", "{}"),
                post("/api/auth/logout", null, null),
                MockHttpServletRequest("GET", "/api/auth/me").apply { addHeader("Content-Type", "text/plain") },
                post("/api/tickets", "multipart/form-data; boundary=x", "part"),
            )

        passing.forEach { request ->
            val chain = MockFilterChain()
            filter.doFilter(request, MockHttpServletResponse(), chain)
            assertThat(chain.request).describedAs(request.requestURI).isNotNull()
        }
    }

    @Test
    fun `a chunked body that hides its length is still asked its type`() {
        val response = MockHttpServletResponse()
        val request = post("/api/auth/agent/login", "text/plain", null).apply { addHeader("Transfer-Encoding", "chunked") }

        JsonOnlyFilter(surface, problemWriter()).doFilter(request, response, MockFilterChain())

        assertThat(response.status).isEqualTo(415)
    }

    @Test
    fun `a storm of unsafe credential requests from one address is 429 with Retry-After, and reads and other routes are not charged`() {
        val filter =
            CredentialThrottleFilter(
                surface,
                TokenBucketThrottle(perMinute = 60, burst = 2, callers = 100, clock = { 0L }),
                problemWriter(),
            )

        val statuses =
            List(3) {
                MockHttpServletResponse().also {
                    filter.doFilter(
                        post("/api/auth/agent/login", "application/json", "{}"),
                        it,
                        MockFilterChain(),
                    )
                }
            }
        val read =
            MockHttpServletResponse().also {
                filter.doFilter(
                    MockHttpServletRequest("GET", "/api/auth/me").apply {
                        remoteAddr =
                            "203.0.113.7"
                    },
                    it,
                    MockFilterChain(),
                )
            }
        val elsewhere =
            MockHttpServletResponse().also {
                filter.doFilter(
                    post("/api/tickets", "application/json", "{}"),
                    it,
                    MockFilterChain(),
                )
            }

        assertThat(statuses.map { it.status }).containsExactly(200, 200, 429)
        assertThat(statuses.last().getHeader("Retry-After")).isEqualTo("1")
        assertThat(statuses.last().code()).isEqualTo("too_many_requests")
        assertThat(read.status).isEqualTo(200)
        assertThat(elsewhere.status).isEqualTo(200)
    }
}

/** Gap 23: an id is read in its canonical form only; anything else is 400 invalid_id, never a 500 or a different id. */
class CanonicalUuidParsingTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "1-1-1-1-1",
            "018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c0",
            "018f5b3a2c2d7e4f8a9b0c1d2e3f4a5c",
            "{018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c}",
            " 018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c",
            "018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5g",
            "",
        ],
    )
    fun `a non-canonical spelling is not an id`(text: String) {
        assertThat(CanonicalIds.parse(text)).isNull()
        val refusal = runCatching { CanonicalIds.required(text, "sessionId") }.exceptionOrNull() as Fault
        assertThat(refusal.kind).isEqualTo(FaultKind.BAD_REQUEST)
        assertThat(refusal.code).isEqualTo(RainErrorCodes.INVALID_ID)
        assertThat(refusal.violations.single().path).isEqualTo(path("sessionId"))
    }

    @Test
    fun `the canonical spelling in either case is the id`() {
        val id = UUID.fromString("018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c")

        assertThat(CanonicalIds.parse("018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c")).isEqualTo(id)
        assertThat(CanonicalIds.parse("018F5B3A-2C2D-7E4F-8A9B-0C1D2E3F4A5C")).isEqualTo(id)
    }
}
