package com.gd.rain.access.internal.audit

import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditEventType
import com.gd.rain.audit.AuditRecorder

/** The evidence rain-access records, each type declared once with the detail keys it may carry. */
public object AccessAuditTypes {
    public const val MODULE: String = "access"

    public val SIGNED_IN: AuditEventType = AuditEventType(MODULE, "signed-in", "session", setOf("subject_type", "address", "user_agent"))

    public val SIGN_IN_FAILED: AuditEventType = AuditEventType(MODULE, "sign-in-failed", "subject-type", setOf("identifier_fp", "address"))

    /** One row when an attempt key becomes locked; the attempts refused while it stays locked write nothing. */
    public val LOCKOUT_OPENED: AuditEventType =
        AuditEventType(MODULE, "lockout-opened", "attempt-key", setOf("key_kind", "subject_type", "address", "lock_for_ms"))

    public val SIGNED_UP: AuditEventType = AuditEventType(MODULE, "signed-up", "subject")

    public val SIGNED_OUT: AuditEventType = AuditEventType(MODULE, "signed-out", "session")

    /** One session closed by its subject from another session. */
    public val SESSION_REVOKED: AuditEventType = AuditEventType(MODULE, "session-revoked", "session")

    /** Every session of a subject up to an instant; the resource is the subject. */
    public val SESSIONS_CLOSED: AuditEventType = AuditEventType(MODULE, "sessions-closed", "subject", setOf("reason", "kept_session"))

    public val PASSWORD_CHANGED: AuditEventType =
        AuditEventType(MODULE, "password-changed", "subject", setOf("by", "other_sessions_closed"))

    public val PASSWORD_ENROLLED: AuditEventType = AuditEventType(MODULE, "password-enrolled", "subject", setOf("by"))

    public val GRANT_CHANGED: AuditEventType = AuditEventType(MODULE, "grant-changed", "subject", setOf("change", "grant_kind", "grant"))

    public val ROLE_CHANGED: AuditEventType = AuditEventType(MODULE, "role-changed", "role", setOf("change", "slug", "permission"))

    public val DEFAULT_ROLE_CHANGED: AuditEventType = AuditEventType(MODULE, "default-role-changed", "subject-type", setOf("slug"))

    public val ALL: List<AuditEventType> =
        listOf(
            SIGNED_IN,
            SIGN_IN_FAILED,
            LOCKOUT_OPENED,
            SIGNED_UP,
            SIGNED_OUT,
            SESSION_REVOKED,
            SESSIONS_CLOSED,
            PASSWORD_CHANGED,
            PASSWORD_ENROLLED,
            GRANT_CHANGED,
            ROLE_CHANGED,
            DEFAULT_ROLE_CHANGED,
        )
}

/** The evidence could not be written. The change it would have recorded does not happen. */
public class AuditUnavailableException(
    event: String,
    cause: Throwable,
) : RuntimeException("audit event $event could not be recorded: ${cause.javaClass.simpleName}", cause)

/** [AuditRecorder] with every failure to record turned into [AuditUnavailableException]. */
public class AuditTrail(
    private val recorder: AuditRecorder,
) {
    /** Inside the caller's transaction: the evidence commits or rolls back with the change. */
    public fun record(event: AuditEvent) {
        try {
            recorder.record(event)
        } catch (failed: RuntimeException) {
            throw AuditUnavailableException(event.type.id, failed)
        }
    }

    /** In a transaction of its own, for an outcome the caller does not commit: a failed attempt. */
    public fun recordIndependently(event: AuditEvent) {
        try {
            recorder.recordIndependently(event)
        } catch (failed: RuntimeException) {
            throw AuditUnavailableException(event.type.id, failed)
        }
    }
}
