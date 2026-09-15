package com.gd.rain.access

import org.springframework.security.core.AuthenticatedPrincipal
import java.time.Instant
import java.util.UUID

/**
 * Who an authenticated request acts as, and the session it holds. It carries no permission list: what the subject may
 * do is asked of [GrantsLookup] for exactly the codes a decision needs.
 */
public data class AccessPrincipal(
    public val subject: SubjectRef,
    public val session: UUID,
    /** When the session was issued; a sign-out everywhere closes every session issued up to its instant. */
    public val sessionIssuedAt: Instant,
    public val tokenExpiresAt: Instant,
) : AuthenticatedPrincipal {
    override fun getName(): String = subject.resourceId
}

/** One permission granted to a subject directly. */
public data class GrantedPermission(
    public val permissionId: UUID,
    public val code: String,
    public val grantedAt: Instant,
)

/** A keyset page of direct permissions; [next] is where the following page starts, `null` after the last one. */
public data class PermissionPage(
    public val items: List<GrantedPermission>,
    public val next: UUID?,
)

/** What an application reads about grants. */
public interface GrantsLookup {
    /** The principal of the request running on this thread, or `null` when it authenticated as nobody. */
    public fun principalOf(): AccessPrincipal?

    /**
     * The subset of [codes] the subject holds — directly, through a role, or through a role that grants every
     * permission. One bounded statement over exactly the codes asked; a code nothing declares is held by nobody.
     */
    public fun heldBy(
        subject: SubjectRef,
        codes: Set<String>,
    ): Set<String>

    /** Permissions granted to the subject directly, in permission-id order. [limit] is 1..`rain.access.web.page.max-size`. */
    public fun directPermissionsOf(
        subject: SubjectRef,
        after: UUID?,
        limit: Int,
    ): PermissionPage

    /** The directory of [type], or `null` when no subject of that type is mounted. */
    public fun directoryOf(type: SubjectType): SubjectDirectory?
}

/** Whether an enrolment wrote a credential. */
public sealed interface Enrolment {
    public data object Enrolled : Enrolment

    /** The subject already signs in with a password; nothing was written. */
    public data object AlreadyEnrolled : Enrolment
}

/** The outcome of looking for a holder of a role who can actually sign in. */
public sealed interface HolderSearch {
    public data class Found(
        public val subject: SubjectRef,
    ) : HolderSearch

    /** Every holder was examined and none is active with a password. */
    public data object NoneFound : HolderSearch

    /** The declared page budget ran out before every holder was examined; nothing was decided. */
    public data class NotEvaluated(
        public val pagesScanned: Int,
    ) : HolderSearch
}

/**
 * The writes seeding and operations perform against access state, each idempotent. Methods that derive a password hash
 * refuse to run inside a transaction: a hash takes a bulkhead permit and seconds of CPU, and a pooled connection held
 * for that time is one no request can have.
 */
public interface AccessProvisioning {
    /** Creates the role when it is missing (never a system role) and attaches [permissions]; never detaches. Answers its id. */
    public fun ensureRole(
        slug: String,
        name: String,
        permissions: Set<String>,
    ): UUID

    /** What a self-registered subject of [type] is granted. */
    public fun setDefaultRole(
        type: SubjectType,
        slug: String,
    )

    public fun grantRole(
        subject: SubjectRef,
        slug: String,
    )

    public fun enrolPassword(
        subject: SubjectRef,
        identifier: String,
        password: String,
    ): Enrolment

    public fun hasPassword(subject: SubjectRef): Boolean

    /**
     * A holder of the role who is active and has a password, examined a keyset page of holders at a time within the
     * declared page budget (`rain.access.provisioning.holder-page-size`, `holder-page-budget`).
     */
    public fun usableHolderOf(slug: String): HolderSearch
}
