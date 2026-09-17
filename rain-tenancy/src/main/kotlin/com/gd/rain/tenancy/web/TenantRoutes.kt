package com.gd.rain.tenancy.web

import com.gd.rain.tenancy.TenantOperation
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.util.ClassUtils
import org.springframework.web.method.HandlerMethod
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** The only tenant operations a servlet route may initiate; durable and admin work stay explicit. */
public enum class TenantRouteOperation {
    READ,
    WRITE,
    ;

    internal fun operation(): TenantOperation =
        when (this) {
            READ -> TenantOperation.READ
            WRITE -> TenantOperation.WRITE
        }
}

/** A request requires one admitted tenant scope before its handler starts. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class TenantRoute(
    public val operation: TenantRouteOperation,
)

/** A request is intentionally central and must state why it cannot serve tenant work. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class CentralRoute(
    public val why: String,
)

/** A request may serve either central work or exactly one admitted tenant, but never malformed input. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class UniversalRoute(
    public val operation: TenantRouteOperation,
    public val why: String,
)

/** One verified tenant declaration for a mounted endpoint or a generated route table. */
public data class TenantEndpointDeclaration(
    public val method: String,
    public val path: String,
    public val surface: TenantRouteSurface,
    public val why: String = "",
) {
    public val key: String get() = "$method $path"

    public fun problems(): List<String> {
        val found = mutableListOf<String>()
        if (method.isBlank() || path.isBlank()) found += "$key has no method or path"
        if (surface is TenantRouteSurface.Central || surface is TenantRouteSurface.Universal) {
            if (!why.isMeaningfulWhy()) found += "$key declares a central-capable surface without a bounded why"
        } else if (why.isNotEmpty()) {
            found += "$key is tenant-required and must not carry a central why"
        }
        return found
    }
}

/** The semantic shape a route has declared. */
public sealed interface TenantRouteSurface {
    public data class Tenant(
        public val operation: TenantRouteOperation,
    ) : TenantRouteSurface

    public data object Central : TenantRouteSurface

    public data class Universal(
        public val operation: TenantRouteOperation,
    ) : TenantRouteSurface
}

/** A generated controller can declare route tenancy from its own stable table instead of reflection. */
public interface DeclaresItsOwnTenancy {
    public fun tenantDeclarations(): List<TenantEndpointDeclaration>
}

/** Explicit exemption for infrastructure handlers outside an application's tenant surface. */
public data class TenantSurfaceExemption(
    public val handlerType: Class<*>,
    public val why: String,
) {
    init {
        require(why.isMeaningfulWhy()) { "tenant surface exemption needs a bounded why" }
    }
}

/** Result of resolving one handler's tenant declaration. */
public sealed interface TenantRouteLookup {
    public data class Declared(
        public val declaration: TenantEndpointDeclaration,
    ) : TenantRouteLookup

    public data class Exempt(
        public val exemption: TenantSurfaceExemption,
    ) : TenantRouteLookup

    public data object Undeclared : TenantRouteLookup
}

/**
 * Reads handler annotations and generated declarations exactly once in the same order as enforcement.
 * A method declaration overrides its class default; two annotations on one level are a start-up failure.
 */
public class TenantRouteDeclarations(
    private val exemptions: List<TenantSurfaceExemption>,
) {
    private val derived = ConcurrentHashMap<DeclaresItsOwnTenancy, List<TenantEndpointDeclaration>>()

    public fun of(
        handlerType: Class<*>,
        handler: HandlerMethod,
        resource: DeclaresItsOwnTenancy?,
        method: String,
        path: String,
    ): TenantRouteLookup {
        val userType = ClassUtils.getUserClass(handlerType)
        exemptions.firstOrNull { it.handlerType.isAssignableFrom(userType) }?.let(TenantRouteLookup::Exempt)?.let { return it }
        declaredOn(handler.method, method, path)?.let(TenantRouteLookup::Declared)?.let { return it }
        declaredOn(userType, method, path)?.let(TenantRouteLookup::Declared)?.let { return it }
        val declarations =
            resource?.let { owner ->
                derived.computeIfAbsent(owner) { it.tenantDeclarations().toList() }
            }
        return declarationFor(declarations.orEmpty(), method, path)?.let(TenantRouteLookup::Declared) ?: TenantRouteLookup.Undeclared
    }

    private fun declaredOn(
        element: AnnotatedElement,
        method: String,
        path: String,
    ): TenantEndpointDeclaration? {
        val declarations =
            buildList {
                AnnotatedElementUtils.findMergedAnnotation(element, TenantRoute::class.java)?.let { annotation ->
                    add(TenantEndpointDeclaration(method, path, TenantRouteSurface.Tenant(annotation.operation)))
                }
                AnnotatedElementUtils.findMergedAnnotation(element, CentralRoute::class.java)?.let { annotation ->
                    add(TenantEndpointDeclaration(method, path, TenantRouteSurface.Central, annotation.why))
                }
                AnnotatedElementUtils.findMergedAnnotation(element, UniversalRoute::class.java)?.let { annotation ->
                    add(TenantEndpointDeclaration(method, path, TenantRouteSurface.Universal(annotation.operation), annotation.why))
                }
            }
        check(declarations.size <= 1) { "tenant route declares more than one surface" }
        return declarations.singleOrNull()
    }
}

/** GET's declaration also governs implicit HEAD, as it does for the access surface. */
public fun declarationFor(
    declarations: Map<String, TenantEndpointDeclaration>,
    method: String,
    path: String,
): TenantEndpointDeclaration? = declarationFor(declarations.values, method, path)

/**
 * Selects an explicit generated route declaration. A duplicate is a configuration defect, never a
 * winner-selected-by-order authorization decision. GET governs an implicit HEAD only when HEAD is
 * not declared itself.
 */
public fun declarationFor(
    declarations: Iterable<TenantEndpointDeclaration>,
    method: String,
    path: String,
): TenantEndpointDeclaration? {
    val direct = declarations.filter { it.method == method && it.path == path }
    val candidates = direct.ifEmpty { declarations.filter { method == "HEAD" && it.method == "GET" && it.path == path } }
    require(candidates.size <= 1) { "$method $path is declared more than once" }
    return candidates.singleOrNull()
}

private fun String.isMeaningfulWhy(): Boolean =
    isNotBlank() && toByteArray(Charsets.UTF_8).size <= MAX_WHY_BYTES && none(Char::isISOControl)

private const val MAX_WHY_BYTES: Int = 512
