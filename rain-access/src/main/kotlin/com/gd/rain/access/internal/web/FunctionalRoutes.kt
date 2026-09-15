package com.gd.rain.access.internal.web

import org.springframework.core.io.Resource
import org.springframework.http.HttpMethod
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerRequest
import java.util.Optional
import java.util.function.Function

/** One route of a `RouterFunction`, as the access surface verification reads it. */
public sealed interface FunctionalRoute {
    /** A route answering [method] at [path]. */
    public data class Readable(
        public val method: String,
        public val path: String,
    ) : FunctionalRoute {
        public val key: String get() = "$method $path"
    }

    /** A route whose request predicate does not reduce to methods and one path, described as Spring prints it. */
    public data class Unreadable(
        public val description: String,
    ) : FunctionalRoute
}

/**
 * Reads the routes of a `RouterFunction` for the access surface verification.
 *
 * A route is readable when its predicate, joined with the predicates of the routes it is nested in, is a conjunction of
 * at most one method predicate and one path predicate per level (nested paths joined in order), plus any predicates
 * that only narrow a request — headers such as content type and accept, query parameters, path extensions, versions.
 * A route with no method predicate answers every method. A disjunction, a negation, a resource route, a route with no
 * path and a predicate or router function Spring cannot describe are unreadable: what they answer is not decided here,
 * and the verification refuses them.
 */
public object FunctionalRoutes {
    private val EVERY_METHOD: Set<String> = HttpMethod.values().map(HttpMethod::name).toSet()

    public fun read(function: RouterFunction<*>?): List<FunctionalRoute> {
        if (function == null) return emptyList()
        val found = mutableListOf<FunctionalRoute>()
        val nesting = ArrayDeque<Parts?>()
        function.accept(
            object : RouterFunctions.Visitor {
                override fun startNested(predicate: RequestPredicate) {
                    nesting.addLast(partsOf(predicate))
                }

                override fun endNested(predicate: RequestPredicate) {
                    nesting.removeLast()
                }

                override fun route(
                    predicate: RequestPredicate,
                    handlerFunction: HandlerFunction<*>,
                ) {
                    found += routesOf(nesting.toList() + partsOf(predicate), predicate)
                }

                override fun resources(lookupFunction: Function<ServerRequest, Optional<Resource>>) {
                    found += FunctionalRoute.Unreadable("a resource route")
                }

                override fun attributes(attributes: Map<String, Any>) = Unit

                override fun unknown(routerFunction: RouterFunction<*>) {
                    found += FunctionalRoute.Unreadable(routerFunction.toString())
                }
            },
        )
        return found
    }

    private class Parts(
        val methods: Set<String>?,
        val path: String?,
    )

    private fun routesOf(
        levels: List<Parts?>,
        predicate: RequestPredicate,
    ): List<FunctionalRoute> {
        val parts = levels.filterNotNull()
        if (parts.size != levels.size) return listOf(FunctionalRoute.Unreadable(predicate.toString()))
        val paths = parts.mapNotNull(Parts::path)
        if (paths.isEmpty()) return listOf(FunctionalRoute.Unreadable("$predicate names no path"))
        val methods = parts.mapNotNull(Parts::methods).fold(EVERY_METHOD) { answered, level -> answered intersect level }
        val path = paths.joinToString("")
        return methods.sorted().map { FunctionalRoute.Readable(it, path) }
    }

    /** The methods and path of one predicate, or null when it holds anything but a conjunction of the readable parts. */
    private fun partsOf(predicate: RequestPredicate): Parts? {
        var methods: Set<String>? = null
        var path: String? = null
        var readable = true
        predicate.accept(
            object : RequestPredicates.Visitor {
                override fun method(httpMethods: Set<HttpMethod>) {
                    if (methods != null) readable = false
                    methods = httpMethods.map(HttpMethod::name).toSet()
                }

                override fun path(pattern: String) {
                    if (path != null) readable = false
                    path = pattern
                }

                // Spring deprecates path-extension predicates; a route that still uses one is read, the extension only narrows it.
                @Deprecated("overrides Spring's deprecated RequestPredicates.Visitor.pathExtension")
                override fun pathExtension(extension: String) = Unit

                override fun header(
                    name: String,
                    value: String,
                ) = Unit

                override fun param(
                    name: String,
                    value: String,
                ) = Unit

                override fun version(version: String) = Unit

                override fun startAnd() = Unit

                override fun and() = Unit

                override fun endAnd() = Unit

                override fun startOr() {
                    readable = false
                }

                override fun or() {
                    readable = false
                }

                override fun endOr() {
                    readable = false
                }

                override fun startNegate() {
                    readable = false
                }

                override fun endNegate() {
                    readable = false
                }

                override fun unknown(predicate: RequestPredicate) {
                    readable = false
                }
            },
        )
        return if (readable) Parts(methods, path) else null
    }
}
