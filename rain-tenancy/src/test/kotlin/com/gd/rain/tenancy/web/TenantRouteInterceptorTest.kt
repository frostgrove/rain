package com.gd.rain.tenancy.web

import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantCandidate
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantRefusal
import com.gd.rain.tenancy.TenantRequestContext
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolver
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.ServerResponse
import java.security.SecureRandom
import java.time.Instant

class TenantRouteInterceptorTest {
    private val resolution = TenantResolution(TenantRef.of("acme"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
    private val resolver = MutableResolver(resolution, TenantCandidate.Present(resolution, "test"))
    private val authority =
        HmacTenantAuthority(
            "test",
            resolver,
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            random = SecureRandom(),
        )
    private val interceptor =
        TenantRouteInterceptor(
            TenantRouteDeclarations(emptyList()),
            AuthorityTenantRequestScopeResolver(authority, resolver),
        )
    private val controller = Routes()

    @Test
    fun `tenant route binds a verified scope only for its handler completion`() {
        val request = request("/tenant")
        val handler = handler("tenant")

        assertThat(interceptor.preHandle(request, MockHttpServletResponse(), handler)).isTrue()
        assertThat(TenantContext.requireScope().epoch).isEqualTo(TenantEpoch(1))

        interceptor.afterCompletion(request, MockHttpServletResponse(), handler, null)
        assertThatThrownBy(TenantContext::requireScope).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `central route refuses a valid tenant selection instead of silently serving central work`() {
        val request = request("/central")

        assertThatThrownBy { interceptor.preHandle(request, MockHttpServletResponse(), handler("central")) }
            .isSameAs(TenantRefusal.CentralRouteSelected)
        assertThatThrownBy(TenantContext::requireScope).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `universal route permits absence but malformed input cannot fall through to central`() {
        resolver.candidate = TenantCandidate.Absent
        val central = request("/universal")
        val handler = handler("universal")

        assertThat(interceptor.preHandle(central, MockHttpServletResponse(), handler)).isTrue()
        assertThatThrownBy(TenantContext::requireScope).isInstanceOf(IllegalStateException::class.java)

        resolver.candidate = TenantCandidate.Malformed("test")
        assertThatThrownBy { interceptor.preHandle(request("/universal"), MockHttpServletResponse(), handler) }
            .isSameAs(TenantRefusal.Malformed)
    }

    @Test
    fun `async handoff closes the servlet worker binding before it is reused`() {
        val request = request("/tenant")
        val handler = handler("tenant")
        interceptor.preHandle(request, MockHttpServletResponse(), handler)

        interceptor.afterConcurrentHandlingStarted(request, MockHttpServletResponse(), handler)

        assertThatThrownBy(TenantContext::requireScope).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `generic servlet context does not turn an unverified header into a tenant attribute`() {
        val request = MockHttpServletRequest().apply { addHeader("X-Tenant", "acme") }

        assertThat(ServletTenantRequestContext(request).attribute("X-Tenant")).isNull()
    }

    @Test
    fun `functional route binds from its declared tenant surface`() {
        val declaration = TenantEndpointDeclaration("GET", "/functional", TenantRouteSurface.Tenant(TenantRouteOperation.READ))
        val functional =
            TenantRouteInterceptor(
                TenantRouteDeclarations(emptyList()),
                AuthorityTenantRequestScopeResolver(authority, resolver),
                listOf(declarations(declaration)),
            )
        val request = request("/functional")

        assertThat(functional.preHandle(request, MockHttpServletResponse(), ok)).isTrue()
        assertThat(TenantContext.requireScope().ref).isEqualTo(TenantRef.of("acme"))

        functional.afterCompletion(request, MockHttpServletResponse(), ok, null)
        assertThatThrownBy(TenantContext::requireScope).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `functional route with no declaration is refused before a handler starts`() {
        assertThatThrownBy {
            interceptor.preHandle(request("/functional"), MockHttpServletResponse(), ok)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("declares no tenancy")
        assertThatThrownBy(TenantContext::requireScope).isInstanceOf(IllegalStateException::class.java)
    }

    private fun request(path: String): MockHttpServletRequest =
        MockHttpServletRequest("GET", path).apply {
            setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path)
        }

    private fun handler(name: String): HandlerMethod = HandlerMethod(controller, Routes::class.java.getMethod(name))

    private fun declarations(vararg declaration: TenantEndpointDeclaration): DeclaresItsOwnTenancy =
        object : DeclaresItsOwnTenancy {
            override fun tenantDeclarations(): List<TenantEndpointDeclaration> = declaration.toList()
        }

    private companion object {
        val ok: HandlerFunction<ServerResponse> = HandlerFunction { ServerResponse.ok().build() }
    }

    private open class Routes {
        @TenantRoute(TenantRouteOperation.READ)
        fun tenant() = Unit

        @CentralRoute("catalogue endpoint has no tenant model")
        fun central() = Unit

        @UniversalRoute(TenantRouteOperation.READ, "landing page may render tenant-aware content")
        fun universal() = Unit
    }

    private class MutableResolver(
        private val resolution: TenantResolution,
        var candidate: TenantCandidate,
    ) : TenantResolver {
        override fun resolveCurrent(context: TenantRequestContext): TenantCandidate = candidate

        override fun lookup(ref: TenantRef): TenantResolution? = resolution.takeIf { it.ref == ref }
    }
}
