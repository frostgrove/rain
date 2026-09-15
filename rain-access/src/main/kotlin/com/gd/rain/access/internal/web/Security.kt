package com.gd.rain.access.internal.web

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.CredentialDelivery
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.revocation.RevocationUnavailableException
import com.gd.rain.access.internal.revocation.RevocationVerdict
import com.gd.rain.access.internal.token.AccessTokenVerifier
import com.gd.rain.access.internal.usecase.AccessFaults
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.core.actor.Actor
import com.gd.rain.core.actor.CurrentActor
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.route.RequestPrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.web.filter.OncePerRequestFilter

/** What authenticated a request, as the rest of Spring Security sees it: the principal, and no authorities. */
public class AccessAuthentication(
    private val principal: AccessPrincipal,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): AccessPrincipal = principal
}

/**
 * Which access token a request presents, through the channels the configured delivery opens: the `__Host-rain-access`
 * cookie for `cookies`, `Authorization: Bearer` for `body`, either for `both`. More than one value in a channel, or a
 * token in both channels at once, is a refusal: a request that disagrees with itself about which credential it spends
 * is not resolved by picking one. No credential at all is not a refusal — whether a route needs one is its declaration.
 */
public class PresentedToken(
    private val delivery: CredentialDelivery,
) {
    public fun of(request: HttpServletRequest): String? {
        val fromCookie =
            if (delivery == CredentialDelivery.BODY) {
                null
            } else {
                val found = request.cookies.orEmpty().filter { it.name == CredentialCookies.ACCESS }
                if (found.size > 1) throw AccessFaults.unauthenticated("the request carries more than one access cookie")
                found.singleOrNull()?.value
            }
        val fromHeader =
            if (delivery == CredentialDelivery.COOKIES) {
                null
            } else {
                val values = request.getHeaders(HttpHeaders.AUTHORIZATION).toList()
                if (values.size > 1) throw AccessFaults.unauthenticated("the request carries more than one Authorization header")
                values.singleOrNull()?.let(::bearer)
            }
        if (fromCookie != null && fromHeader != null) {
            throw AccessFaults.unauthenticated("the request presents an access token in a cookie and in a header")
        }
        return fromCookie ?: fromHeader
    }

    /** `Bearer <token>`, the scheme compared case-insensitively; any other scheme presents no token of this application. */
    private fun bearer(header: String): String? {
        val space = header.indexOf(' ')
        if (space <= 0 || !header.substring(0, space).equals(SCHEME, ignoreCase = true)) return null
        return header.substring(space + 1).takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }

    private companion object {
        const val SCHEME = "Bearer"
    }
}

/**
 * Turns a presented access token into a principal, inside Spring Security's chain: the token verifies, names a served
 * subject type, is not revoked — by its session or by a cutoff of its subject — and names an active subject. A token
 * that fails any of it is `401 unauthenticated`; a revocation list that cannot be asked is `503 revocation_unavailable`,
 * never a pass.
 */
public class AccessAuthenticationFilter(
    private val tokens: PresentedToken,
    private val verifier: AccessTokenVerifier,
    private val subjects: SubjectRegistry,
    private val revocations: RevocationList,
    private val writer: ProblemWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal =
            try {
                authenticate(request)
            } catch (refusal: Fault) {
                writer.write(response, refusal)
                return
            } catch (unavailable: RevocationUnavailableException) {
                writer.write(response, Fault(FaultKind.RETRYABLE, AccessErrorCodes.REVOCATION_UNAVAILABLE, cause = unavailable))
                return
            }
        if (principal == null) {
            filterChain.doFilter(request, response)
            return
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = AccessAuthentication(principal)
        SecurityContextHolder.setContext(context)
        try {
            filterChain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun authenticate(request: HttpServletRequest): AccessPrincipal? {
        val token = tokens.of(request) ?: return null
        val verified = verifier.verify(token) ?: throw AccessFaults.unauthenticated("the access token is not valid")
        val served =
            subjects.served(verified.subject.type)
                ?: throw AccessFaults.unauthenticated("the access token names a subject type this application does not serve")
        if (revocations.verdict(verified.session, verified.subject, verified.sessionIssuedAt) == RevocationVerdict.REVOKED) {
            throw AccessFaults.unauthenticated("the session has been closed")
        }
        if (!served.directory.isActive(verified.subject.id)) throw AccessFaults.unauthenticated("the subject is not active")
        return AccessPrincipal(verified.subject, verified.session, verified.sessionIssuedAt, verified.expiresAt)
    }

    public companion object {
        /** Where the principal is kept on the request, which the request log reads after the security context is gone. */
        public const val PRINCIPAL_ATTRIBUTE: String = "com.gd.rain.access.principal"
    }
}

/** Spring Security's own 401, in problem format. */
public class ProblemEntryPoint(
    private val writer: ProblemWriter,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        writer.write(response, Fault(FaultKind.UNAUTHORIZED, RainErrorCodes.UNAUTHENTICATED, cause = authException))
    }
}

/** Spring Security's own 403, in problem format. */
public class ProblemAccessDenied(
    private val writer: ProblemWriter,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        writer.write(response, Fault(FaultKind.FORBIDDEN, RainErrorCodes.FORBIDDEN, cause = accessDeniedException))
    }
}

/**
 * The one security filter chain: stateless, no login page, no basic authentication, no CSRF token (rain-web's
 * cross-site filter refuses a forged write), no header writer (rain-web writes the security headers on every response)
 * and no CORS processing (rain-web's). It authorizes nothing by path: every route's own declaration is enforced by
 * [AccessEnforcementInterceptor].
 */
public object AccessSecurityChain {
    public fun build(
        http: HttpSecurity,
        authentication: AccessAuthenticationFilter,
        writer: ProblemWriter,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .headers { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { it.authenticationEntryPoint(ProblemEntryPoint(writer)).accessDeniedHandler(ProblemAccessDenied(writer)) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .addFilterBefore(authentication, AuthorizationFilter::class.java)
            .build()
}

/** The actor of the request running on this thread: its authenticated subject, or nobody. */
public object PrincipalActor : CurrentActor {
    override fun actor(): Actor? =
        (SecurityContextHolder.getContext().authentication?.principal as? AccessPrincipal)?.let {
            Actor(it.subject.type.name, it.subject.id.toString())
        }
}

/** What the request log names a request's principal by: `type:id`. */
public object AccessRequestPrincipal : RequestPrincipal {
    override fun of(request: HttpServletRequest): String? =
        (request.getAttribute(AccessAuthenticationFilter.PRINCIPAL_ATTRIBUTE) as? AccessPrincipal)?.subject?.resourceId
}
