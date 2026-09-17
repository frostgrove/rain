package com.gd.rain.tenancy.web

import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantCandidate
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRefusal
import com.gd.rain.tenancy.TenantRequestContext
import com.gd.rain.tenancy.TenantResolver
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.resolution.TenantHeaderRequestContext
import com.gd.rain.tenancy.resolution.TenantHostAuthority
import com.gd.rain.tenancy.resolution.TenantHostRequestContext
import jakarta.servlet.http.HttpServletRequest
import java.util.Collections

/** One authority-checked answer from the servlet request-resolution pipeline. */
public sealed interface TenantRequestScope {
    public data object Absent : TenantRequestScope

    public data class Present(
        public val scope: TenantScope,
    ) : TenantRequestScope
}

/** Resolves either a valid minted scope or a deliberate absence; malformed input never becomes absence. */
public fun interface TenantRequestScopeResolver {
    public fun resolve(
        context: TenantRequestContext,
        operation: TenantOperation,
    ): TenantRequestScope
}

/**
 * Adapter over the same resolver and authority used elsewhere. The servlet context exposes only
 * request attributes, not arbitrary headers: a trusted-header integration must validate and place
 * its own candidate before it reaches this generic boundary.
 */
public class AuthorityTenantRequestScopeResolver(
    private val authority: TenantAuthority,
    private val resolver: TenantResolver,
) : TenantRequestScopeResolver {
    override fun resolve(
        context: TenantRequestContext,
        operation: TenantOperation,
    ): TenantRequestScope =
        when (val candidate = resolver.resolveCurrent(context)) {
            TenantCandidate.Absent -> TenantRequestScope.Absent
            is TenantCandidate.Malformed -> throw TenantRefusal.Malformed
            is TenantCandidate.Present -> TenantRequestScope.Present(authority.verify(candidate.resolution, operation))
        }
}

/** Servlet transport context for tenant sources; headers are intentionally not generic attributes. */
public class ServletTenantRequestContext(
    private val request: HttpServletRequest,
) : TenantRequestContext,
    TenantHostRequestContext,
    TenantHeaderRequestContext {
    override fun attribute(name: String): String? = request.getAttribute(name) as? String

    /** Server-normalized authority; no raw forwarding or Host header is read here. */
    override fun authority(): TenantHostAuthority = TenantHostAuthority(request.serverName, request.serverPort)

    override fun headers(name: String): List<String> = Collections.list(request.getHeaders(name))
}
