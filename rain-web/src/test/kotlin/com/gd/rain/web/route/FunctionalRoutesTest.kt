package com.gd.rain.web.route

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.Resource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.Optional
import java.util.function.Consumer
import java.util.function.Function

/** Functional routes are read from predicates without attaching access or tenancy policy. */
class FunctionalRoutesTest {
    @Test
    fun `method and path routes, nested paths and narrowing predicates are read`() {
        val routes =
            RouterFunctions
                .route()
                .GET("/a", ok)
                .POST("/b", RequestPredicates.accept(MediaType.APPLICATION_JSON), ok)
                .nest(RequestPredicates.path("/api"), Consumer<RouterFunctions.Builder> { it.GET("/x", ok) })
                .build()

        assertThat(FunctionalRoutes.read(routes)).containsExactly(
            FunctionalRoute.Readable("GET", "/a"),
            FunctionalRoute.Readable("POST", "/b"),
            FunctionalRoute.Readable("GET", "/api/x"),
        )
    }

    @Test
    fun `a route with no method predicate answers every method`() {
        val methods =
            FunctionalRoutes.read(RouterFunctions.route(RequestPredicates.path("/any"), ok)).map {
                (it as FunctionalRoute.Readable).method
            }

        assertThat(methods).containsExactly("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE")
    }

    @Test
    fun `a disjunction, a negation and a route with no path are unreadable`() {
        listOf(
            RouterFunctions.route(RequestPredicates.GET("/a").or(RequestPredicates.GET("/b")), ok),
            RouterFunctions.route(RequestPredicates.GET("/a").negate(), ok),
            RouterFunctions.route(RequestPredicates.method(HttpMethod.GET), ok),
        ).forEach { routes ->
            assertThat(FunctionalRoutes.read(routes))
                .describedAs(routes.toString())
                .singleElement()
                .isInstanceOf(FunctionalRoute.Unreadable::class.java)
        }
    }

    @Test
    fun `null resource and opaque routers stay visibly unreadable`() {
        assertThat(FunctionalRoutes.read(null)).isEmpty()
        assertThat(FunctionalRoute.Readable("GET", "/reports").key).isEqualTo("GET /reports")
        assertThat(
            FunctionalRoutes.read(RouterFunctions.resources(Function<ServerRequest, Optional<Resource>> { Optional.empty() })),
        ).containsExactly(FunctionalRoute.Unreadable("a resource route"))
        assertThat(FunctionalRoutes.read(OpaqueRouter)).containsExactly(FunctionalRoute.Unreadable("opaque router"))
        assertThat(FunctionalRoutes.read(RouterFunctions.route(OpaquePredicate, ok)))
            .containsExactly(FunctionalRoute.Unreadable("opaque predicate"))
    }

    private companion object {
        val ok: HandlerFunction<ServerResponse> = HandlerFunction { ServerResponse.ok().build() }
    }

    private object OpaqueRouter : RouterFunction<ServerResponse> {
        override fun route(request: ServerRequest): Optional<HandlerFunction<ServerResponse>> = Optional.empty()

        override fun accept(visitor: RouterFunctions.Visitor) {
            visitor.unknown(this)
        }

        override fun toString(): String = "opaque router"
    }

    private object OpaquePredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = false

        override fun accept(visitor: RequestPredicates.Visitor) {
            visitor.unknown(this)
        }

        override fun toString(): String = "opaque predicate"
    }
}
