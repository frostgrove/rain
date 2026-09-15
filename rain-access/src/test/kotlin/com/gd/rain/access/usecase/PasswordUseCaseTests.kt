package com.gd.rain.access.usecase

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.AttemptKeys
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.StoredCredential
import com.gd.rain.access.internal.usecase.IssuedCredentials
import com.gd.rain.access.internal.usecase.RevocationReasons
import com.gd.rain.access.support.AGENT_OF_TESTS
import com.gd.rain.access.support.AccessKit
import com.gd.rain.access.support.FakeHasher
import com.gd.rain.access.support.MemoryCredentialStore
import com.gd.rain.access.support.MemorySessionStore
import com.gd.rain.access.support.PASSWORD_RULES
import com.gd.rain.access.support.POLICY
import com.gd.rain.access.support.RecordingRevocationList
import com.gd.rain.access.support.START
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private const val PASSWORD = "correct horse battery"
private const val NEXT = "a brand new password"
private const val IDENTIFIER = "ada@example.test"

private fun AccessKit.signedIn(
    subject: SubjectRef,
    identifier: String = IDENTIFIER,
    password: String = PASSWORD,
): IssuedCredentials = login.signIn(served(subject), identifier, password, AGENT_OF_TESTS).credentials

private fun principalOf(issued: IssuedCredentials): AccessPrincipal =
    AccessPrincipal(issued.subject, issued.session, issued.sessionIssuedAt, issued.accessExpiresAt)

private fun faultOf(block: () -> Any?): Fault =
    requireNotNull(runCatching(block).exceptionOrNull() as? Fault) {
        "the call raised no fault"
    }

private fun Fault.pointed(): List<Pair<String, ErrorCode>> = violations.map { it.pointer to it.code }

/** Moves the credential to a new version, keeping its hash, each of the next [changes] times it is locked. */
private class CredentialChangedBetweenPhases(
    val delegate: MemoryCredentialStore,
    var changes: Int,
) : CredentialStore by delegate {
    override fun lockById(id: UUID): StoredCredential? {
        if (changes > 0) {
            changes--
            val row = requireNotNull(delegate.lockById(id))
            delegate.replaceSecret(id, row.version, row.secretHash, Instant.EPOCH)
        }
        return delegate.lockById(id)
    }
}

/** A subject changing its own password: a password attempt like a sign-in, and a change of every session it says to close. */
class ChangePasswordTest {
    private val hasher = FakeHasher()
    private val kit = AccessKit(hasher = hasher)
    private val subject = kit.enrolledAgent(IDENTIFIER, PASSWORD)
    private val issued = kit.signedIn(subject)

    private fun change(
        current: String = PASSWORD,
        next: String = NEXT,
        from: IssuedCredentials = issued,
    ) = kit.changePassword.change(principalOf(from), current, next, AGENT_OF_TESTS)

    @Test
    fun `a new password the rules refuse is refused before the current one is verified, and nothing is charged or written`() {
        val verified = hasher.verifications.get()

        val weak = faultOf { change(current = "not the current password", next = "short") }
        val long = faultOf { change(next = "é".repeat(PASSWORD_RULES.maxBytes)) }

        assertThat(weak.pointed()).containsExactly("/next" to AccessErrorCodes.WEAK_PASSWORD)
        assertThat(long.pointed()).containsExactly("/next" to AccessErrorCodes.PASSWORD_TOO_LONG)
        assertThat(hasher.verifications.get()).isEqualTo(verified)
        assertThat(kit.audit.ofType(AccessAuditTypes.SIGN_IN_FAILED.id)).isEmpty()
        assertThat(kit.credentials.findBySubject(subject)?.secretHash).isEqualTo("fake:$PASSWORD")
    }

