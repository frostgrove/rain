package com.gd.rain.access

import java.time.Instant
import java.util.UUID

/**
 * What kind of caller a subject is: `agent`, `service`, `customer`.
 *
 * A value and not an enum, so a second kind of caller costs a [MountedSubject] and a [SubjectDirectory] bean and
 * no change to rain. The format is the one an audit actor type obeys, so a subject is always recordable as an actor.
 */
@JvmInline
public value class SubjectType(
    public val name: String,
) {
    init {
        require(isWellFormed(name)) { "a subject type matches $PATTERN, got \"$name\"" }
    }

    override fun toString(): String = name

    public companion object {
        public const val PATTERN: String = "^[a-z][a-z0-9_-]{0,63}$"

        private val FORMAT = Regex(PATTERN)

        public fun isWellFormed(value: String): Boolean = FORMAT.matches(value)
    }
}

/** The pair every grant, credential and session is scoped by. */
public data class SubjectRef(
    public val type: SubjectType,
    public val id: UUID,
) {
    /** `type:id`, the form an audit resource id and a log line name a subject by. */
    public val resourceId: String get() = "${type.name}:$id"

    override fun toString(): String = resourceId
}

/** What a directory says about a subject, for display. Nothing here decides what the subject may do. */
public data class Profile(
    public val displayName: String,
    public val identifier: String,
    public val attributes: Map<String, String> = emptyMap(),
)

/**
 * The seam between rain-access and the module that owns one kind of subject.
 *
 * rain-access knows a subject by type and id and asks the directory what it cannot answer itself, sometimes inside a
 * transaction rain-access opened and sometimes outside any:
 *
 * - inside one: [signedIn] and [describe] in the sign-in and sign-up transactions, and [isActive] in the transaction
 *   that grants a role or a permission;
 * - outside any: [isActive] on every authenticated request, before a sign-in locks the credential it verified, before a
 *   rotation's compare-and-set and while looking for a usable holder of a role; [describe] after a rotation, for
 *   `GET <base>/auth/me` and before an operator sets a password.
 *
 * An implementation that reads the database joins the caller's transaction when there is one, and needs none.
 */
public interface SubjectDirectory {
    public val subjectType: SubjectType

    /** Whether the subject may act. Asked on every authenticated request and before every rotation. */
    public fun isActive(id: UUID): Boolean

    /** The subject's profile, or `null` when the directory holds no such subject. */
    public fun describe(id: UUID): Profile?

    /** The subject signed in at [at]; called inside the sign-in transaction, so a failure refuses the sign-in. */
    public fun signedIn(
        id: UUID,
        at: Instant,
    )
}

/** How a presented identifier becomes the stored one; declared by the application for each mounted subject. */
public fun interface IdentifierNormalization {
    public fun normalize(presented: String): String
}

/**
 * A kind of subject this application serves over the credential surface: its type and the rule its identifiers are
 * normalised by. Credentials are stored and looked up by the exact normalised string, so a subject mounted without its
 * rule writes credentials no sign-in can find.
 */
public data class MountedSubject(
    public val type: SubjectType,
    public val normalization: IdentifierNormalization,
)

/** What a sign-up hands the registrar: the normalised identifier and the profile fields the caller sent. */
public data class SignUp(
    public val identifier: String,
    public val profile: Map<String, String>,
)

/**
 * Lets a caller with no account create one of [subjectType]. The sign-up route of a subject type exists only when a
 * registrar for it is a bean.
 *
 * [register] runs inside the sign-up transaction, together with the credential and the first session, so an account
 * never exists without the credential it signs in with.
 */
public interface SubjectRegistrar {
    public val subjectType: SubjectType

    /** Creates the subject and answers its id. */
    public fun register(signUp: SignUp): UUID
}
