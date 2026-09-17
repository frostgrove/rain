package com.gd.rain.tenancy.web

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRefusal
import com.gd.rain.web.route.FunctionalRoute
import com.gd.rain.web.route.FunctionalRoutes
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.util.ClassUtils
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.AsyncHandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Binds a scope only around one handler invocation. It does not begin a tenant transaction: a
 * service annotation or explicit data-plane callback remains responsible for that later boundary.
 */
public class TenantRouteInterceptor(
    private val declarations: TenantRouteDeclarations,
    private val resolver: TenantRequestScopeResolver,
    resources: List<DeclaresItsOwnTenancy> = emptyList(),
) : AsyncHandlerInterceptor {
    private val resources: List<DeclaresItsOwnTenancy> = resources.toList()

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val declaration =
            when (
                handler
            ) {
                is HandlerMethod -> annotated(request, handler) ?: return true
                is HandlerFunction<*> -> functional(request)
                else -> return true
            }
        TenantContext.requireUnbound()
        val scope = resolve(declaration, ServletTenantRequestContext(request))
        scope?.let { bind(request, it) }
        return true
    }

    private fun annotated(
        request: HttpServletRequest,
        handler: HandlerMethod,
    ): TenantEndpointDeclaration? {
        val pattern =
            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
                ?: error("a request mapped to ${handler.shortLogMessage} carries no matched pattern")
        return when (
            val lookup =
                declarations.of(
                    handler.beanType,
                    handler,
                    handler.bean as? DeclaresItsOwnTenancy,
                    request.method,
                    pattern,
                )
        ) {
            is TenantRouteLookup.Declared -> lookup.declaration
            is TenantRouteLookup.Exempt -> null
            TenantRouteLookup.Undeclared -> error("${request.method} $pattern declares no tenancy; start-up verification refuses it")
        }
    }

    private fun functional(request: HttpServletRequest): TenantEndpointDeclaration {
        val pattern =
            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
                ?: error("a request routed to a functional route carries no matched pattern")
        return declarationFor(resources.flatMap(DeclaresItsOwnTenancy::tenantDeclarations), request.method, pattern)
            ?: error("${request.method} $pattern is a functional route that declares no tenancy; start-up verification refuses it")
    }

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ): Unit = Unit

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        close(request)
    }

    /** An async dispatch must leave its worker clean before the container returns that worker to its pool. */
    override fun afterConcurrentHandlingStarted(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ) {
        close(request)
    }

    private fun resolve(
        declaration: TenantEndpointDeclaration,
        context: ServletTenantRequestContext,
    ): com.gd.rain.tenancy.TenantScope? =
        when (val surface = declaration.surface) {
            is TenantRouteSurface.Tenant -> {
                when (val result = resolver.resolve(context, surface.operation.operation())) {
                    TenantRequestScope.Absent -> throw TenantRefusal.Required
                    is TenantRequestScope.Present -> result.scope
                }
            }

            TenantRouteSurface.Central -> {
                when (resolver.resolve(context, TenantOperation.READ)) {
                    TenantRequestScope.Absent -> null
                    is TenantRequestScope.Present -> throw TenantRefusal.CentralRouteSelected
                }
            }

            is TenantRouteSurface.Universal -> {
                when (val result = resolver.resolve(context, surface.operation.operation())) {
                    TenantRequestScope.Absent -> null
                    is TenantRequestScope.Present -> result.scope
                }
            }
        }

    private fun bind(
        request: HttpServletRequest,
        scope: com.gd.rain.tenancy.TenantScope,
    ) {
        check(request.getAttribute(BINDING_ATTRIBUTE) == null) { "tenant scope is already bound for this servlet dispatch" }
        request.setAttribute(BINDING_ATTRIBUTE, TenantContext.bind(scope))
    }

    private fun close(request: HttpServletRequest) {
        (request.getAttribute(BINDING_ATTRIBUTE) as? TenantContext.TenantBinding)?.let { binding ->
            request.removeAttribute(BINDING_ATTRIBUTE)
            binding.close()
        }
    }

    private companion object {
        const val BINDING_ATTRIBUTE: String = "com.gd.rain.tenancy.web.binding"
    }
}

/**
 * Ensures every mounted annotation-controller route deliberately declares its tenancy semantics.
 * Generated controllers use [DeclaresItsOwnTenancy]; infrastructure mappings have one explicit
 * [TenantSurfaceExemption].
 */
