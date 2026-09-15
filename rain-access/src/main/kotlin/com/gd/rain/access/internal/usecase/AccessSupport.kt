package com.gd.rain.access.internal.usecase

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.AttemptStoreUnavailableException
import com.gd.rain.access.internal.audit.AuditUnavailableException
import com.gd.rain.access.internal.revocation.RevocationUnavailableException
import com.gd.rain.access.internal.store.NewSession
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.token.RefreshCredential
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.ViolationOrigin
import com.gd.rain.core.error.path
import com.gd.rain.core.id.IdGenerator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The transactions rain-access opens, and the rule that password hashing never runs inside one. */
public class AccessTransactions(
    transactions: PlatformTransactionManager,
) {
    private val template = TransactionTemplate(transactions)

    public fun <T> inTransaction(work: () -> T): T {
        // A holder, because a nullable answer (a lock that found nothing) is a result `execute` cannot tell from none.
        val held = ArrayList<T>(1)
        template.executeWithoutResult { held += work() }
        return held.single()
    }

    /**
     * Refuses to run [operation] inside a transaction: it derives a password hash, and a pooled connection held meanwhile
     * is one no request can have.
     */
    public fun requireOutside(operation: String) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "$operation derives a password hash and runs outside any transaction; it was called inside one"
        }
    }
}

/** The refusals rain-access raises, spelled once. */
public object AccessFaults {
    public fun badCredentials(): Fault = Fault(FaultKind.UNAUTHORIZED, AccessErrorCodes.BAD_CREDENTIALS)

    public fun unauthenticated(message: String): Fault = Fault(FaultKind.UNAUTHORIZED, RainErrorCodes.UNAUTHENTICATED, message)

    public fun tooManyAttempts(retryAfter: Duration): Fault =
        Fault(FaultKind.TOO_MANY_REQUESTS, AccessErrorCodes.TOO_MANY_ATTEMPTS, retryAfter = retryAfter)

    public fun credentialChanged(): Fault = Fault(FaultKind.RETRYABLE, AccessErrorCodes.CREDENTIAL_CHANGED)

    public fun identifierTaken(): Fault = Fault(FaultKind.CONFLICT, AccessErrorCodes.IDENTIFIER_TAKEN)

    public fun alreadyEnrolled(): Fault = Fault(FaultKind.CONFLICT, AccessErrorCodes.ALREADY_ENROLLED)

    public fun systemRole(slug: String): Fault =
        Fault(FaultKind.FORBIDDEN, AccessErrorCodes.SYSTEM_ROLE, "the $slug role is part of the application and cannot be changed here")

    public fun tooManyRoles(maximum: Int): Fault =
        Fault(FaultKind.CONFLICT, AccessErrorCodes.TOO_MANY_ROLES, "the subject already holds $maximum roles, the most this service allows")

    public fun notFound(what: String): Fault = Fault.notFound(message = "no $what with this id exists")

    public fun unknownSubjectType(
        type: String,
        field: String,
    ): Fault =
        Fault(
            FaultKind.BAD_REQUEST,
            AccessErrorCodes.UNKNOWN_SUBJECT_TYPE,
            violations =
                listOf(
                    Violation.at(
                        path(field),
                        AccessErrorCodes.UNKNOWN_SUBJECT_TYPE,
                        "\"${type.take(64)}\" is not a subject type this application serves",
                    ),
                ),
        )

    public fun unknownRole(field: String): Fault = invalid(field, AccessErrorCodes.UNKNOWN_ROLE)

    public fun unknownPermission(field: String): Fault = invalid(field, AccessErrorCodes.UNKNOWN_PERMISSION)

    public fun unusableSubject(field: String): Fault = invalid(field, AccessErrorCodes.UNUSABLE_SUBJECT)

    public fun invalid(
        field: String,
        code: ErrorCode,
        message: String? = null,
    ): Fault =
        Fault(
            FaultKind.VALIDATION,
            code,
            violations = listOf(Violation(path = path(field), code = code, message = message, origin = ViolationOrigin.STATE)),
        )
}

/** The failures rain-access raises below a fault, each a retryable refusal naming what was unavailable. */
public object AccessFaultTranslator : FaultTranslator {
    override fun translate(failure: Throwable): Fault? {
        for (cause in generateSequence(failure, Throwable::cause)) {
            when (cause) {
                is AuditUnavailableException -> return Fault(FaultKind.RETRYABLE, AccessErrorCodes.AUDIT_UNAVAILABLE, cause = failure)

                is RevocationUnavailableException -> return Fault(
                    FaultKind.RETRYABLE,
                    AccessErrorCodes.REVOCATION_UNAVAILABLE,
                    cause = failure,
                )

                is AttemptStoreUnavailableException -> return Fault(FaultKind.RETRYABLE, RainErrorCodes.UNAVAILABLE, cause = failure)
            }
        }
        return null
    }
}

