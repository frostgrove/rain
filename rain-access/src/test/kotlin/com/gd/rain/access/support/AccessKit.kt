package com.gd.rain.access.support

import com.gd.rain.access.AccessProperties
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectRegistrar
import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.AttemptPolicy
import com.gd.rain.access.internal.attempt.MemoryAttemptLimiter
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.password.HashingBulkhead
import com.gd.rain.access.internal.password.PasswordHasher
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.token.AccessTokenVerifier
import com.gd.rain.access.internal.token.RefreshWindow
import com.gd.rain.access.internal.token.SessionFingerprints
import com.gd.rain.access.internal.usecase.AccessTransactions
import com.gd.rain.access.internal.usecase.Agent
import com.gd.rain.access.internal.usecase.ChangePasswordUseCase
import com.gd.rain.access.internal.usecase.CloseEverywhereUseCase
import com.gd.rain.access.internal.usecase.GrantsAdministration
import com.gd.rain.access.internal.usecase.LoginUseCase
import com.gd.rain.access.internal.usecase.LogoutUseCase
import com.gd.rain.access.internal.usecase.PasswordRules
import com.gd.rain.access.internal.usecase.RefreshUseCase
import com.gd.rain.access.internal.usecase.RoleAdministration
import com.gd.rain.access.internal.usecase.SessionClosing
import com.gd.rain.access.internal.usecase.SessionIssuer
import com.gd.rain.access.internal.usecase.SessionsQuery
import com.gd.rain.access.internal.usecase.SetSubjectPasswordUseCase
import com.gd.rain.access.internal.usecase.SignUpUseCase
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.test.MutableClock
import java.time.Duration

val KEY: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }
const val ISSUER: String = "rain-test"
const val AUDIENCE: String = "rain-test-api"
val ACCESS_TTL: Duration = Duration.ofMinutes(5)
val SESSION_TTL: Duration = Duration.ofDays(30)
val IDLE_TTL: Duration = Duration.ofDays(7)
val GRACE: Duration = Duration.ofSeconds(10)
val PASSWORD_RULES: AccessProperties.Password = AccessProperties.Password(revokeOtherSessionsOnChange = true)
val POLICY: AttemptPolicy =
    AttemptPolicy(perIdentifier = 3, perAddress = 50, window = Duration.ofMinutes(15), lockFor = Duration.ofMinutes(15))
val AGENT_OF_TESTS: Agent = Agent("rain-test/1", "203.0.113.7")

/**
 * The use cases over in-memory stores, each piece replaceable, so a unit test asserts behaviour without a database: time
 * from a [MutableClock], transactions from a manager with no resource.
 */
class AccessKit(
    val clock: MutableClock = MutableClock(START),
    val credentials: CredentialStore = MemoryCredentialStore(),
    val sessions: SessionStore = MemorySessionStore(),
    val grants: MemoryGrants = MemoryGrants(),
    val audit: RecordingAuditRecorder = RecordingAuditRecorder(),
    val hasher: PasswordHasher = FakeHasher(),
    val bulkhead: HashingBulkhead = bulkheadOf(),
    val revocations: RevocationList = RecordingRevocationList(),
    limiter: AttemptLimiter? = null,
    registrars: List<SubjectRegistrar> = emptyList(),
    revokeOtherSessions: Boolean = true,
    val rotationAttempts: Int = 3,
) {
    val manager = NoOpTransactionManager()
    val transactions = AccessTransactions(manager)
    val trail = AuditTrail(audit)
    val ids: IdGenerator = IdGenerator(UuidV7Ids::next)
    val limiter: AttemptLimiter = limiter ?: MemoryAttemptLimiter(POLICY, 10_000, clock)
    val tokens = AccessTokenIssuer(KEY, ISSUER, AUDIENCE, ACCESS_TTL)
    val verifier = AccessTokenVerifier(KEY, ISSUER, AUDIENCE, clock)
    val fingerprints = SessionFingerprints(KEY)
    val rules = PasswordRules(PASSWORD_RULES)
    val agents = MemoryDirectory(AGENT)
    val services = MemoryDirectory(SERVICE)
    val registry = SubjectRegistry(listOf(mounted(AGENT), mounted(SERVICE)), listOf(agents, services), registrars)
    val issuer = SessionIssuer(sessions, tokens, ids, clock, SESSION_TTL)
    val closing = SessionClosing(sessions, revocations, transactions, 2, clock)

    val login = LoginUseCase(credentials, issuer, hasher, bulkhead, this.limiter, POLICY, rules, trail, transactions, fingerprints, clock)
    val signUp = SignUpUseCase(credentials, grants, issuer, hasher, bulkhead, rules, trail, transactions, ids, clock)
    val refresh =
        RefreshUseCase(sessions, issuer, registry, revocations, RefreshWindow(GRACE, IDLE_TTL), rotationAttempts, fingerprints, clock)
    val logout = LogoutUseCase(sessions, revocations, trail, transactions, clock)
    val everywhere = CloseEverywhereUseCase(closing, trail, transactions, clock)
    val sessionsQuery = SessionsQuery(sessions, IDLE_TTL, clock)
    val changePassword =
        ChangePasswordUseCase(
            credentials,
            hasher,
            bulkhead,
            this.limiter,
            POLICY,
            rules,
            closing,
            revokeOtherSessions,
            trail,
            transactions,
            clock,
        )
    val setPassword = SetSubjectPasswordUseCase(registry, credentials, hasher, bulkhead, rules, closing, trail, transactions, ids, clock)
    val grantsAdministration = GrantsAdministration(grants, noOpLocks(manager), trail, transactions, 4, clock)
    val roles = RoleAdministration(grants, trail, transactions, ids, 2, clock)

    /** An active agent with a password credential. */
    fun enrolledAgent(
        identifier: String = "ada@example.test",
        password: String = "correct horse battery",
    ): SubjectRef {
        val subject = agents.add(identifier)
        credentials.insert(ids.next(), subject, identifier, "fake:$password", clock.instant())
        return subject
    }

    fun served(subject: SubjectRef) = requireNotNull(registry.served(subject.type))
}