public class TenantServletSurfaceVerifier(
    private val mapping: () -> RequestMappingHandlerMapping?,
    private val declarations: TenantRouteDeclarations,
    resources: List<DeclaresItsOwnTenancy>,
    private val routes: () -> RouterFunction<*>? = { null },
) : ConfigurationCheck {
    private val resources: List<DeclaresItsOwnTenancy> = resources.toList()

    override fun problems(): List<ConfigurationProblem> {
        val found = mutableListOf<ConfigurationProblem>()
        val mounted = mutableMapOf<String, TenantEndpointDeclaration>()
        resources
            .flatMap(DeclaresItsOwnTenancy::tenantDeclarations)
            .groupBy(TenantEndpointDeclaration::key)
            .filterValues { it.size > 1 }
            .forEach { (key, duplicates) ->
                found += problem(key, ProblemCode.CONTRADICTS, "is declared ${duplicates.size} times")
            }
        entries().forEach { entry ->
            when (val lookup = entry.lookup) {
                is TenantRouteLookup.Exempt -> {}

                TenantRouteLookup.Undeclared -> {
                    found += problem(entry.key, ProblemCode.REQUIRED, "is mounted by ${entry.handler} and declares no tenancy")
                }

                is TenantRouteLookup.Declared -> {
                    if (mounted.put(entry.key, lookup.declaration) != null) {
                        found += problem(entry.key, ProblemCode.CONTRADICTS, "is declared twice")
                    }
                    lookup.declaration.problems().forEach { found += problem(entry.key, ProblemCode.INVALID, it) }
                }
            }
        }
        functionalProblems(mounted).forEach(found::add)
        resources.forEach { resource ->
            resource.tenantDeclarations().filterNot { it.key in mounted }.forEach { declaration ->
                found +=
                    problem(
                        declaration.key,
                        ProblemCode.CONTRADICTS,
                        "is declared by ${ClassUtils.getUserClass(resource).name} and mounts nothing",
                    )
            }
        }
        return found.distinct().sortedBy(ConfigurationProblem::path)
    }

    private fun functionalProblems(mounted: MutableMap<String, TenantEndpointDeclaration>): List<ConfigurationProblem> =
        FunctionalRoutes.read(routes()).mapNotNull { route ->
            when (route) {
                is FunctionalRoute.Readable -> {
                    val declarations =
                        resources
                            .flatMap(DeclaresItsOwnTenancy::tenantDeclarations)
                            .filter { declaration -> declaration.method == route.method && declaration.path == route.path }
                    if (declarations.isEmpty()) {
                        return@mapNotNull problem(route.key, ProblemCode.REQUIRED, "is a functional route and declares no tenancy")
                    }
                    if (declarations.size > 1) return@mapNotNull null
                    val declaration = declarations.single()
                    if (mounted.put(route.key, declaration) != null) {
                        problem(route.key, ProblemCode.CONTRADICTS, "is declared twice")
                    } else {
                        declaration.problems().firstOrNull()?.let { problem(route.key, ProblemCode.INVALID, it) }
                    }
                }

                is FunctionalRoute.Unreadable -> {
                    problem(
                        "functional-route",
                        ProblemCode.INVALID,
                        "cannot be verified: ${route.description}; mount it with a method and a path predicate",
                    )
                }
            }
        }

    private data class Entry(
        val key: String,
        val handler: String,
        val lookup: TenantRouteLookup,
    )

    private fun entries(): List<Entry> {
        val handlers = mapping()?.handlerMethods ?: return emptyList()
        val explicit =
            handlers.keys
                .flatMap { info -> info.methodsCondition.methods.flatMap { method -> info.patternValues.map { "${method.name} $it" } } }
                .toSet()
        return handlers
            .flatMap { (info, handler) ->
                val resource = resources.firstOrNull { ClassUtils.getUserClass(it) == ClassUtils.getUserClass(handler.beanType) }
                val stated = info.methodsCondition.methods.map(RequestMethod::name)
                info.patternValues.flatMap { path ->
                    val methods = stated.ifEmpty { RequestMethod.entries.map(RequestMethod::name).filterNot { "$it $path" in explicit } }
                    methods.map { method ->
                        Entry(
                            "$method $path",
                            handler.toString(),
                            declarations.of(handler.beanType, handler, resource, method, path),
                        )
                    }
                }
            }.sortedBy(Entry::key)
    }

    private fun problem(
        key: String,
        code: ProblemCode,
        message: String,
    ): ConfigurationProblem = ConfigurationProblem("tenancy.surface:$key", code, message)
}
