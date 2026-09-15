package com.gd.rain.access.internal.web

import com.gd.rain.access.GrantsLookup
import com.gd.rain.access.SurfaceExemption
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.web.route.Access
import com.gd.rain.web.route.DeclaresItsOwnAccess
import com.gd.rain.web.route.EndpointDeclaration
import com.gd.rain.web.route.MountsItsOwnSurface
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.util.ClassUtils
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.pattern.PathPattern
import java.util.concurrent.ConcurrentHashMap

/** What a handler declares about its access. */
public sealed interface DeclarationLookup {
    public data class Declared(
        public val declaration: EndpointDeclaration,
    ) : DeclarationLookup

    public data class Exempt(
        public val exemption: SurfaceExemption,
    ) : DeclarationLookup

    public data object Undeclared : DeclarationLookup
}

/**
 * Where a handler's declaration comes from, for both the start-up verification and the enforcement of each request: a
 * [SurfaceExemption] for the handler's type, else its `@Access` (on the method, else on the class, composed annotations
 * included), else — for a handler that [DeclaresItsOwnAccess] — its declaration for the method and pattern.
 */
public class AccessDeclarations(
    private val exemptions: List<SurfaceExemption>,
) {
    private val derived = ConcurrentHashMap<DeclaresItsOwnAccess, Map<String, EndpointDeclaration>>()

    public fun of(
        handlerType: Class<*>,
        handler: HandlerMethod,
        resource: DeclaresItsOwnAccess?,
        method: String,
        pattern: String,
    ): DeclarationLookup {
        val userType = ClassUtils.getUserClass(handlerType)
        exemptions.firstOrNull { it.handlerType.isAssignableFrom(userType) }?.let { return DeclarationLookup.Exempt(it) }
        annotationOf(handler, userType)?.let { return DeclarationLookup.Declared(EndpointDeclaration.of(method, pattern, it)) }
        val declared =
            resource?.let { owner ->
                derived.computeIfAbsent(owner) { it.accessDeclarations().associateBy(EndpointDeclaration::key) }
            }
        return declared?.get("$method $pattern")?.let(DeclarationLookup::Declared) ?: DeclarationLookup.Undeclared
    }

    public fun annotationOf(
        handler: HandlerMethod,
        userType: Class<*>,
    ): Access? =
        AnnotatedElementUtils.findMergedAnnotation(handler.method, Access::class.java)
            ?: AnnotatedElementUtils.findMergedAnnotation(userType, Access::class.java)
}

/**
 * Enforces each request's declaration before its handler runs: public passes; authenticated needs a principal
 * (`401 unauthenticated`); permissions need a principal holding every named code (`403 forbidden`), asked in one bounded
 * question per request. A request mapping that declares nothing never started — the surface verification refused it —
 * and reaching one here is an internal failure, not a pass.
 */
public class AccessEnforcementInterceptor(
    private val declarations: AccessDeclarations,
    private val grants: GrantsLookup,
    /** The declarations of functional routes by `METHOD path`, from every `MountsItsOwnSurface`; read once, on first use. */
    functional: () -> Map<String, EndpointDeclaration>,
) : HandlerInterceptor {
    private val functional: Map<String, EndpointDeclaration> by lazy(functional)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val declaration =
            when (handler) {
                is HandlerMethod -> annotated(request, handler) ?: return true
                is HandlerFunction<*> -> functionalDeclaration(request)
                else -> return true
            }
        enforce(declaration, request)
        return true
    }

    /** The declaration of a request mapping; null when a [SurfaceExemption] exempts its handler. */
    private fun annotated(
        request: HttpServletRequest,
        handler: HandlerMethod,
    ): EndpointDeclaration? {
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        checkNotNull(pattern) { "a request mapped to ${handler.shortLogMessage} carries no matched pattern" }
        return when (
            val lookup =
                declarations.of(
                    handler.beanType,
                    handler,
                    handler.bean as? DeclaresItsOwnAccess,
                    request.method,
                    pattern,
                )
        ) {
            is DeclarationLookup.Exempt -> null
            is DeclarationLookup.Declared -> lookup.declaration
            DeclarationLookup.Undeclared -> error("${request.method} $pattern declares no access; the start-up verification refuses that")
        }
    }

    /**
     * The declaration of a functional route, by the pattern the route matched — which `RouterFunctionMapping` records on
     * the request as its best matching pattern, as a request mapping's is, once it has taken the router's own attribute
     * off. A `GET` route also answers `HEAD` (RFC 9110 §9.3.2), so a `HEAD` request with no `HEAD` declaration of its own
     * is held to the `GET` declaration.
     */
    private fun functionalDeclaration(request: HttpServletRequest): EndpointDeclaration {
        val pattern =
            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
                ?: error("a request routed to a functional route carries no matched pattern")
        return functional["${request.method} $pattern"]
            ?: functional["GET $pattern"]?.takeIf { request.method == "HEAD" }
            ?: error("${request.method} $pattern is a functional route that declares no access; the start-up verification refuses that")
    }

    /** A caller that presented a credential which authenticated nobody is refused for the reason the filter found. */
    private fun enforce(
        declaration: EndpointDeclaration,
        request: HttpServletRequest,
    ) {
        if (declaration.public) return
        val principal =
            grants.principalOf()
                ?: throw (request.getAttribute(AccessAuthenticationFilter.REFUSAL_ATTRIBUTE) as? Fault)
                    ?: Fault(FaultKind.UNAUTHORIZED, RainErrorCodes.UNAUTHENTICATED, "this route needs an authenticated caller")
        val required = declaration.permissions.toSet()
        if (required.isNotEmpty() && grants.heldBy(principal.subject, required) != required) throw Fault.forbidden()
    }
}