    @Test
    fun `a wrong current password is 401 bad_credentials, recorded on its own against the account's identifier, and writes nothing`() {
        val refusal = faultOf { change(current = "not the current password") }

        assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.UNAUTHORIZED to AccessErrorCodes.BAD_CREDENTIALS)
        val failed = kit.audit.independent.single { it.type == AccessAuditTypes.SIGN_IN_FAILED }
        assertThat(failed.detail["identifier_fp"]).isEqualTo(AttemptKeys.fingerprint(IDENTIFIER))
        assertThat(failed.detail["address"]).isEqualTo(AGENT_OF_TESTS.address)
        assertThat(kit.audit.ofType(AccessAuditTypes.PASSWORD_CHANGED.id)).isEmpty()
        assertThat(kit.credentials.findBySubject(subject)?.secretHash).isEqualTo("fake:$PASSWORD")
    }

    @Test
    fun `the wrong current password that reaches the ceiling locks the account for sign-in too and records one lockout-opened row`() {
        repeat(POLICY.perIdentifier) { faultOf { change(current = "wrong password $it") } }

        assertThat(kit.audit.ofType(AccessAuditTypes.LOCKOUT_OPENED.id))
            .singleElement()
            .matches({ it.detail["key_kind"] == "identifier" && it.detail["subject_type"] == "agent" }, "the identifier's key")
        assertThat(faultOf { kit.signedIn(subject) }.code).isEqualTo(AccessErrorCodes.TOO_MANY_ATTEMPTS)
        assertThat(faultOf { change() }.code).isEqualTo(AccessErrorCodes.TOO_MANY_ATTEMPTS)
        assertThat(kit.audit.ofType(AccessAuditTypes.LOCKOUT_OPENED.id)).describedAs("refusals while locked record nothing").hasSize(1)
    }

    @Test
    fun `a change clears the failures counted against the account's identifier`() {
        repeat(POLICY.perIdentifier - 1) { faultOf { change(current = "wrong password $it") } }

        change()
        repeat(POLICY.perIdentifier - 1) { faultOf { change(current = "wrong again $it") } }

        assertThat(kit.audit.ofType(AccessAuditTypes.LOCKOUT_OPENED.id)).isEmpty()
        assertThat(kit.signedIn(subject, password = NEXT).subject).isEqualTo(subject)
    }

    @Test
    fun `a current password longer than the service reads is a failed attempt, and no hash is verified against it`() {
        val verified = hasher.verifications.get()

        val refusal = faultOf { change(current = "x".repeat(PASSWORD_RULES.maxBytes + 1)) }

        assertThat(refusal.code).isEqualTo(AccessErrorCodes.BAD_CREDENTIALS)
        assertThat(hasher.verifications.get()).isEqualTo(verified)
        assertThat(kit.audit.ofType(AccessAuditTypes.SIGN_IN_FAILED.id)).hasSize(1)
    }

    @Test
    fun `a change closes the subject's other sessions and keeps the one it was made from, when the deployment says so`() {
        val others = List(2) { kit.signedIn(subject) }

        change()

        val sessions = (kit.sessions as MemorySessionStore).all().associateBy { it.id }
        assertThat(sessions.getValue(issued.session).revokedAt).isNull()
        others.forEach { assertThat(sessions.getValue(it.session).revokedReason).isEqualTo(RevocationReasons.PASSWORD_CHANGED) }
        assertThat((kit.revocations as RecordingRevocationList).cutoffs)
            .singleElement()
            .matches({ it.subject == subject && it.keptSession == issued.session }, "one cutoff keeping this session")
        val changed = kit.audit.ofType(AccessAuditTypes.PASSWORD_CHANGED.id).single()
        assertThat(changed.resourceId).isEqualTo(subject.resourceId)
        assertThat(changed.detail["by"] to changed.detail["other_sessions_closed"]).isEqualTo("self" to true)
        assertThat(kit.signedIn(subject, password = NEXT).subject).isEqualTo(subject)
        assertThat(faultOf { kit.signedIn(subject) }.code).isEqualTo(AccessErrorCodes.BAD_CREDENTIALS)
    }

    @Test
    fun `a deployment that keeps other sessions on a change closes none and announces nothing`() {
        val keeping = AccessKit(revokeOtherSessions = false)
        val subject = keeping.enrolledAgent(IDENTIFIER, PASSWORD)
        val first = keeping.signedIn(subject)
        val second = keeping.signedIn(subject)

        keeping.changePassword.change(principalOf(first), PASSWORD, NEXT, AGENT_OF_TESTS)

        assertThat((keeping.sessions as MemorySessionStore).all()).allMatch { it.revokedAt == null }
        assertThat((keeping.revocations as RecordingRevocationList).cutoffs).isEmpty()
        assertThat(
            keeping.audit
                .ofType(AccessAuditTypes.PASSWORD_CHANGED.id)
                .single()
                .detail["other_sessions_closed"],
        ).isEqualTo(false)
        assertThat(
            keeping.refresh
                .rotate(second.refresh)
                .credentials.session,
        ).isEqualTo(second.session)
    }

    @Test
    fun `a credential that changed once between verification and write is verified again, and the change is written`() {
        val credentials = CredentialChangedBetweenPhases(MemoryCredentialStore(), changes = 0)
        val counting = FakeHasher()
        val changing = AccessKit(credentials = credentials, hasher = counting)
        val subject = changing.enrolledAgent(IDENTIFIER, PASSWORD)
        val issued = changing.signedIn(subject)
        val verified = counting.verifications.get()
        credentials.changes = 1

        changing.changePassword.change(principalOf(issued), PASSWORD, NEXT, AGENT_OF_TESTS)

        assertThat(counting.verifications.get() - verified).isEqualTo(2)
        assertThat(credentials.findBySubject(subject)?.secretHash).isEqualTo("fake:$NEXT")
        assertThat(changing.audit.ofType(AccessAuditTypes.PASSWORD_CHANGED.id)).hasSize(1)
    }
}

