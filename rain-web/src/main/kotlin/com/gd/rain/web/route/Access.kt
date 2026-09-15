package com.gd.rain.web.route

import jakarta.servlet.http.HttpServletRequest

/**
 * What a route needs, as one annotation on the handler method or controller.
 *
 * One annotation is the declaration and what enforcement reads, so a route cannot be guarded without
 * being declared or declared without being guarded. The three shapes — permissions, authenticated,
 * public — are mutually exclusive, and [why] is mandatory for the two that name no permission: "this
 * route needs nothing" is a claim that carries its reason. Enforcement belongs to the access module;
 * this annotation lives in the web layer so every module's controllers can wear it.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Access(
    public val permissions: Array<String> = [],
    public val authenticated: Boolean = false,
    public val public: Boolean = false,
    public val why: String = "",
)

/**
 * One verified entry of the HTTP surface: a method and path and the access it declares. The surface
 * answers with the list the start-up verified, so tooling reads what the running process checked.
 */
public data class EndpointDeclaration(
    public val method: String,
    public val path: String,
    public val permissions: List<String> = emptyList(),
    public val authenticated: Boolean = false,
    public val public: Boolean = false,
    public val why: String = "",
) {
    public val key: String get() = "$method $path"

    /**
     * The invalid combinations, refused at start-up. An entry that declares nothing at all is the
     * dangerous one: it looks like a public route and is a route nobody decided about.
     */
    public fun problems(): List<String> {
        val problems = mutableListOf<String>()
        if (public && authenticated) problems += "$key is declared both public and authenticated"
        if (public && permissions.isNotEmpty()) problems += "$key is declared public and also requires ${permissions.joinToString(", ")}"
        if (permissions.any(String::isBlank)) problems += "$key declares an empty permission"
        if (!public && !authenticated && permissions.isEmpty()) problems += "$key is mounted and declares no access"
        if ((public || authenticated) && permissions.isEmpty() && why.isBlank()) {
            problems += "$key is declared ${if (public) "public" else "authenticated"} and says nothing about why"
        }
        return problems
    }

    public companion object {
        public fun of(
            method: String,
            path: String,
            access: Access,
        ): EndpointDeclaration =
            EndpointDeclaration(
                method = method,
                path = path,
                permissions = access.permissions.toList(),
                authenticated = access.authenticated,
                public = access.public,
                why = access.why,
            )
    }
}

/**
 * A controller whose routes carry no `@Access` of their own because they are derived from a table;
 * the surface verification reads these declarations instead of the annotations.
 */
public interface DeclaresItsOwnAccess {
    public fun accessDeclarations(): List<EndpointDeclaration>
}

/**
 * Routes a component mounts outside request mappings, with the access each declares.
 *
 * What the surface verification checks (rain-access's): every functional `RouterFunction` route it can read is declared
 * by some `MountsItsOwnSurface`, and one it cannot read refuses the start; every declaration is well formed, names only
 * permissions a module declares, and is not declared twice. A declaration that matches no mounted route is not refused:
 * a route of another handler mapping (a WebSocket upgrade) is declared here too, and the verification cannot see it.
 *
 * A functional route is enforced from its declaration like an `@Access` handler; a route mounted through any other
 * handler mapping must check the access it declares itself, before it serves.
 */
public interface MountsItsOwnSurface {
    public fun mountedDeclarations(): List<EndpointDeclaration>
}

/**
 * Who a request authenticated as, for the request log. The module that authenticates registers one
 * bean; without one the log line names no principal.
 */
public fun interface RequestPrincipal {
    /** The principal's identifier, or null for a request that authenticated as nobody. */
    public fun of(request: HttpServletRequest): String?
}
