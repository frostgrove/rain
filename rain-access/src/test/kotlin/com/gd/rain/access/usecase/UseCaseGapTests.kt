package com.gd.rain.access.usecase

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.revocation.RedisRevocationList
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.access.internal.store.StoredCredential
import com.gd.rain.access.internal.token.RefreshCredential
import com.gd.rain.access.internal.token.SessionFingerprints
import com.gd.rain.access.internal.usecase.Agent
import com.gd.rain.access.internal.usecase.IssuedCredentials
import com.gd.rain.access.internal.usecase.LoginUseCase
import com.gd.rain.access.internal.usecase.RefreshUseCase
import com.gd.rain.access.support.ACCESS_TTL
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AGENT_OF_TESTS
import com.gd.rain.access.support.AccessKit
import com.gd.rain.access.support.FakeHasher
import com.gd.rain.access.support.GRACE
import com.gd.rain.access.support.MemoryCredentialStore
import com.gd.rain.access.support.MemorySessionStore
import com.gd.rain.access.support.POLICY
import com.gd.rain.access.support.RecordingRevocationList
import com.gd.rain.access.support.SERVICE
import com.gd.rain.access.support.Transcript
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

private const val PASSWORD = "correct horse battery"

private fun AccessKit.signIn(
    subject: SubjectRef,
    identifier: String = "ada@example.test",
    password: String = PASSWORD,
    agent: Agent = AGENT_OF_TESTS,
): IssuedCredentials = login.signIn(served(subject), identifier, password, agent).credentials

private fun principalOf(issued: IssuedCredentials): AccessPrincipal =
    AccessPrincipal(issued.subject, issued.session, issued.sessionIssuedAt, issued.accessExpiresAt)

private fun codeOf(failure: Throwable): String = (failure as Fault).code.value

/** Gap 17: a key becoming locked writes one row; the attempts refused while it stays locked write nothing. */
class RefusedAttemptsWriteOneLockoutRowTest {
    private val kit = AccessKit()

    @Test
    fun `failures each leave a row, the lock leaves one, and refusals leave none`() {
        val subject = kit.enrolledAgent()
        repeat(POLICY.perIdentifier) {
            assertThatThrownBy { kit.signIn(subject, password = "wrong password $it") }
                .matches({ codeOf(it) == AccessErrorCodes.BAD_CREDENTIALS.value }, "bad credentials")
        }
        val afterLock = kit.audit.all.size

        repeat(5) {
            assertThatThrownBy {
                kit.signIn(
                    subject,
                )
            }.matches({ codeOf(it) == AccessErrorCodes.TOO_MANY_ATTEMPTS.value }, "too many attempts")
        }

        assertThat(kit.audit.ofType(AccessAuditTypes.SIGN_IN_FAILED.id)).hasSize(POLICY.perIdentifier)
        assertThat(kit.audit.ofType(AccessAuditTypes.LOCKOUT_OPENED.id)).singleElement().matches({
            it.detail["key_kind"] == "identifier"
        }, "identifier")
        assertThat(kit.audit.all).describedAs("refused attempts wrote nothing").hasSize(afterLock)
        assertThat(kit.audit.ofType(AccessAuditTypes.SIGNED_IN.id)).isEmpty()
    }

    @Test
    fun `a refusal carries how long the lock still holds`() {
        val subject = kit.enrolledAgent()
        repeat(POLICY.perIdentifier) { runCatching { kit.signIn(subject, password = "wrong") } }

        val refusal = runCatching { kit.signIn(subject) }.exceptionOrNull() as Fault

        assertThat(refusal.kind).isEqualTo(FaultKind.TOO_MANY_REQUESTS)
        assertThat(refusal.retryAfter).isEqualTo(POLICY.lockFor)
    }
}

