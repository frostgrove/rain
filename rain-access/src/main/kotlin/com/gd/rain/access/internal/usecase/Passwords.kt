package com.gd.rain.access.internal.usecase

import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.Admission
import com.gd.rain.access.internal.attempt.Attempt
import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.AttemptPolicy
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.password.HashingBulkhead
import com.gd.rain.access.internal.password.PasswordHasher
import com.gd.rain.access.internal.store.CredentialInsert
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.internal.web.CanonicalIds
import com.gd.rain.audit.AuditDetail
import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditOutcome
import com.gd.rain.core.id.IdGenerator
import java.time.Clock

/**
 * A subject changing its own password: the current password is a password oracle behind a valid session, so it is
 * charged to the same attempt keys a sign-in with the account's identifier is, and a failure is recorded the way a
 * failed sign-in is — its own row, and a `lockout-opened` row for each key it locked. The current password is verified
 * and the new one hashed outside any transaction; the write happens only while the credential is still the version
 * verified, with one re-run when it changed, then a refusal. Whether the subject's other sessions close is the
 * deployment's `password.revoke-other-sessions-on-change`.
 */
public class ChangePasswordUseCase(
    private val credentials: CredentialStore,
    private val hasher: PasswordHasher,
    private val bulkhead: HashingBulkhead,
    private val limiter: AttemptLimiter,
    policy: AttemptPolicy,
    private val rules: PasswordRules,
    private val closing: SessionClosing,
    private val revokeOtherSessions: Boolean,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val clock: Clock,
) {
    private val failures = FailedAttempts(limiter, policy, audit)

    public fun change(
        principal: AccessPrincipal,
        current: String,
        next: String,
        agent: Agent,
    ) {
        transactions.requireOutside("a password change")
        repeat(PHASES) {
            val credential = credentials.findBySubject(principal.subject) ?: throw AccessFaults.badCredentials()
            val attempt = Attempt(principal.subject.type, credential.identifier, agent.address)
            when (val admission = limiter.admit(attempt)) {
                is Admission.Refused -> throw AccessFaults.tooManyAttempts(admission.retryAfter)
                Admission.Admitted -> Unit
            }
            rules.checkNewPassword(next, "next")
            if (!rules.presentable(credential.identifier, current) || !bulkhead.run { hasher.verify(current, credential.secretHash) }) {
                failures.record(attempt)
                throw AccessFaults.badCredentials()
            }
            val hash = bulkhead.run { hasher.hash(next) }
            val cutoff =
                transactions.inTransaction {
                    val locked = credentials.lockById(credential.id)
                    if (locked == null || locked.version != credential.version) return@inTransaction Changed
                    val now = clock.instant()
                    credentials.replaceSecret(locked.id, locked.version, hash, now)
                    val cutoff = if (revokeOtherSessions) closing.cutoff(principal.subject, principal.session, now) else null
                    audit.record(
                        AuditEvent(
                            AccessAuditTypes.PASSWORD_CHANGED,
                            AuditOutcome.OK,
                            principal.subject.resourceId,
                            AuditDetail.of("by" to "self", "other_sessions_closed" to revokeOtherSessions),
                        ),
                    )
                    Written(cutoff)
                }
            if (cutoff is Written) {
                limiter.recordSuccess(attempt)
                cutoff.cutoff?.let { closing.complete(it, RevocationReasons.PASSWORD_CHANGED) }
                return
            }
        }
        throw AccessFaults.credentialChanged()
    }

    private sealed interface Outcome

    private data object Changed : Outcome

    private data class Written(
        val cutoff: SubjectCutoff?,
    ) : Outcome

    private companion object {
        const val PHASES = 2
    }
}

/**
 * An operator setting another subject's password: the subject type is one this application serves (otherwise
 * `400 unknown_subject_type`), the identifier is the directory's, normalised the way sign-in normalises what it looks
 * up, the hash is derived outside any transaction, and every session of the subject is closed. An identifier another
 * subject of the type signs in with is `409 identifier_taken`, whether or not the subject had a credential.
 */
public class SetSubjectPasswordUseCase(
    private val subjects: SubjectRegistry,
    private val credentials: CredentialStore,
    private val hasher: PasswordHasher,
    private val bulkhead: HashingBulkhead,
    private val rules: PasswordRules,
    private val closing: SessionClosing,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    public fun set(
        subjectType: String,
        subjectId: String,
        secret: String,
    ) {
        val served = subjects.resolve(subjectType, "subjectType")
        val subject = SubjectRef(served.type, CanonicalIds.required(subjectId, "subjectId"))
        val profile = served.directory.describe(subject.id) ?: throw AccessFaults.notFound("subject")
        val identifier = served.mounted.normalization.normalize(profile.identifier)
        rules.checkIdentifier(identifier, "identifier")
        rules.checkNewPassword(secret, "password")
        transactions.requireOutside("setting a password")
        val hash = bulkhead.run { hasher.hash(secret) }
        val cutoff =
            transactions.inTransaction {
                val now = clock.instant()
                val existing = credentials.lockBySubject(subject)
                if (existing == null) {
                    when (credentials.insert(ids.next(), subject, identifier, hash, now)) {
                        CredentialInsert.INSERTED -> Unit
                        CredentialInsert.IDENTIFIER_TAKEN -> throw AccessFaults.identifierTaken()
                        CredentialInsert.SUBJECT_ENROLLED -> throw AccessFaults.credentialChanged()
                    }
                } else {
                    // Checked before the write, so another subject's identifier is the refusal the insert gives, not a unique violation.
                    val holder = credentials.findByIdentifier(subject.type, identifier)
                    if (holder != null && holder.subject != subject) throw AccessFaults.identifierTaken()
                    credentials.replaceIdentifierAndSecret(existing.id, existing.version, identifier, hash, now)
                }
                val cutoff = closing.cutoff(subject, null, now)
                audit.record(
                    AuditEvent(
                        AccessAuditTypes.PASSWORD_CHANGED,
                        AuditOutcome.OK,
                        subject.resourceId,
                        AuditDetail.of("by" to "operator", "other_sessions_closed" to true),
                    ),
                )
                cutoff
            }
        closing.complete(cutoff, RevocationReasons.PASSWORD_SET)
    }
}
