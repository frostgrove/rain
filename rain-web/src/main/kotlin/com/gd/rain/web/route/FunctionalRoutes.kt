package com.gd.rain.web.route

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

/** One route of a `RouterFunction`, as a declared surface verifier reads it. */
public sealed interface FunctionalRoute {
    /** A route answering [method] at [path]. */
    public data class Readable(
        public val method: String,
        public val path: String,
    ) : FunctionalRoute {
        public val key: String get() = "$method $path"
    }

    /** A route whose predicate cannot be reduced to methods and one path. */
    public data class Unreadable(
        public val description: String,
    ) : FunctionalRoute
}

/**
 * Reads the inspectable routes of a `RouterFunction` without assigning policy to them. Access,
 * tenancy and other modules use this one parser to verify their separate route declarations.
 */
public object FunctionalRoutes {
    private val everyMethod: Set<String> = HttpMethod.values().map(HttpMethod::name).toSet()

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
        val methods = parts.mapNotNull(Parts::methods).fold(everyMethod) { answered, level -> answered intersect level }
        val path = paths.joinToString("")
        return methods.sorted().map { FunctionalRoute.Readable(it, path) }
    }

    /** The readable conjunction of one predicate, or null when its actual surface cannot be proved. */
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