/** Gap 15: a rotation that loses its compare-and-set re-reads and mints from the generation the session has now. */
class RefreshLostSwapRetryTest {
    /** Lets a test run a competing rotation right before the next swap reaches the store. */
    private class RacingSessions(
        val delegate: MemorySessionStore,
    ) : SessionStore by delegate {
        val swapsExpecting = mutableListOf<Long>()
        var beforeNextSwap: (() -> Unit)? = null
        var refuseEverySwap = false

        override fun swap(
            id: UUID,
            expectedGeneration: Long,
            currentHash: String,
            nextHash: String,
            now: Instant,
        ): Int {
            swapsExpecting += expectedGeneration
            beforeNextSwap?.also { beforeNextSwap = null }?.invoke()
            if (refuseEverySwap) return 0
            return delegate.swap(id, expectedGeneration, currentHash, nextHash, now)
        }
    }

    private val sessions = RacingSessions(MemorySessionStore())
    private val kit = AccessKit(sessions = sessions)

    @Test
    fun `losing to a concurrent rotation retries from the re-read generation and succeeds inside the grace`() {
        val subject = kit.enrolledAgent()
        val issued = kit.signIn(subject)
        sessions.beforeNextSwap = {
            sessions.delegate.swap(
                issued.session,
                1,
                RefreshCredential.digest(issued.refresh),
                RefreshCredential.digest("the other tab"),
                kit.clock.instant(),
            )
        }

        val rotated = kit.refresh.rotate(issued.refresh)

        assertThat(sessions.swapsExpecting).containsExactly(1L, 2L)
        assertThat(RefreshCredential.prefixOf(rotated.credentials.refresh)?.generation).isEqualTo(3)
        val stored = requireNotNull(sessions.findById(issued.session))
        assertThat(stored.generation).isEqualTo(3)
        assertThat(stored.tokenHash).isEqualTo(RefreshCredential.digest(rotated.credentials.refresh))
    }

    @Test
    fun `a rotation that loses every attempt is refused and closes nothing`() {
        val subject = kit.enrolledAgent()
        val issued = kit.signIn(subject)
        sessions.refuseEverySwap = true

        assertThatThrownBy { kit.refresh.rotate(issued.refresh) }.matches({ codeOf(it) == RainErrorCodes.UNAUTHENTICATED.value }, "401")

        assertThat(sessions.swapsExpecting).hasSize(kit.rotationAttempts).containsOnly(1L)
        assertThat(sessions.findById(issued.session)?.revokedAt).isNull()
    }
}

/** Gap 19: a credential verified outside the transaction is only acted on while unchanged; changed twice, the request is refused. */
class PasswordChangedBetweenPhasesRefusedTest {
    /** Moves the credential to a new version, with the same hash, each time it is locked while [changes] remain. */
    private class ChangingCredentials(
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

    private val outsideTransactions =
        FakeHasher(
            beforeVerify = { check(!TransactionSynchronizationManager.isActualTransactionActive()) { "hashing inside a transaction" } },
        )

    @Test
    fun `one change between the phases is verified again and signs in`() {
        val credentials = ChangingCredentials(MemoryCredentialStore(), changes = 1)
        val kit = AccessKit(credentials = credentials, hasher = outsideTransactions)
        val subject = kit.enrolledAgent()

        val issued = kit.signIn(subject)

        assertThat(outsideTransactions.verifications.get()).isEqualTo(2)
        assertThat(issued.subject).isEqualTo(subject)
    }

    @Test
    fun `a change in both phases refuses the sign-in and issues nothing`() {
        val credentials = ChangingCredentials(MemoryCredentialStore(), changes = 2)
        val kit = AccessKit(credentials = credentials, hasher = outsideTransactions)
        val subject = kit.enrolledAgent()

        val failure = runCatching { kit.signIn(subject) }.exceptionOrNull() as Fault

        assertThat(failure.kind).isEqualTo(FaultKind.RETRYABLE)
        assertThat(failure.code).isEqualTo(AccessErrorCodes.CREDENTIAL_CHANGED)
        assertThat((kit.sessions as MemorySessionStore).all()).isEmpty()
        assertThat(kit.audit.ofType(AccessAuditTypes.SIGNED_IN.id)).isEmpty()
    }

    @Test
    fun `a password change whose credential changes in both phases writes no hash`() {
        val credentials = ChangingCredentials(MemoryCredentialStore(), changes = 0)
        val kit = AccessKit(credentials = credentials, hasher = outsideTransactions)
        val subject = kit.enrolledAgent()
        val issued = kit.signIn(subject)
        credentials.changes = 2

        assertThatThrownBy { kit.changePassword.change(principalOf(issued), PASSWORD, "a brand new password", AGENT_OF_TESTS) }
            .matches({ codeOf(it) == AccessErrorCodes.CREDENTIAL_CHANGED.value }, "credential changed")

        assertThat(credentials.delegate.findBySubject(subject)?.secretHash).isEqualTo("fake:$PASSWORD")
    }
}

/** Gap 20: a subject type this application does not serve is a typed refusal, never a lookup that falls through. */
class UnknownSubjectTypeOnSetPasswordTest {
    private val kit = AccessKit()

