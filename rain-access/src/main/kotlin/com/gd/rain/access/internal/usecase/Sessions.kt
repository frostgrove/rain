package com.gd.rain.access.internal.usecase

import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.Profile
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.revocation.RevocationUnavailableException
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SessionCursor
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.access.internal.store.StoredSession
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.internal.token.PresentedCredential
import com.gd.rain.access.internal.token.RefreshCredential
import com.gd.rain.access.internal.token.RefreshWindow
import com.gd.rain.access.internal.token.RotationClassifier
import com.gd.rain.access.internal.token.RotationOutcome
import com.gd.rain.access.internal.token.SessionFingerprints
import com.gd.rain.audit.AuditDetail
import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A rotation that succeeded. */
public data class Rotated(
    public val credentials: IssuedCredentials,
    public val profile: Profile?,
)

/**
 * `POST <base>/auth/refresh`: the presented refresh credential is classified against the session it names and, when it
 * may rotate, swapped for the next one.
 *
 * The swap is a compare-and-set on the generation and digest the classification read. When it loses — another request
 * rotated first — the session is read again, classified again, and the next credential is minted from the generation it
 * now has, so a rotation that retries never hands out a credential for a generation the session does not reach. Losing
 * `rotation-attempts` times refuses. A replay closes the session and announces it. No transaction wraps any of it: each
 * statement is atomic, and a rollback would undo the closing a replay exists to make.
 */
public class RefreshUseCase(
    private val sessions: SessionStore,
    private val issuer: SessionIssuer,
    private val subjects: SubjectRegistry,
    private val revocations: RevocationList,
    private val window: RefreshWindow,
    private val rotationAttempts: Int,
    private val fingerprints: SessionFingerprints,
    private val clock: Clock,
) {
    init {
        require(rotationAttempts >= 1) { "a rotation is attempted at least once, got $rotationAttempts" }
    }

    public fun rotate(credential: String): Rotated {
        val prefix = RefreshCredential.prefixOf(credential) ?: throw refused()
        val digest = RefreshCredential.digest(credential)
        val now = clock.instant()
        var session = find(digest, prefix) ?: throw refused()
        repeat(rotationAttempts) {
            when (classify(session, digest, prefix.generation, now)) {
                RotationOutcome.ROTATE, RotationOutcome.ROTATE_AGAIN -> {
                    val served = subjects.served(session.subject.type) ?: throw refused()
                    if (!served.directory.isActive(session.subject.id)) throw refused()
                    val next = RefreshCredential.mint(session.generation + 1, session.id)
                    if (sessions.swap(session.id, session.generation, session.tokenHash, RefreshCredential.digest(next), now) == 1) {
                        val issued = issuer.answer(session.subject, session.id, session.createdAt, session.expiresAt, next, now)
                        return Rotated(issued, served.directory.describe(session.subject.id))
                    }
                    session = sessions.findById(session.id) ?: throw refused()
                }

                RotationOutcome.REPLAY -> {
                    closeReplayed(session, now)
                    throw refused()
                }

                RotationOutcome.UNUSABLE -> {
                    throw refused()
                }
            }
        }
        log
            .atWarn()
            .setMessage("a refresh lost every compare-and-set it attempted")
            .addKeyValue(SessionFingerprints.LOG_KEY, fingerprints.of(session.id))
            .addKeyValue("attempts", rotationAttempts)
            .log()
        throw refused()
    }

    private fun find(
        digest: String,
        prefix: RefreshCredential.Prefix,
    ): StoredSession? {
        sessions.findByTokenHash(digest)?.let { return it }
        sessions.findByPreviousTokenHash(digest)?.let { return it }
        // A credential older than the previous rotation matches neither digest; only its own prefix finds its session,
        // and only a session that has moved past the generation the credential names.
        return sessions.findById(prefix.session)?.takeIf { it.generation > prefix.generation + 1 }
    }

    private fun classify(
        session: StoredSession,
        digest: String,
        generation: Long,
        now: Instant,
    ): RotationOutcome {
        val cutoff = sessions.cutoffOf(session.subject)
        if (cutoff != null && cutoff.closes(session.id, session.createdAt)) return RotationOutcome.UNUSABLE
        return RotationClassifier.classify(
            PresentedCredential(
                digest = digest,
                current = session.tokenHash,
                previous = session.previousTokenHash,
                generation = generation,
                currentGeneration = session.generation,
                rotatedAt = session.rotatedAt,
                lastUsedAt = session.lastUsedAt,
                revoked = session.revokedAt != null,
                expiresAt = session.expiresAt,
            ),
            now,
            window,
        )
    }

    private fun closeReplayed(
        session: StoredSession,
        now: Instant,
    ) {
        sessions.close(session.id, now, RevocationReasons.REFRESH_REPLAYED)
        log
            .atWarn()
            .setMessage("a spent refresh credential was replayed; the session is closed")
            .addKeyValue(SessionFingerprints.LOG_KEY, fingerprints.of(session.id))
            .addKeyValue("subject_type", session.subject.type.name)
            .log()
        val closed = sessions.findById(session.id)?.revokedAt ?: now
        revocations.announceSessions(listOf(RevokedSession(session.id, closed)))
    }

    private fun refused() = AccessFaults.unauthenticated("the refresh credential is not usable")

    private companion object {
        val log = LoggerFactory.getLogger(RefreshUseCase::class.java)
    }
}

