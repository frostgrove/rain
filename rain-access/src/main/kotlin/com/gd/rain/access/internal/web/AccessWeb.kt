package com.gd.rain.access.internal.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.CredentialDelivery
import com.gd.rain.access.PermissionDef
import com.gd.rain.access.Profile
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.access.internal.store.PermissionRow
import com.gd.rain.access.internal.store.RoleRow
import com.gd.rain.access.internal.store.SessionCursor
import com.gd.rain.access.internal.store.StoredSession
import com.gd.rain.access.internal.usecase.Agent
import com.gd.rain.access.internal.usecase.IssuedCredentials
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import com.gd.rain.web.filter.isSafeMethod
import com.gd.rain.web.filter.mountedPath
import com.gd.rain.web.limit.TokenBucketThrottle
import com.gd.rain.web.problem.ProblemWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The permissions rain-access's own routes declare. */
public object AccessPermissionCodes {
    public const val ROLE_READ: String = "access.role.read"
    public const val ROLE_WRITE: String = "access.role.write"
    public const val ROLE_DELETE: String = "access.role.delete"
    public const val GRANT_READ: String = "access.grant.read"
    public const val GRANT_WRITE: String = "access.grant.write"
    public const val CREDENTIAL_WRITE: String = "access.credential.write"

    public val DECLARED: List<PermissionDef> =
        listOf(
            PermissionDef(ROLE_READ, "Read roles and the permission catalogue"),
            PermissionDef(ROLE_WRITE, "Create and rename roles, attach and detach their permissions"),
            PermissionDef(ROLE_DELETE, "Delete roles"),
            PermissionDef(GRANT_READ, "Read what a subject was granted"),
            PermissionDef(GRANT_WRITE, "Grant and revoke roles and permissions"),
            PermissionDef(CREDENTIAL_WRITE, "Set another subject's password"),
        )
}

/**
 * The two credential cookies. `__Host-rain-access` is sent on every path of this host; `__Secure-rain-refresh` only on
 * the refresh route, the one route that spends it. Both `HttpOnly`, `Secure` and `SameSite=Strict`, as their prefixes
 * require of a browser that honours them.
 */
public class CredentialCookies(
    private val refreshPath: String,
    private val clock: Clock,
) {
    public fun issue(credentials: IssuedCredentials): List<ResponseCookie> =
        listOf(
            cookie(ACCESS, ACCESS_PATH, credentials.accessToken, lifetime(credentials.accessExpiresAt)),
            cookie(REFRESH, refreshPath, credentials.refresh, lifetime(credentials.refreshExpiresAt)),
        )

    public fun clear(): List<ResponseCookie> = listOf(cookie(ACCESS, ACCESS_PATH, "", Duration.ZERO), clearRefresh())

    public fun clearRefresh(): ResponseCookie = cookie(REFRESH, refreshPath, "", Duration.ZERO)

    public fun write(
        response: HttpServletResponse,
        cookies: List<ResponseCookie>,
    ) {
        cookies.forEach { response.addHeader(HttpHeaders.SET_COOKIE, it.toString()) }
    }

    private fun lifetime(expiresAt: Instant): Duration =
        Duration.between(clock.instant(), expiresAt).takeIf { !it.isNegative } ?: Duration.ZERO

    private fun cookie(
        name: String,
        path: String,
        value: String,
        maxAge: Duration,
    ): ResponseCookie =
        ResponseCookie
            .from(name, value)
            .path(path)
            .httpOnly(true)
            .secure(true)
            .sameSite(SAME_SITE)
            .maxAge(maxAge)
            .build()

    public companion object {
        public const val ACCESS: String = "__Host-rain-access"
        public const val REFRESH: String = "__Secure-rain-refresh"
        public const val ACCESS_PATH: String = "/"
        public const val SAME_SITE: String = "Strict"
    }
}