    @Test
    fun `an unserved subject type is 400 unknown_subject_type naming the path segment`() {
        val refusal =
            runCatching {
                kit.setPassword.set(
                    "robot",
                    UUID.randomUUID().toString(),
                    "long enough password",
                )
            }.exceptionOrNull() as Fault

        assertThat(refusal.kind).isEqualTo(FaultKind.BAD_REQUEST)
        assertThat(refusal.code).isEqualTo(AccessErrorCodes.UNKNOWN_SUBJECT_TYPE)
        assertThat(refusal.violations.single().pointer).isEqualTo("/subjectType")
    }

    @Test
    fun `a subject type that is not even well formed is the same refusal`() {
        assertThatThrownBy { kit.setPassword.set("Robot!", UUID.randomUUID().toString(), "long enough password") }
            .matches({ codeOf(it) == AccessErrorCodes.UNKNOWN_SUBJECT_TYPE.value }, "unknown subject type")
    }

    @Test
    fun `a served type with an id its directory does not hold is 404`() {
        assertThatThrownBy { kit.setPassword.set("agent", UUID.randomUUID().toString(), "long enough password") }
            .matches({ (it as Fault).kind == FaultKind.NOT_FOUND }, "404")
    }
}

/** Gap 22: closing every session of a subject writes one cutoff whatever the number of sessions, and closes them in batches. */
class LogoutAllWritesOneRedisKeyTest {
    @Test
    fun `seven sessions cost one cutoff announcement and no session announcement`() {
        val revocations = RecordingRevocationList()
        val kit = AccessKit(revocations = revocations)
        val subject = kit.enrolledAgent()
        val issued = List(7) { kit.signIn(subject) }

        val closed = kit.everywhere.closeAll(principalOf(issued.first()), includingCurrent = false)

        assertThat(closed).isEqualTo(6)
        assertThat(
            revocations.cutoffs,
        ).singleElement().matches({ it.subject == subject && it.keptSession == issued.first().session }, "one cutoff")
        assertThat(revocations.sessions).isEmpty()
        val sessions = (kit.sessions as MemorySessionStore).all()
        assertThat(sessions.filter { it.revokedAt == null }.map { it.id }).containsExactly(issued.first().session)
    }

    @Test
    fun `the redis list writes a cutoff as one scripted command on one key`() {
        val template = mockk<StringRedisTemplate>()
        every { template.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg()) } returns 1L
        val list =
            RedisRevocationList(
                template,
                "app:revoked:",
                ACCESS_TTL,
                java.time.Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), java.time.ZoneOffset.UTC),
            )
        val subject = SubjectRef(AGENT, UUID.randomUUID())

        list.announceCutoff(
            com.gd.rain.access.internal.store
                .SubjectCutoff(subject, Instant.parse("2026-09-15T10:00:00Z"), null),
        )

        verify(exactly = 1) { template.execute(any<RedisScript<Long>>(), listOf("app:revoked:c:agent:${subject.id}"), *anyVararg()) }
    }
}

/** Gap 23: several kinds of subject share one application, addressed by type. */
class SeveralMountedSubjectsTest {
    private val kit = AccessKit()

