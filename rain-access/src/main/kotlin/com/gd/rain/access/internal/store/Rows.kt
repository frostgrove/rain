package com.gd.rain.access.internal.store

import com.gd.rain.access.SubjectRef
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

public data class StoredCredential(
    public val id: UUID,
    public val subject: SubjectRef,
    public val identifier: String,
    public val secretHash: String,
    public val version: Long,
)

/** What inserting a password credential did. Nothing is ever overwritten by an insert. */
public enum class CredentialInsert {
    INSERTED,

    /** The subject already has a password credential. */
    SUBJECT_ENROLLED,

    /** Another subject of the type already signs in with the identifier. */
    IDENTIFIER_TAKEN,
}

public data class NewSession(
    public val id: UUID,
    public val subject: SubjectRef,
    public val tokenHash: String,
    public val userAgent: String?,
    public val address: String?,
    public val createdAt: Instant,
    public val expiresAt: Instant,
)

public data class StoredSession(
    public val id: UUID,
    public val subject: SubjectRef,
    public val tokenHash: String,
    public val previousTokenHash: String?,
    public val generation: Long,
    public val userAgent: String?,
    public val address: String?,
    public val createdAt: Instant,
    public val lastUsedAt: Instant,
    public val rotatedAt: Instant?,
    public val expiresAt: Instant,
    public val revokedAt: Instant?,
    public val revokedReason: String?,
)

/** Where a keyset page of a subject's live sessions continues: newest first by creation, then id. */
public data class SessionCursor(
    public val createdAt: Instant,
    public val id: UUID,
)

/** What closing one session found: when it was closed, and whether this call is what closed it. */
public data class SessionClosure(
    public val closedAt: Instant,
    public val closedByThisCall: Boolean,
)

/** A session closed at [revokedAt]; what the revocation list is told. */
public data class RevokedSession(
    public val id: UUID,
    public val revokedAt: Instant,
)

/** Every session of [subject] issued up to [cutoffAt] is closed, except [keptSession]. */
public data class SubjectCutoff(
    public val subject: SubjectRef,
    public val cutoffAt: Instant,
    public val keptSession: UUID?,
) {
    /** Whether a session issued at [issuedAt] with id [session] is closed by this cutoff. */
    public fun closes(
        session: UUID,
        issuedAt: Instant,
    ): Boolean = session != keptSession && !issuedAt.isAfter(cutoffAt)
}

public data class RoleRow(
    public val id: UUID,
    public val slug: String,
    public val name: String,
    public val isSystem: Boolean,
    public val grantsEveryPermission: Boolean,
    public val createdAt: Instant,
)

public data class PermissionRow(
    public val id: UUID,
    public val code: String,
    public val name: String,
    public val module: String,
    public val createdAt: Instant,
)

public data class HeldRole(
    public val roleId: UUID,
    public val slug: String,
    public val grantedAt: Instant,
)

public data class DeclaredPermission(
    public val id: UUID,
    public val code: String,
    public val name: String,
    public val module: String,
)

internal fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