/** How one sign-in's credentials are delivered: fixed by `rain.access.web.delivery`, or stated per request in `both`. */
public object DeliveryDecision {
    public const val HEADER: String = "Rain-Auth-Delivery"

    /** [CredentialDelivery.COOKIES] or [CredentialDelivery.BODY], never [CredentialDelivery.BOTH]. */
    public fun forSignIn(
        configured: CredentialDelivery,
        request: HttpServletRequest,
    ): CredentialDelivery {
        if (configured != CredentialDelivery.BOTH) return configured
        val stated = request.getHeaders(HEADER).toList()
        val chosen = stated.singleOrNull()?.let(CredentialDelivery::fromWire)
        if (chosen == null || chosen == CredentialDelivery.BOTH) {
            throw Fault(
                FaultKind.BAD_REQUEST,
                AccessErrorCodes.INVALID_DELIVERY,
                "state $HEADER once, as ${CredentialDelivery.COOKIES.wire} or ${CredentialDelivery.BODY.wire}",
            )
        }
        return chosen
    }
}

/** A JSON request body, read field by field into violations that point at the field. */
public class JsonBody(
    private val node: JsonNode?,
) {
    public fun requiredText(field: String): String {
        val value = node?.get(field)
        if (value == null || value.isNull) throw violation(field, RainErrorCodes.REQUIRED, null)
        if (!value.isString) throw violation(field, RainErrorCodes.INVALID_FORMAT, "\"$field\" is a string")
        return value.asString()
    }

    public fun requiredBoolean(field: String): Boolean {
        val value = node?.get(field)
        if (value == null || value.isNull) throw violation(field, RainErrorCodes.REQUIRED, null)
        if (!value.isBoolean) throw violation(field, RainErrorCodes.INVALID_FORMAT, "\"$field\" is true or false")
        return value.booleanValue()
    }

    /** An object of string values; absent is empty. */
    public fun textMap(field: String): Map<String, String> {
        val value = node?.get(field)
        if (value == null || value.isNull) return emptyMap()
        if (!value.isObject) throw violation(field, RainErrorCodes.INVALID_FORMAT, "\"$field\" is an object of strings")
        return value.properties().associate { (key, entry) ->
            if (!entry.isString) throw violation(field, RainErrorCodes.INVALID_FORMAT, "\"$field\" holds only string values")
            key to entry.asString()
        }
    }

    public fun canonicalIds(field: String): List<UUID> {
        val value = node?.get(field)
        if (value == null || value.isNull) throw violation(field, RainErrorCodes.REQUIRED, null)
        if (!value.isArray || value.isEmpty) throw violation(field, RainErrorCodes.INVALID_FORMAT, "\"$field\" is a non-empty array of ids")
        return value.mapIndexed { index, element ->
            (if (element.isString) CanonicalIds.parse(element.asString()) else null)
                ?: throw Fault.validation(listOf(Violation.at(path(field, index), RainErrorCodes.INVALID_ID)))
        }
    }

    private fun violation(
        field: String,
        code: ErrorCode,
        message: String?,
    ): Fault = Fault.validation(listOf(Violation.at(path(field), code, message)))
}