/**
 * The start-up verification that the mounted surface and the declared surface are one: every request mapping declares
 * its access or is exempted by a [SurfaceExemption]; every functional route ([FunctionalRoutes]) is declared by a
 * `MountsItsOwnSurface`, and one the verification cannot read is refused; a mapping without a method stands for every method no other mapping
 * of its pattern names; nothing is declared twice or declared without being mounted; every declaration is well formed;
 * and every permission a declaration names is one a `ModuleGrants` declares.
 */
public class AccessSurfaceVerifier(
    private val mapping: () -> RequestMappingHandlerMapping?,
    private val routes: () -> RouterFunction<*>?,
    private val declarations: AccessDeclarations,
    private val resources: List<DeclaresItsOwnAccess>,
    private val selfMounted: List<MountsItsOwnSurface>,
    private val declaredCodes: () -> Set<String>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val found = mutableListOf<ConfigurationProblem>()
        val codes = declaredCodes()
        val mounted = mutableMapOf<String, EndpointDeclaration>()
        entries().forEach { entry ->
            when (val lookup = entry.lookup) {
                is DeclarationLookup.Exempt -> {
                    return@forEach
                }

                DeclarationLookup.Undeclared -> {
                    found += problem(entry.key, ProblemCode.REQUIRED, "is mounted by ${entry.handler} and declares no access")
                }

                is DeclarationLookup.Declared -> {
                    if (mounted.put(entry.key, lookup.declaration) != null) {
                        found += problem(entry.key, ProblemCode.CONTRADICTS, "is declared twice")
                    }
                    found += declarationProblems(lookup.declaration, codes)
                }
            }
        }
        resources.forEach { resource ->
            resource.accessDeclarations().filterNot { it.key in mounted }.forEach {
                found +=
                    problem(it.key, ProblemCode.CONTRADICTS, "is declared by ${ClassUtils.getUserClass(resource).name} and mounts nothing")
            }
        }
        selfMounted.flatMap(MountsItsOwnSurface::mountedDeclarations).forEach { declaration ->
            if (mounted.put(declaration.key, declaration) !=
                null
            ) {
                found += problem(declaration.key, ProblemCode.CONTRADICTS, "is declared twice")
            }
            found += declarationProblems(declaration, codes)
        }
        found += functionalProblems()
        return found
    }

    private fun functionalProblems(): List<ConfigurationProblem> {
        val declared = selfMounted.flatMap(MountsItsOwnSurface::mountedDeclarations).map(EndpointDeclaration::key).toSet()
        return FunctionalRoutes.read(routes()).mapNotNull { route ->
            when (route) {
                is FunctionalRoute.Readable -> {
                    if (route.key in declared) {
                        null
                    } else {
                        problem(
                            route.key,
                            ProblemCode.REQUIRED,
                            "is mounted as a functional route and declares no access; declare it through a MountsItsOwnSurface bean",
                        )
                    }
                }

                is FunctionalRoute.Unreadable -> {
                    problem(
                        "functional-route",
                        ProblemCode.INVALID,
                        "cannot be verified: ${route.description}; mount it with a method and a path predicate, or as an @Access handler",
                    )
                }
            }
        }
    }

    private data class Entry(
        val key: String,
        val handler: String,
        val lookup: DeclarationLookup,
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
                info.patternValues.flatMap { pattern ->
                    // A mapping without a method answers every method no other mapping of the pattern names.
                    val methods = stated.ifEmpty { RequestMethod.entries.map(RequestMethod::name).filterNot { "$it $pattern" in explicit } }
                    methods.map { method ->
                        Entry("$method $pattern", handler.toString(), declarations.of(handler.beanType, handler, resource, method, pattern))
                    }
                }
            }.sortedBy { it.key }
    }

    private fun declarationProblems(
        declaration: EndpointDeclaration,
        codes: Set<String>,
    ): List<ConfigurationProblem> =
        declaration.problems().map { problem(declaration.key, ProblemCode.INVALID, it) } +
            declaration.permissions.filterNot { it in codes }.distinct().map {
                problem(declaration.key, ProblemCode.INVALID, "names permission $it, which no ModuleGrants declares")
            }

    private fun problem(
        key: String,
        code: ProblemCode,
        message: String,
    ): ConfigurationProblem = ConfigurationProblem("access.surface:$key", code, message)
}