    @Test
    fun `one identifier is two credentials in two subject types, each signing in to its own subject`() {
        val agent = kit.agents.add("ops@example.test")
        val service = kit.services.add("ops@example.test")
        kit.credentials.insert(kit.ids.next(), agent, "ops@example.test", "fake:agent password 1", kit.clock.instant())
        kit.credentials.insert(kit.ids.next(), service, "ops@example.test", "fake:service password 1", kit.clock.instant())

        val asAgent = kit.login.signIn(kit.registry.resolve("agent", "subjectType"), "OPS@example.test", "agent password 1", AGENT_OF_TESTS)
        val asService =
            kit.login.signIn(
                kit.registry.resolve("service", "subjectType"),
                "ops@example.test",
                "service password 1",
                AGENT_OF_TESTS,
            )

        assertThat(asAgent.credentials.subject).isEqualTo(agent)
        assertThat(asService.credentials.subject).isEqualTo(service)
        assertThat(
            kit.verifier
                .verify(asService.credentials.accessToken)
                ?.subject
                ?.type,
        ).isEqualTo(SERVICE)
        assertThatThrownBy {
            kit.login.signIn(kit.registry.resolve("service", "subjectType"), "ops@example.test", "agent password 1", AGENT_OF_TESTS)
        }.matches({ codeOf(it) == AccessErrorCodes.BAD_CREDENTIALS.value }, "bad credentials")
    }
}

/** Gap 23: the audit resource of a sign-out everywhere is the subject, and each role of a bulk delete is recorded on its own. */
class AuditResourcesTest {
    private val kit = AccessKit()

    @Test
    fun `a sign-out everywhere is recorded against the subject`() {
        val subject = kit.enrolledAgent()
        val issued = kit.signIn(subject)

        kit.everywhere.closeAll(principalOf(issued), includingCurrent = true)

        val event = kit.audit.ofType(AccessAuditTypes.SESSIONS_CLOSED.id).single()
        assertThat(event.resourceId).isEqualTo(subject.resourceId)
        assertThat(event.detail.keys).doesNotContain("kept_session")
    }

    @Test
    fun `a bulk delete records one row per role, and a batch holding a system role deletes nothing`() {
        val roles = listOf("triage", "billing", "support").map { kit.roles.create(it, it.replaceFirstChar(Char::uppercase)) }
        val system = kit.grants.declareSystemRole(UUID.randomUUID(), "administrator", "Administrator", true, kit.clock.instant())

        assertThatThrownBy {
            kit.roles.deleteAll(roles.map { it.id } + system)
        }.matches({ codeOf(it) == AccessErrorCodes.SYSTEM_ROLE.value }, "system role")
        assertThat(roles.map { kit.grants.roleById(it.id) }).doesNotContainNull()

        kit.roles.deleteAll(roles.map { it.id })

        val deleted = kit.audit.ofType(AccessAuditTypes.ROLE_CHANGED.id).filter { it.detail["change"] == "deleted" }
        assertThat(deleted.map { it.resourceId }).containsExactlyInAnyOrderElementsOf(roles.map { it.id.toString() })
    }
}

/** Gap 23: a log line names a session by its keyed fingerprint and never by its id. */
class SessionFingerprintInLogsTest {
    private val kit = AccessKit()

    @Test
    fun `sign-in and replay lines carry sid_fp and never the session id`() {
        val subject = kit.enrolledAgent()
        val signIns = Transcript.of(LoginUseCase::class)
        val refreshes = Transcript.of(RefreshUseCase::class)
        try {
            val issued = kit.signIn(subject)
            kit.refresh.rotate(issued.refresh)
            kit.clock.advance(GRACE.plusSeconds(1))
            runCatching { kit.refresh.rotate(issued.refresh) }

            val expected = SessionFingerprints(com.gd.rain.access.support.KEY).of(issued.session)
            val lines = signIns.lines + refreshes.lines
            assertThat(lines.map { signIns.attributes(it)[SessionFingerprints.LOG_KEY] }).contains(expected)
            assertThat(
                refreshes.lines
                    .single {
                        it.message.contains("replayed")
                    }.let { refreshes.attributes(it)[SessionFingerprints.LOG_KEY] },
            ).isEqualTo(expected)
            assertThat(signIns.written() + refreshes.written()).doesNotContain(issued.session.toString())
        } finally {
            signIns.close()
            refreshes.close()
        }
    }
}