/** A keyset page's query parameters: only those a route names, a limit within the declared page, and a cursor it can read. */
public class PageRequest(
    private val page: AccessProperties.Page,
) {
    public fun limit(request: HttpServletRequest): Int {
        val written = single(request, LIMIT) ?: return page.defaultSize
        val limit = if (DIGITS.matches(written)) written.toIntOrNull() else null
        if (limit == null || limit < 1 || limit > page.maxSize) {
            throw Fault(FaultKind.BAD_REQUEST, RainErrorCodes.BAD_QUERY, "limit is a whole number from 1 to ${page.maxSize}")
        }
        return limit
    }

    public fun <C : Any> after(
        request: HttpServletRequest,
        read: (String) -> C?,
    ): C? {
        val written = single(request, AFTER) ?: return null
        return read(written) ?: throw Fault(FaultKind.BAD_REQUEST, AccessErrorCodes.INVALID_CURSOR)
    }

    /** Refuses a parameter the route does not name, so a misspelt or unsupported one is heard rather than ignored. */
    public fun only(
        request: HttpServletRequest,
        vararg allowed: String,
    ) {
        val unknown =
            request.parameterNames
                .toList()
                .filterNot { it in allowed }
                .sorted()
        if (unknown.isNotEmpty()) {
            throw Fault(
                FaultKind.BAD_REQUEST,
                RainErrorCodes.UNKNOWN_PARAMETER,
                "this route reads ${allowed.joinToString(
                    ", ",
                ).ifEmpty { "no parameter" }}; it does not read ${unknown.joinToString(", ").take(256)}",
            )
        }
    }

    private fun single(
        request: HttpServletRequest,
        name: String,
    ): String? {
        val values = request.getParameterValues(name) ?: return null
        if (values.size != 1) throw Fault(FaultKind.BAD_REQUEST, RainErrorCodes.BAD_QUERY, "$name is stated once")
        return values.single()
    }

    public companion object {
        public const val LIMIT: String = "limit"
        public const val AFTER: String = "after"

        private val DIGITS = Regex("^[0-9]{1,9}$")

        public fun slug(text: String): String? = text.takeIf(SystemRoleDeclaration::isWellFormedSlug)

        public fun code(text: String): String? = text.takeIf(PermissionDef::isWellFormed)

        /** `<epoch microseconds>_<session id>`. */
        public fun sessionCursor(text: String): SessionCursor? {
            val match = SESSION_CURSOR.matchEntire(text) ?: return null
            val micros = match.groupValues[1].toLongOrNull() ?: return null
            val id = CanonicalIds.parse(match.groupValues[2]) ?: return null
            return SessionCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id)
        }

        public fun sessionCursorOf(cursor: SessionCursor): String =
            "${ChronoUnit.MICROS.between(Instant.EPOCH, cursor.createdAt)}_${cursor.id}"

        private val SESSION_CURSOR = Regex("^([0-9]{1,19})_(.{36})$")
    }
}

public fun agentOf(request: HttpServletRequest): Agent = Agent(request.getHeader(HttpHeaders.USER_AGENT), request.remoteAddr)

public data class SubjectView(
    public val type: String,
    public val id: UUID,
)

