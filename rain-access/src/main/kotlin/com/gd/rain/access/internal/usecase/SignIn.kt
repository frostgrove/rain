package com.gd.rain.access.internal.usecase

import com.gd.rain.access.Profile
import com.gd.rain.access.SignUp
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.Admission
import com.gd.rain.access.internal.attempt.Attempt
import com.gd.rain.access.internal.attempt.AttemptKeyKind
import com.gd.rain.access.internal.attempt.AttemptKeys
import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.AttemptPolicy
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.password.HashingBulkhead
import com.gd.rain.access.internal.password.PasswordHasher
import com.gd.rain.access.internal.store.CredentialInsert
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.GrantStore
import com.gd.rain.access.internal.store.StoredCredential
import com.gd.rain.access.internal.token.SessionFingerprints
import com.gd.rain.audit.AuditDetail
import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditOutcome
import com.gd.rain.core.actor.Actor
import com.gd.rain.core.id.IdGenerator
import org.slf4j.LoggerFactory
import java.time.Clock

/** A sign-in that succeeded: the credentials to deliver and the profile to answer with. */
public data class SignedIn(
    public val credentials: IssuedCredentials,
    public val profile: Profile?,
)

/**
 * Password sign-in, in two phases so no pooled connection is held while a hash is derived.
 *
 * Outside any transaction: admission by the attempt limiter, the bounds, the credential read, the Argon2 verification
 * (against a dummy hash when nothing was found, so a miss costs what a hit costs) and, when the stored hash is weaker than
 * the current parameters, its re-derivation — each derivation inside the hashing bulkhead. Inside one transaction: the
 * credential row locked and required to be exactly the version phase one verified, the upgraded hash written, the session
 * issued, the directory told, and the success recorded as evidence — a failure to record it rolls everything back. A
 * credential that changed between the phases is verified again once; changed again, the sign-in is refused.
 *
 * A failed attempt is charged to the limiter and recorded independently; the failure that locks a key records one
 * `lockout-opened` row. An attempt the limiter refuses is charged nothing and records nothing.
 */
public class LoginUseCase(
    private val credentials: CredentialStore,
    private val issuer: SessionIssuer,
    private val hasher: PasswordHasher,
    private val bulkhead: HashingBulkhead,
    private val limiter: AttemptLimiter,
    private val policy: AttemptPolicy,
    private val rules: PasswordRules,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val fingerprints: SessionFingerprints,
    private val clock: Clock,
) {
    private val failures = FailedAttempts(limiter, policy, audit)

    public fun signIn(
        subject: ServedSubject,
        presentedIdentifier: String,
        secret: String,
        agent: Agent,
    ): SignedIn {
        transactions.requireOutside("a sign-in")
        val identifier = subject.mounted.normalization.normalize(presentedIdentifier)
        val attempt = Attempt(subject.type, identifier, agent.address)
        when (val admission = limiter.admit(attempt)) {
            is Admission.Refused -> throw AccessFaults.tooManyAttempts(admission.retryAfter)
            Admission.Admitted -> Unit
        }
        if (!rules.presentable(identifier, secret)) fail(attempt)

        repeat(PHASES) {
            val credential = credentials.findByIdentifier(subject.type, identifier)
            val verified = bulkhead.run { hasher.verify(secret, credential?.secretHash ?: hasher.dummyHash) }
            if (!verified || credential == null) fail(attempt)
            if (!subject.directory.isActive(credential.subject.id)) fail(attempt)
            val upgraded = if (hasher.needsUpgrade(credential.secretHash)) bulkhead.run { hasher.hash(secret) } else null
            val signedIn = transactions.inTransaction { issueUnchanged(subject, credential, upgraded, agent) }
            if (signedIn != null) {
                limiter.recordSuccess(attempt)
                log
                    .atInfo()
                    .setMessage("signed in")
                    .addKeyValue("subject_type", subject.type.name)
                    .addKeyValue(SessionFingerprints.LOG_KEY, fingerprints.of(signedIn.credentials.session))
                    .log()
                return signedIn
            }
        }
        throw AccessFaults.credentialChanged()
    }

    /** Phase two; `null` when the credential is no longer the one phase one verified. */
    private fun issueUnchanged(
        subject: ServedSubject,
        verified: StoredCredential,
        upgraded: String?,
        agent: Agent,
    ): SignedIn? {
        val locked = credentials.lockById(verified.id)
        if (locked == null || locked.version != verified.version || locked.identifier != verified.identifier) return null
        val now = clock.instant()
        if (upgraded != null) credentials.replaceSecret(locked.id, locked.version, upgraded, now)
        val issued = issuer.issue(locked.subject, agent)
        subject.directory.signedIn(locked.subject.id, now)
        audit.record(
            AuditEvent(
                type = AccessAuditTypes.SIGNED_IN,
                outcome = AuditOutcome.OK,
                resourceId = issued.session.toString(),
                detail = signInDetail(locked.subject, agent),
                actor = Actor(locked.subject.type.name, locked.subject.id.toString()),
            ),
        )
        return SignedIn(issued, subject.directory.describe(locked.subject.id))
    }

    private fun fail(attempt: Attempt): Nothing {
        failures.record(attempt)
        throw AccessFaults.badCredentials()
    }

    private companion object {
        /** The first verification, and the one repeat a credential that changed between the phases is allowed. */
        const val PHASES = 2

        val log = LoggerFactory.getLogger(LoginUseCase::class.java)
    }
}