/** An operator setting another subject's password: the directory's identifier, a fresh hash, and every session closed. */
class SetSubjectPasswordTest {
    private val hasher = FakeHasher()
    private val kit = AccessKit(hasher = hasher)

    @Test
    fun `a subject with no password is enrolled under its directory identifier, normalised the way sign-in looks it up`() {
        val subject = kit.agents.add(" Grace@Example.test ")

        kit.setPassword.set("agent", subject.id.toString(), NEXT)

        assertThat(kit.credentials.findBySubject(subject)?.identifier).isEqualTo("grace@example.test")
        assertThat(
            kit.login
                .signIn(kit.served(subject), "GRACE@example.test", NEXT, AGENT_OF_TESTS)
                .credentials.subject,
        ).isEqualTo(subject)
        val changed = kit.audit.ofType(AccessAuditTypes.PASSWORD_CHANGED.id).single()
        assertThat(changed.detail["by"] to changed.detail["other_sessions_closed"]).isEqualTo("operator" to true)
    }

    @Test
    fun `setting a password replaces the identifier and the secret and closes every session of the subject`() {
        val subject = kit.agents.add("moved@example.test")
        kit.credentials.insert(kit.ids.next(), subject, "old@example.test", "fake:$PASSWORD", START)
        val sessions = List(2) { kit.signedIn(subject, "old@example.test") }

        kit.setPassword.set("agent", subject.id.toString(), NEXT)

        val stored = requireNotNull(kit.credentials.findBySubject(subject))
        assertThat(stored.identifier to stored.secretHash).isEqualTo("moved@example.test" to "fake:$NEXT")
        assertThat((kit.sessions as MemorySessionStore).all()).allMatch { it.revokedReason == RevocationReasons.PASSWORD_SET }
        assertThat((kit.revocations as RecordingRevocationList).cutoffs)
            .singleElement()
            .matches({ it.subject == subject && it.keptSession == null }, "one cutoff keeping no session")
        sessions.forEach { assertThat(faultOf { kit.refresh.rotate(it.refresh) }.kind).isEqualTo(FaultKind.UNAUTHORIZED) }
        assertThat(faultOf { kit.signedIn(subject, "old@example.test") }.code).isEqualTo(AccessErrorCodes.BAD_CREDENTIALS)
    }

    @Test
    fun `an identifier another subject signs in with is 409 identifier_taken, whether or not the subject had a password`() {
        kit.enrolledAgent("taken@example.test", PASSWORD)
        val newcomer = kit.agents.add("taken@example.test")
        val enrolled = kit.agents.add("taken@example.test")
        kit.credentials.insert(kit.ids.next(), enrolled, "own@example.test", "fake:$PASSWORD", START)

        listOf(newcomer, enrolled).forEach { subject ->
            val refusal = faultOf { kit.setPassword.set("agent", subject.id.toString(), NEXT) }
            assertThat(
                refusal.kind to refusal.code,
            ).describedAs(subject.toString()).isEqualTo(FaultKind.CONFLICT to AccessErrorCodes.IDENTIFIER_TAKEN)
        }

        assertThat(kit.credentials.findBySubject(newcomer)).isNull()
        assertThat(kit.credentials.findBySubject(enrolled)?.identifier).isEqualTo("own@example.test")
        assertThat(kit.audit.ofType(AccessAuditTypes.PASSWORD_CHANGED.id)).isEmpty()
        assertThat((kit.revocations as RecordingRevocationList).cutoffs).isEmpty()
    }

    @Test
    fun `a weak password, an identifier sign-in could never look up and an id that is not canonical are refused before any hash`() {
        val subject = kit.agents.add("weak@example.test")
        val blank = kit.agents.add("   ")

        assertThat(faultOf { kit.setPassword.set("agent", subject.id.toString(), "short") }.pointed())
            .containsExactly("/password" to AccessErrorCodes.WEAK_PASSWORD)
        assertThat(faultOf { kit.setPassword.set("agent", blank.id.toString(), NEXT) }.pointed())
            .containsExactly("/identifier" to RainErrorCodes.REQUIRED)
        assertThat(faultOf { kit.setPassword.set("agent", "1-1-1-1-1", NEXT) }.pointed())
            .containsExactly("/subjectId" to RainErrorCodes.INVALID_ID)
        assertThat(hasher.hashes.get()).isZero()
    }
}
