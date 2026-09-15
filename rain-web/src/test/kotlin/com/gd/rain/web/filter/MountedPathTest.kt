package com.gd.rain.web.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.web.MockHttpServletRequest

/** A filter sees the path the handler mapping sees, however the request spells it. */
class MountedPathTest {
    @ParameterizedTest(name = "{1}")
    @CsvSource(
        "/api/v1/auth/login,              plain",
        "/api/v1/%61uth/login,            a percent-encoded letter in a segment",
        "/api/v1/auth/%6Cogin,            a percent-encoded letter after it",
        "/%61pi/v1/auth/login,            a percent-encoded letter before it",
        "/api/v1/auth;jsessionid=x/login, a matrix parameter the matcher drops",
        "/api/v1/%61uth;v=1/%6Cogin,      both at once",
    )
    fun `every spelling the router resolves resolves the same way here`(
        uri: String,
        spelling: String,
    ) {
        assertThat(MockHttpServletRequest("POST", uri).mountedPath()).describedAs(spelling).isEqualTo("/api/v1/auth/login")
    }

    @Test
    fun `the context path is not part of the route`() {
        val request = MockHttpServletRequest("POST", "/app/api/v1/auth/login").apply { contextPath = "/app" }

        assertThat(request.mountedPath()).isEqualTo("/api/v1/auth/login")
    }

    @Test
    fun `a path that only shares a prefix stays itself`() {
        assertThat(MockHttpServletRequest("POST", "/api/v1/authors/7").mountedPath()).isEqualTo("/api/v1/authors/7")
    }

    @Test
    fun `the answer is computed once per request`() {
        val request = MockHttpServletRequest("POST", "/api/v1/%61uth/login")

        val first = request.mountedPath()

        assertThat(request.mountedPath()).isSameAs(first)
    }

    /** RFC 9110 §9.1: method names are case sensitive, so `get` is not the safe method `GET`. */
    @ParameterizedTest
    @CsvSource("GET, true", "HEAD, true", "OPTIONS, true", "get, false", "POST, false", "TRACE, false")
    fun `the transport counts exactly three methods safe`(
        method: String,
        safe: Boolean,
    ) {
        assertThat(isSafeMethod(method)).isEqualTo(safe)
    }
}