/**
 * Closing every session of a subject issued up to an instant, except one: the cutoff row is written inside the caller's
 * transaction; after it commits the cutoff is announced — one revocation key whatever the number of sessions — and the
 * sessions are marked closed in bounded batches, each in a transaction of its own. Until the batches finish, the
 * cutoff is what refuses those sessions: at rotation, in the session list and at every request.
 */
public class SessionClosing(
    private val sessions: SessionStore,
    private val revocations: RevocationList,
    private val transactions: AccessTransactions,
    private val batch: Int,
    private val clock: Clock,
) {
    /** Inside the caller's transaction. */
    public fun cutoff(
        subject: SubjectRef,
        kept: java.util.UUID?,
        now: Instant,
    ): SubjectCutoff = SubjectCutoff(subject, now, kept).also(sessions::upsertCutoff)

    /** After the transaction that wrote [cutoff] committed. A list that could not be told is reported after the batches ran. */
    public fun complete(
        cutoff: SubjectCutoff,
        reason: String,
    ): Long {
        val unannounced =
            try {
                revocations.announceCutoff(cutoff)
                null
            } catch (failed: RevocationUnavailableException) {
                failed
            }
        var closed = 0L
        do {
            val changed =
                transactions.inTransaction {
                    sessions.revokeBatch(cutoff.subject, cutoff.cutoffAt, cutoff.keptSession, clock.instant(), reason, batch)
                }
            closed += changed
        } while (changed == batch)
        if (unannounced != null) throw unannounced
        return closed
    }
}

public class LogoutUseCase(
    private val sessions: SessionStore,
    private val revocations: RevocationList,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val clock: Clock,
) {
    /** Closes the principal's own session and announces it. */
    public fun signOut(principal: AccessPrincipal) {
        close(principal.subject, principal.session, RevocationReasons.SIGNED_OUT, AccessAuditTypes.SIGNED_OUT)
    }

    /** Closes another session of the principal's subject; another subject's session id matches nothing and closes nothing. */
    public fun closeOwn(
        principal: AccessPrincipal,
        session: java.util.UUID,
    ) {
        close(principal.subject, session, RevocationReasons.CLOSED_BY_SUBJECT, AccessAuditTypes.SESSION_REVOKED)
    }

    private fun close(
        subject: SubjectRef,
        session: java.util.UUID,
        reason: String,
        evidence: com.gd.rain.audit.AuditEventType,
    ) {
        val closedAt =
            transactions.inTransaction {
                val closed = sessions.revokeOne(subject, session, clock.instant(), reason)
                if (closed != null) audit.record(AuditEvent(evidence, AuditOutcome.OK, session.toString()))
                closed
            } ?: return
        revocations.announceSessions(listOf(RevokedSession(session, closedAt)))
    }
}

/** `POST <base>/auth/logout-all`: every session of the principal's subject, keeping the current one unless asked not to. */
public class CloseEverywhereUseCase(
    private val closing: SessionClosing,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val clock: Clock,
) {
    public fun closeAll(
        principal: AccessPrincipal,
        includingCurrent: Boolean,
    ): Long {
        val kept = if (includingCurrent) null else principal.session
        val cutoff =
            transactions.inTransaction {
                val cutoff = closing.cutoff(principal.subject, kept, clock.instant())
                val detail = mutableListOf<Pair<String, Any>>("reason" to RevocationReasons.SIGNED_OUT_EVERYWHERE)
                kept?.let { detail += "kept_session" to it.toString() }
                audit.record(
                    AuditEvent(
                        AccessAuditTypes.SESSIONS_CLOSED,
                        AuditOutcome.OK,
                        principal.subject.resourceId,
                        AuditDetail.of(*detail.toTypedArray()),
                    ),
                )
                cutoff
            }
        return closing.complete(cutoff, RevocationReasons.SIGNED_OUT_EVERYWHERE)
    }
}

public data class SessionPage(
    public val items: List<StoredSession>,
    public val next: SessionCursor?,
)

/** A subject's open sessions, newest first, a keyset page at a time; a session a cutoff closed is not listed. */
public class SessionsQuery(
    private val sessions: SessionStore,
    private val idleTtl: Duration,
    private val clock: Clock,
) {
    public fun page(
        subject: SubjectRef,
        after: SessionCursor?,
        limit: Int,
    ): SessionPage {
        val now = clock.instant()
        val cutoff = sessions.cutoffOf(subject)
        val rows = sessions.livePage(subject, now, now.minus(idleTtl), after, limit + 1)
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { SessionCursor(it.createdAt, it.id) } else null
        return SessionPage(page.filterNot { cutoff?.closes(it.id, it.createdAt) == true }, next)
    }
}