public data class ProfileView(
    public val displayName: String,
    public val identifier: String,
    public val attributes: Map<String, String>,
) {
    public companion object {
        public fun of(profile: Profile?): ProfileView? = profile?.let { ProfileView(it.displayName, it.identifier, it.attributes) }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
public data class PrincipalView(
    public val subject: SubjectView,
    public val session: UUID,
    public val profile: ProfileView?,
)

/** A sign-in's answer. The token fields are present only when credentials are delivered in the body. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public data class AuthAnswer(
    public val principal: PrincipalView,
    public val accessToken: String? = null,
    public val accessExpiresAt: Instant? = null,
    public val refreshToken: String? = null,
    public val refreshExpiresAt: Instant? = null,
) {
    public companion object {
        public fun of(
            credentials: IssuedCredentials,
            profile: Profile?,
            delivery: CredentialDelivery,
        ): AuthAnswer {
            val principal =
                PrincipalView(
                    SubjectView(credentials.subject.type.name, credentials.subject.id),
                    credentials.session,
                    ProfileView.of(profile),
                )
            return if (delivery == CredentialDelivery.BODY) {
                AuthAnswer(
                    principal,
                    credentials.accessToken,
                    credentials.accessExpiresAt,
                    credentials.refresh,
                    credentials.refreshExpiresAt,
                )
            } else {
                AuthAnswer(principal)
            }
        }
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
public data class PageView<T>(
    public val items: List<T>,
    public val next: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
public data class SessionView(
    public val id: UUID,
    public val current: Boolean,
    public val userAgent: String?,
    public val address: String?,
    public val createdAt: Instant,
    public val lastUsedAt: Instant,
    public val expiresAt: Instant,
) {
    public companion object {
        public fun of(
            session: StoredSession,
            current: UUID,
        ): SessionView =
            SessionView(
                session.id,
                session.id == current,
                session.userAgent,
                session.address,
                session.createdAt,
                session.lastUsedAt,
                session.expiresAt,
            )
    }
}

public data class RoleView(
    public val id: UUID,
    public val slug: String,
    public val name: String,
    public val system: Boolean,
    public val grantsEveryPermission: Boolean,
    public val createdAt: Instant,
) {
    public companion object {
        public fun of(role: RoleRow): RoleView =
            RoleView(role.id, role.slug, role.name, role.isSystem, role.grantsEveryPermission, role.createdAt)
    }
}

public data class PermissionView(
    public val id: UUID,
    public val code: String,
    public val name: String,
    public val module: String,
    public val createdAt: Instant,
) {
    public companion object {
        public fun of(permission: PermissionRow): PermissionView =
            PermissionView(permission.id, permission.code, permission.name, permission.module, permission.createdAt)
    }
}

/** The routes under `<base>/auth`: the surface credentials are presented to and handed out on. */
public class CredentialSurface(
    private val authPath: String,
) {
    public fun covers(path: String): Boolean = path == authPath || path.startsWith("$authPath/")
}

/** The order of rain-access's own servlet filters: after rain-web's, before Spring Security's chain (order −100). */
public object AccessFilterOrder {
    public const val JSON_ONLY: Int = Ordered.HIGHEST_PRECEDENCE + 50
    public const val CREDENTIAL_THROTTLE: Int = Ordered.HIGHEST_PRECEDENCE + 60
}

/**
 * An unsafe request with a body on the credential surface carries JSON, and nothing a `<form>` can send
 * (`application/x-www-form-urlencoded`, `multipart/form-data`, `text/plain`): `415 unsupported_media_type`. A request
 * with no body is not asked; the length is read from the headers, never from the stream.
 */
public class JsonOnlyFilter(
    private val surface: CredentialSurface,
    private val writer: ProblemWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!surface.covers(request.mountedPath()) || isSafeMethod(request.method) || !hasBody(request) || isJson(request)) {
            filterChain.doFilter(request, response)
            return
        }
        writer.write(response, Fault(FaultKind.UNSUPPORTED_MEDIA_TYPE, message = "the credential routes read application/json only"))
    }

    private fun hasBody(request: HttpServletRequest): Boolean =
        request.contentLengthLong > 0 || (request.contentLengthLong < 0 && request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null)

    private fun isJson(request: HttpServletRequest): Boolean {
        val type =
            request.getHeader(HttpHeaders.CONTENT_TYPE)?.let {
                try {
                    MediaType.parseMediaType(it)
                } catch (malformed: org.springframework.http.InvalidMediaTypeException) {
                    null
                }
            } ?: return false
        return type.type == MediaType.APPLICATION_JSON.type && type.subtype == MediaType.APPLICATION_JSON.subtype
    }
}

/**
 * A token bucket per client address (the address rain-web's `client-address` mode attributes the request to) on unsafe
 * requests to the credential surface: a caller past its bucket is `429 too_many_requests` with a `Retry-After`.
 */
public class CredentialThrottleFilter(
    private val surface: CredentialSurface,
    private val throttle: TokenBucketThrottle,
    private val writer: ProblemWriter,
) : OncePerRequestFilter() {
    private val retryAfter: Duration? = throttle.retryAfterSeconds()?.let(Duration::ofSeconds)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!surface.covers(request.mountedPath()) || isSafeMethod(request.method) || throttle.allow(request.remoteAddr)) {
            filterChain.doFilter(request, response)
            return
        }
        writer.write(response, Fault(FaultKind.TOO_MANY_REQUESTS, retryAfter = retryAfter))
    }
}