/**
 * A password that did not prove itself — at sign-in, or as the current password of a password change: charged to the
 * attempt limiter, recorded independently of the refused request, and one `lockout-opened` row for each key the failure
 * locked. A failure a full attempt table could not count is logged.
 */
public class FailedAttempts(
    private val limiter: AttemptLimiter,
    private val policy: AttemptPolicy,
    private val audit: AuditTrail,
) {
    public fun record(attempt: Attempt) {
        val recorded = limiter.recordFailure(attempt)
        audit.recordIndependently(
            AuditEvent(
                type = AccessAuditTypes.SIGN_IN_FAILED,
                outcome = AuditOutcome.FAILED,
                resourceId = attempt.subjectType.name,
                detail = AuditDetail.of("identifier_fp" to AttemptKeys.fingerprint(attempt.identifier), "address" to attempt.address),
            ),
        )
        recorded.opened.forEach { key ->
            audit.recordIndependently(
                AuditEvent(
                    type = AccessAuditTypes.LOCKOUT_OPENED,
                    outcome = AuditOutcome.REFUSED,
                    resourceId =
                        when (key.kind) {
                            AttemptKeyKind.IDENTIFIER -> "identifier:${attempt.subjectType.name}:${AttemptKeys.fingerprint(
                                attempt.identifier,
                            )}"

                            AttemptKeyKind.ADDRESS -> "address:${attempt.address}"
                        },
                    detail =
                        AuditDetail.of(
                            "key_kind" to key.kind.wire,
                            "subject_type" to attempt.subjectType.name,
                            "address" to attempt.address,
                            "lock_for_ms" to policy.lockFor.toMillis(),
                        ),
                ),
            )
        }
        if (recorded.uncounted.isNotEmpty()) {
            log
                .atWarn()
                .setMessage("a failed password attempt could not be counted: the attempt table is full")
                .addKeyValue("subject_type", attempt.subjectType.name)
                .log()
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FailedAttempts::class.java)
    }
}

internal fun signInDetail(
    subject: SubjectRef,
    agent: Agent,
): AuditDetail {
    val entries = mutableListOf<Pair<String, Any>>("subject_type" to subject.type.name, "address" to agent.address)
    agent.storedUserAgent?.let { entries += "user_agent" to it }
    return AuditDetail.of(*entries.toTypedArray())
}

/**
 * A caller with no account creates one of a subject type that has a registrar: the password is hashed outside any
 * transaction; then, in one transaction, the registrar creates the subject, the credential is written, the type's
 * default role is granted, the first session is issued and both facts are recorded. An identifier another subject
 * signs in with refuses the whole of it.
 */
public class SignUpUseCase(
    private val credentials: CredentialStore,
    private val grants: GrantStore,
    private val issuer: SessionIssuer,
    private val hasher: PasswordHasher,
    private val bulkhead: HashingBulkhead,
    private val rules: PasswordRules,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public fun signUp(
        subject: ServedSubject,
        presentedIdentifier: String,
        secret: String,
        profile: Map<String, String>,
        agent: Agent,
    ): SignedIn {
        val registrar =
            checkNotNull(subject.registrar) { "subject type ${subject.type} has no registrar; its sign-up route is not mounted" }
        transactions.requireOutside("a sign-up")
        val identifier = subject.mounted.normalization.normalize(presentedIdentifier)
        rules.checkIdentifier(identifier, "identifier")
        rules.checkNewPassword(secret, "password")
        val hash = bulkhead.run { hasher.hash(secret) }
        return transactions.inTransaction {
            val now = clock.instant()
            val defaultRole = grants.defaultRoleOf(subject.type)
            val ref = SubjectRef(subject.type, registrar.register(SignUp(identifier, profile)))
            when (credentials.insert(ids.next(), ref, identifier, hash, now)) {
                CredentialInsert.INSERTED -> Unit
                CredentialInsert.IDENTIFIER_TAKEN -> throw AccessFaults.identifierTaken()
                CredentialInsert.SUBJECT_ENROLLED -> throw AccessFaults.alreadyEnrolled()
            }
            defaultRole?.let { grants.grantRole(ref, it.id, now) }
            val issued = issuer.issue(ref, agent)
            subject.directory.signedIn(ref.id, now)
            val actor = Actor(ref.type.name, ref.id.toString())
            audit.record(AuditEvent(AccessAuditTypes.SIGNED_UP, AuditOutcome.OK, ref.resourceId, actor = actor))
            audit.record(
                AuditEvent(AccessAuditTypes.SIGNED_IN, AuditOutcome.OK, issued.session.toString(), signInDetail(ref, agent), actor),
            )
            SignedIn(issued, subject.directory.describe(ref.id))
        }
    }
}
