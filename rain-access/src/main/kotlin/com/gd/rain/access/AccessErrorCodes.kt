package com.gd.rain.access

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog

/** The codes rain-access lets reach a client. */
public object AccessErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-access"

    /** Says nothing about which half was wrong, and a miss costs what a hit costs. */
    public val BAD_CREDENTIALS: ErrorCode = ErrorCode.of("bad_credentials", "the identifier or the password is wrong")
    public val WEAK_PASSWORD: ErrorCode = ErrorCode.of("weak_password", "the password is shorter than this service accepts")
    public val PASSWORD_TOO_LONG: ErrorCode = ErrorCode.of("password_too_long", "the password is longer than this service accepts")
    public val TOO_MANY_ATTEMPTS: ErrorCode = ErrorCode.of("too_many_attempts", "too many sign-in attempts; wait before trying again")
    public val OVERLOADED: ErrorCode = ErrorCode.of("overloaded", "the service is busy; try again")
    public val UNKNOWN_ROLE: ErrorCode = ErrorCode.of("unknown_role", "no role with this slug exists")
    public val UNKNOWN_PERMISSION: ErrorCode = ErrorCode.of("unknown_permission", "no permission with this code is declared")
    public val SYSTEM_ROLE: ErrorCode = ErrorCode.of("system_role", "this role is part of the application and cannot be changed here")
    public val UNKNOWN_SUBJECT_TYPE: ErrorCode = ErrorCode.of("unknown_subject_type", "this application serves no subject of this type")
    public val UNUSABLE_SUBJECT: ErrorCode = ErrorCode.of("unusable_subject", "no active subject with this id exists")
    public val TOO_MANY_ROLES: ErrorCode = ErrorCode.of("too_many_roles", "the subject holds as many roles as this service allows")
    public val ALREADY_ENROLLED: ErrorCode = ErrorCode.of("already_enrolled", "this subject already signs in with a password")
    public val IDENTIFIER_TAKEN: ErrorCode = ErrorCode.of("identifier_taken", "this identifier already signs in")
    public val INVALID_DELIVERY: ErrorCode = ErrorCode.of("invalid_delivery", "the request does not say how credentials are delivered")
    public val CREDENTIAL_CHANGED: ErrorCode =
        ErrorCode.of("credential_changed", "the credential changed while the request was being checked; try again")
    public val AUDIT_UNAVAILABLE: ErrorCode = ErrorCode.of("audit_unavailable", "the change could not be recorded; nothing was changed")
    public val REVOCATION_UNAVAILABLE: ErrorCode =
        ErrorCode.of("revocation_unavailable", "whether this session is still open could not be established; try again")

    override val codes: List<ErrorCode> =
        listOf(
            BAD_CREDENTIALS,
            WEAK_PASSWORD,
            PASSWORD_TOO_LONG,
            TOO_MANY_ATTEMPTS,
            OVERLOADED,
            UNKNOWN_ROLE,
            UNKNOWN_PERMISSION,
            SYSTEM_ROLE,
            UNKNOWN_SUBJECT_TYPE,
            UNUSABLE_SUBJECT,
            TOO_MANY_ROLES,
            ALREADY_ENROLLED,
            IDENTIFIER_TAKEN,
            INVALID_DELIVERY,
            CREDENTIAL_CHANGED,
            AUDIT_UNAVAILABLE,
            REVOCATION_UNAVAILABLE,
        )
}