/** The declared bounds on what a caller may present. */
public class PasswordRules(
    private val password: AccessProperties.Password,
) {
    public val maxPasswordBytes: Int get() = password.maxBytes

    public val maxIdentifierBytes: Int get() = password.maxIdentifierBytes

    /** Whether a sign-in's identifier and password fit the bounds at all; one that does not is a failed attempt. */
    public fun presentable(
        identifier: String,
        secret: String,
    ): Boolean = identifier.isNotEmpty() && bytes(identifier) <= password.maxIdentifierBytes && bytes(secret) <= password.maxBytes

    public fun checkNewPassword(
        secret: String,
        field: String,
    ) {
        if (secret.codePointCount(0, secret.length) < password.minLength) {
            throw input(field, AccessErrorCodes.WEAK_PASSWORD, "a password has at least ${password.minLength} characters")
        }
        if (bytes(secret) > password.maxBytes) {
            throw input(field, AccessErrorCodes.PASSWORD_TOO_LONG, "a password has at most ${password.maxBytes} bytes")
        }
    }

    public fun checkIdentifier(
        identifier: String,
        field: String,
    ) {
        if (identifier.isEmpty()) throw input(field, RainErrorCodes.REQUIRED, null)
        if (bytes(identifier) > password.maxIdentifierBytes) {
            throw input(field, RainErrorCodes.TOO_LONG, "an identifier has at most ${password.maxIdentifierBytes} bytes")
        }
    }

    private fun bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun input(
        field: String,
        code: ErrorCode,
        message: String?,
    ): Fault = Fault.validation(listOf(Violation.at(path(field), code, message)))
}

/** Who is asking, as a session records it. */
public data class Agent(
    public val userAgent: String?,
    public val address: String,
) {
    /** The user agent as stored: at most [MAX_USER_AGENT] characters, absent when the request sent none. */
    public val storedUserAgent: String? get() = userAgent?.takeIf(String::isNotEmpty)?.take(MAX_USER_AGENT)

    public companion object {
        public const val MAX_USER_AGENT: Int = 256
    }
}

/** Both halves of a sign-in's answer, before delivery decides where each goes. */
public data class IssuedCredentials(
    public val subject: SubjectRef,
    public val session: UUID,
    public val sessionIssuedAt: Instant,
    public val accessToken: String,
    public val accessExpiresAt: Instant,
    public val refresh: String,
    public val refreshExpiresAt: Instant,
) {
    override fun toString(): String =
        "IssuedCredentials(subject=$subject, accessExpiresAt=$accessExpiresAt, refreshExpiresAt=$refreshExpiresAt)"
}

/** The one place a session comes into existence, and where both credentials are minted. */
public class SessionIssuer(
    private val sessions: SessionStore,
    private val tokens: AccessTokenIssuer,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val sessionTtl: Duration,
) {
    /** Inserts a session inside the caller's transaction and answers its credentials. */
    public fun issue(
        subject: SubjectRef,
        agent: Agent,
    ): IssuedCredentials {
        val now = clock.instant()
        val id = ids.next()
        val credential = RefreshCredential.mint(FIRST_GENERATION, id)
        val expiresAt = now.plus(sessionTtl)
        sessions.insert(NewSession(id, subject, RefreshCredential.digest(credential), agent.storedUserAgent, agent.address, now, expiresAt))
        return answer(subject, id, now, expiresAt, credential, now)
    }

    public fun answer(
        subject: SubjectRef,
        session: UUID,
        sessionIssuedAt: Instant,
        sessionExpiresAt: Instant,
        refresh: String,
        now: Instant,
    ): IssuedCredentials {
        val minted = tokens.mint(subject, session, sessionIssuedAt, sessionExpiresAt, now)
        return IssuedCredentials(subject, session, sessionIssuedAt, minted.token, minted.expiresAt, refresh, sessionExpiresAt)
    }

    private companion object {
        const val FIRST_GENERATION = 1L
    }
}

/** Why sessions were closed, as `sessions.revoked_reason` records it. The set is closed. */
public object RevocationReasons {
    public const val SIGNED_OUT: String = "signed-out"
    public const val SIGNED_OUT_EVERYWHERE: String = "signed-out-everywhere"
    public const val CLOSED_BY_SUBJECT: String = "closed-by-subject"
    public const val PASSWORD_CHANGED: String = "password-changed"
    public const val PASSWORD_SET: String = "password-set"
    public const val REFRESH_REPLAYED: String = "refresh-replayed"
}
