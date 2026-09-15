package com.gd.rain.access.support

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.MemoryAttemptLimiter
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.password.HashingBulkhead
import com.gd.rain.access.internal.password.PasswordHasher
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.store.NewSession
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.token.RefreshCredential
import com.gd.rain.access.internal.token.RefreshWindow
import com.gd.rain.access.internal.token.SessionFingerprints
import com.gd.rain.access.internal.usecase.AccessTransactions
import com.gd.rain.access.internal.usecase.CloseEverywhereUseCase
import com.gd.rain.access.internal.usecase.IssuedCredentials
import com.gd.rain.access.internal.usecase.LoginUseCase
import com.gd.rain.access.internal.usecase.PasswordRules
import com.gd.rain.access.internal.usecase.RefreshUseCase
import com.gd.rain.access.internal.usecase.RoleAdministration
import com.gd.rain.access.internal.usecase.SessionClosing
import com.gd.rain.access.internal.usecase.SessionIssuer
import com.gd.rain.access.internal.usecase.SessionsQuery
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.audit.AuditRecorder
import java.time.Instant
import java.util.UUID

const val DEFAULT_PASSWORD: String = "correct horse battery"

/** The use cases over a real database: the jOOQ stores, real transactions, and rain-audit's recorder. */
class DatabaseKit(
    val db: AccessDatabase,
    val revocations: RevocationList = RecordingRevocationList(),
    val hasher: PasswordHasher = FakeHasher(),
    val bulkhead: HashingBulkhead = bulkheadOf(),
    val audit: AuditRecorder = db.auditRecorder(),
    rotationAttempts: Int = 3,
) {
    val clock = db.clock
    val ids = db.ids
    val transactions = AccessTransactions(db.transactions)
    val trail = AuditTrail(audit)
    val limiter: AttemptLimiter = MemoryAttemptLimiter(POLICY, 10_000, clock)
    val tokens = AccessTokenIssuer(KEY, ISSUER, AUDIENCE, ACCESS_TTL)
    val fingerprints = SessionFingerprints(KEY)
    val rules = PasswordRules(PASSWORD_RULES)
    val agents = MemoryDirectory(AGENT)
    val services = MemoryDirectory(SERVICE)
    val registry = SubjectRegistry(listOf(mounted(AGENT), mounted(SERVICE)), listOf(agents, services), emptyList())
    val issuer = SessionIssuer(db.sessions, tokens, ids, clock, SESSION_TTL)
    val closing = SessionClosing(db.sessions, revocations, transactions, 2, clock)
    val login = LoginUseCase(db.credentials, issuer, hasher, bulkhead, limiter, POLICY, rules, trail, transactions, fingerprints, clock)
    val refresh =
        RefreshUseCase(db.sessions, issuer, registry, revocations, RefreshWindow(GRACE, IDLE_TTL), rotationAttempts, fingerprints, clock)
    val everywhere = CloseEverywhereUseCase(closing, trail, transactions, clock)
    val sessionsQuery = SessionsQuery(db.sessions, IDLE_TTL, clock)
    val roles = RoleAdministration(db.grants, trail, transactions, ids, 2, clock)

    /** An active subject of [directory]'s type with a password credential. */
    fun enrol(
        directory: MemoryDirectory,
        identifier: String,
        password: String = DEFAULT_PASSWORD,
    ): SubjectRef {
        val subject = directory.add(identifier)
        db.credentials.insert(ids.next(), subject, identifier, "fake:$password", clock.instant())
        return subject
    }

    fun signIn(
        subject: SubjectRef,
        identifier: String,
        password: String = DEFAULT_PASSWORD,
    ): IssuedCredentials = login.signIn(requireNotNull(registry.served(subject.type)), identifier, password, AGENT_OF_TESTS).credentials
}

/** An open session of [subject] written straight to the store, last used when it was created. */
fun AccessDatabase.openSession(
    subject: SubjectRef,
    createdAt: Instant,
    expiresAt: Instant = createdAt.plus(SESSION_TTL),
): UUID {
    val id = ids.next()
    sessions.insert(NewSession(id, subject, RefreshCredential.digest("1.$id.test"), "rain-test/1", "203.0.113.7", createdAt, expiresAt))
    return id
}
