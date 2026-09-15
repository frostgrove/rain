package com.gd.rain.access.internal.web

import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.CredentialDelivery
import com.gd.rain.access.GrantsLookup
import com.gd.rain.access.internal.usecase.AccessFaults
import com.gd.rain.access.internal.usecase.ChangePasswordUseCase
import com.gd.rain.access.internal.usecase.CloseEverywhereUseCase
import com.gd.rain.access.internal.usecase.LoginUseCase
import com.gd.rain.access.internal.usecase.LogoutUseCase
import com.gd.rain.access.internal.usecase.RefreshUseCase
import com.gd.rain.access.internal.usecase.SessionsQuery
import com.gd.rain.access.internal.usecase.SignUpUseCase
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.web.route.Access
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

public object AuthReasons {
    public const val NO_CREDENTIAL_YET: String = "a caller has no credential to present until this answers one"
    public const val TOKEN_IS_GONE: String = "the refresh credential authenticates this call; the access token is what it replaces"
    public const val SIGNING_UP: String = "creating an account is what a caller with none does"
    public const val OWN_SESSION: String = "the caller acts on its own sessions and credential only"
}

/**
 * The credential routes under `<base>/auth`. Where each half of an answer goes is `rain.access.web.delivery`'s; a
 * refresh answers the way its credential arrived, so a script cannot turn a cookie-borne credential into a readable one.
 */
@RestController
@RequestMapping("\${rain.access.web.base-path}/auth")
public class AuthController(
    private val subjects: SubjectRegistry,
    private val login: LoginUseCase,
    private val refresh: RefreshUseCase,
    private val logout: LogoutUseCase,
    private val everywhere: CloseEverywhereUseCase,
    private val passwords: ChangePasswordUseCase,
    private val sessions: SessionsQuery,
    private val grants: GrantsLookup,
    private val cookies: CredentialCookies,
    private val delivery: CredentialDelivery,
    private val pages: PageRequest,
) {
    @Access(public = true, why = AuthReasons.NO_CREDENTIAL_YET)
    @PostMapping("/{subjectType}/login")
    public fun signIn(
        @PathVariable subjectType: String,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): AuthAnswer {
        pages.only(request)
        val mode = DeliveryDecision.forSignIn(delivery, request)
        val served = subjects.resolve(subjectType, "subjectType")
        val form = JsonBody.of(body, "identifier", "password")
        val signedIn = login.signIn(served, form.requiredText("identifier"), form.requiredText("password"), agentOf(request))
        if (mode == CredentialDelivery.COOKIES) cookies.write(response, cookies.issue(signedIn.credentials))
        return AuthAnswer.of(signedIn.credentials, signedIn.profile, mode)
    }

    @Access(public = true, why = AuthReasons.TOKEN_IS_GONE)
    @PostMapping("/refresh")
    public fun rotate(
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): AuthAnswer {
        pages.only(request)
        val byCookie =
            if (delivery !=
                CredentialDelivery.BODY
            ) {
                request.cookies.orEmpty().filter { it.name == CredentialCookies.REFRESH }
            } else {
                emptyList()
            }
        val byBody = if (delivery != CredentialDelivery.COOKIES) body?.get(REFRESH_FIELD) else null
        val presented =
            when {
                byCookie.size > 1 -> throw AccessFaults.unauthenticated("the request carries more than one refresh cookie")

                byCookie.size == 1 && byBody != null -> throw AccessFaults.unauthenticated(
                    "the request presents a refresh credential twice",
                )

                byCookie.size == 1 -> byCookie.single().value to CredentialDelivery.COOKIES

                byBody != null && byBody.isString -> byBody.asString() to CredentialDelivery.BODY

                else -> throw AccessFaults.unauthenticated("the request presents no refresh credential")
            }
        val (credential, arrived) = presented
        val rotated =
            try {
                refresh.rotate(credential)
            } catch (refused: Fault) {
                if (arrived == CredentialDelivery.COOKIES) cookies.write(response, listOf(cookies.clearRefresh()))
                throw refused
            }
        if (arrived == CredentialDelivery.COOKIES) cookies.write(response, cookies.issue(rotated.credentials))
        return AuthAnswer.of(rotated.credentials, rotated.profile, arrived)
    }

    @Access(authenticated = true, why = AuthReasons.OWN_SESSION)
    @PostMapping("/logout")
    public fun signOut(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        pages.only(request)
        logout.signOut(principal())
        if (delivery != CredentialDelivery.BODY) cookies.write(response, cookies.clear())
        return ResponseEntity.noContent().build()
    }

    @Access(authenticated = true, why = AuthReasons.OWN_SESSION)
    @PostMapping("/logout-all")
    public fun signOutEverywhere(
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ClosedView {
        pages.only(request)
        val includingCurrent = JsonBody.of(body, "includingCurrent").requiredBoolean("includingCurrent")
        val closed = everywhere.closeAll(principal(), includingCurrent)
        if (includingCurrent && delivery != CredentialDelivery.BODY) cookies.write(response, cookies.clear())
        return ClosedView(closed)
    }

    @Access(authenticated = true, why = AuthReasons.OWN_SESSION)
    @PostMapping("/password")
    public fun changePassword(
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        val form = JsonBody.of(body, "current", "next")
        passwords.change(principal(), form.requiredText("current"), form.requiredText("next"), agentOf(request))
        return ResponseEntity.noContent().build()
    }

    @Access(authenticated = true, why = AuthReasons.OWN_SESSION)
    @GetMapping("/me")
    public fun whoAmI(request: HttpServletRequest): PrincipalView {
        pages.only(request)
        val principal = principal()
        val profile = subjects.served(principal.subject.type)?.directory?.describe(principal.subject.id)
        return PrincipalView(SubjectView(principal.subject.type.name, principal.subject.id), principal.session, ProfileView.of(profile))
    }

    @Access(authenticated = true, why = AuthReasons.OWN_SESSION)
    @GetMapping("/sessions")
    public fun ownSessions(request: HttpServletRequest): PageView<SessionView> {
        pages.only(request, PageRequest.AFTER, PageRequest.LIMIT)
        val principal = principal()
        val page = sessions.page(principal.subject, pages.after(request, PageRequest::sessionCursor), pages.limit(request))
        return PageView(page.items.map { SessionView.of(it, principal.session) }, page.next?.let(PageRequest::sessionCursorOf))
    }

    @Access(authenticated = true, why = AuthReasons.OWN_SESSION)
    @DeleteMapping("/sessions/{sessionId}")
    public fun closeOwnSession(
        @PathVariable sessionId: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        logout.closeOwn(principal(), CanonicalIds.required(sessionId, "sessionId"))
        return ResponseEntity.noContent().build()
    }

    private fun principal(): AccessPrincipal =
        grants.principalOf()
            ?: throw Fault(FaultKind.UNAUTHORIZED, RainErrorCodes.UNAUTHENTICATED, "this route needs an authenticated caller")

    private companion object {
        const val REFRESH_FIELD = "refreshToken"
    }
}

public data class ClosedView(
    public val closed: Long,
)

/** `POST <base>/auth/{subjectType}/register`, mounted only when some subject type has a registrar. */
@RestController
@RequestMapping("\${rain.access.web.base-path}/auth")
public class SignUpController(
    private val subjects: SubjectRegistry,
    private val signUp: SignUpUseCase,
    private val cookies: CredentialCookies,
    private val delivery: CredentialDelivery,
    private val pages: PageRequest,
) {
    @Access(public = true, why = AuthReasons.SIGNING_UP)
    @PostMapping("/{subjectType}/register")
    public fun register(
        @PathVariable subjectType: String,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<AuthAnswer> {
        pages.only(request)
        val served = subjects.resolve(subjectType, "subjectType")
        if (served.registrar == null) throw Fault.notFound(message = "subject type ${served.type} does not sign up")
        val mode = DeliveryDecision.forSignIn(delivery, request)
        val form = JsonBody.of(body, "identifier", "password", "profile")
        val signedIn =
            signUp.signUp(
                served,
                form.requiredText("identifier"),
                form.requiredText("password"),
                form.textMap("profile"),
                agentOf(request),
            )
        if (mode == CredentialDelivery.COOKIES) cookies.write(response, cookies.issue(signedIn.credentials))
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthAnswer.of(signedIn.credentials, signedIn.profile, mode))
    }
}
