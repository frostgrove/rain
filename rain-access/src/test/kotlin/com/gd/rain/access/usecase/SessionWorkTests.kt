package com.gd.rain.access.usecase

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.AttemptStoreUnavailableException
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.audit.AuditUnavailableException
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.revocation.RevocationReplayTask
import com.gd.rain.access.internal.revocation.RevocationUnavailableException
import com.gd.rain.access.internal.revocation.RevocationVerdict
import com.gd.rain.access.internal.store.NewSession
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.internal.usecase.AccessFaultTranslator
import com.gd.rain.access.internal.usecase.IssuedCredentials
import com.gd.rain.access.internal.usecase.RevocationReasons
import com.gd.rain.access.internal.usecase.SessionRetentionTask
import com.gd.rain.access.support.ACCESS_TTL
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AGENT_OF_TESTS
import com.gd.rain.access.support.AccessKit
import com.gd.rain.access.support.MemorySessionStore
import com.gd.rain.access.support.RecordingRevocationList
import com.gd.rain.access.support.SESSION_TTL
import com.gd.rain.access.support.START
import com.gd.rain.access.support.Transcript
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

private fun AccessKit.signedIn(subject: SubjectRef): IssuedCredentials =
    login.signIn(served(subject), "ada@example.test", "correct horse battery", AGENT_OF_TESTS).credentials

private fun principalOf(issued: IssuedCredentials): AccessPrincipal =
    AccessPrincipal(issued.subject, issued.session, issued.sessionIssuedAt, issued.accessExpiresAt)

/** Closing one session: recorded once by the call that closed it, announced whenever it is the subject's, and nothing otherwise. */
class LogoutRecordsOnlyWhatItClosedTest {
    private val kit = AccessKit()
    private val revocations = kit.revocations as RecordingRevocationList
    private val sessions = kit.sessions as MemorySessionStore
    private val subject = kit.enrolledAgent()

    @Test
    fun `signing out closes the principal's session, records it, and announces the instant it closed`() {
        val issued = kit.signedIn(subject)
        kit.clock.advance(Duration.ofMinutes(1))

        kit.logout.signOut(principalOf(issued))

        val closed = requireNotNull(sessions.findById(issued.session))
        assertThat(closed.revokedAt to closed.revokedReason).isEqualTo(START.plusSeconds(60) to RevocationReasons.SIGNED_OUT)
        assertThat(kit.audit.ofType(AccessAuditTypes.SIGNED_OUT.id).map { it.resourceId }).containsExactly(issued.session.toString())
        assertThat(revocations.sessions).containsExactly(RevokedSession(issued.session, START.plusSeconds(60)))
    }

    @Test
    fun `closing another session of one's own subject closes that session only and records a session-revoked row`() {
        val current = kit.signedIn(subject)
        val other = kit.signedIn(subject)

        kit.logout.closeOwn(principalOf(current), other.session)

        assertThat(sessions.findById(other.session)?.revokedReason).isEqualTo(RevocationReasons.CLOSED_BY_SUBJECT)
        assertThat(sessions.findById(current.session)?.revokedAt).isNull()
        assertThat(kit.audit.ofType(AccessAuditTypes.SESSION_REVOKED.id).map { it.resourceId }).containsExactly(other.session.toString())
        assertThat(revocations.sessions.map { it.id }).containsExactly(other.session)
    }

    @Test
    fun `naming a session of another subject closes nothing, records nothing and announces nothing`() {
        val mine = kit.signedIn(subject)
        val stranger = kit.enrolledAgent("stranger@example.test")
        val theirs = kit.login.signIn(kit.served(stranger), "stranger@example.test", "correct horse battery", AGENT_OF_TESTS).credentials

        kit.logout.closeOwn(principalOf(mine), theirs.session)
        kit.logout.closeOwn(principalOf(mine), UUID.randomUUID())

        assertThat(sessions.findById(theirs.session)?.revokedAt).isNull()
        assertThat(kit.audit.ofType(AccessAuditTypes.SESSION_REVOKED.id)).isEmpty()
        assertThat(revocations.sessions).isEmpty()
    }

    @Test
    fun `closing a session that is already closed records nothing more, and announces it again with the instant it closed`() {
        val current = kit.signedIn(subject)
        val other = kit.signedIn(subject)

        kit.logout.closeOwn(principalOf(current), other.session)
        kit.clock.advance(Duration.ofMinutes(1))
        kit.logout.closeOwn(principalOf(current), other.session)

        assertThat(kit.audit.ofType(AccessAuditTypes.SESSION_REVOKED.id)).hasSize(1)
        assertThat(revocations.sessions).containsExactly(RevokedSession(other.session, START), RevokedSession(other.session, START))
        assertThat(sessions.findById(other.session)?.revokedAt).isEqualTo(START)
    }
}

/** A revocation list that cannot be asked or told anything. */
private object DownList : RevocationList {
    override fun verdict(
        session: UUID,
        subject: SubjectRef,
        sessionIssuedAt: Instant,
    ): RevocationVerdict = throw RevocationUnavailableException("read", IllegalStateException("the list is down"))

    override fun announceSessions(sessions: List<RevokedSession>): Unit =
        throw RevocationUnavailableException("written", IllegalStateException("the list is down"))

    override fun announceCutoff(cutoff: SubjectCutoff): Unit =
        throw RevocationUnavailableException("written", IllegalStateException("the list is down"))
}

/** The database is the record: a list that cannot be told stops no session from closing, and the caller hears it was not told. */
class ClosingEverySessionWithTheListDownTest {
    @Test
    fun `a cutoff the list could not be told still closes every session in batches, then reports the list unavailable`() {
        val kit = AccessKit(revocations = DownList)
        val subject = kit.enrolledAgent()
        val issued = List(3) { kit.signedIn(subject) }

        assertThatThrownBy { kit.everywhere.closeAll(principalOf(issued.first()), includingCurrent = true) }
            .isInstanceOf(RevocationUnavailableException::class.java)

        assertThat((kit.sessions as MemorySessionStore).all()).hasSize(3).allMatch {
            it.revokedReason == RevocationReasons.SIGNED_OUT_EVERYWHERE
        }
        assertThat(kit.sessions.cutoffOf(subject)?.cutoffAt).isEqualTo(START)
        assertThat(kit.audit.ofType(AccessAuditTypes.SESSIONS_CLOSED.id)).hasSize(1)
    }
}

/** Retention and replay are bounded per run, and a run that stops at its budget says so in the log. */
class RecurringAccessWorkReportsItsBudgetTest {
    private val clock = MutableClock(START)
    private val store = MemorySessionStore()
    private val list = RecordingRevocationList()
    private val subject = SubjectRef(AGENT, UUID.randomUUID())

    private fun session(
        createdAt: Instant,
        expiresAt: Instant = createdAt.plus(SESSION_TTL),
    ): UUID = UUID.randomUUID().also { store.insert(NewSession(it, subject, "digest-$it", null, "203.0.113.7", createdAt, expiresAt)) }

    private fun closedAt(at: Instant): UUID = session(at.minusSeconds(60)).also { store.close(it, at, RevocationReasons.SIGNED_OUT) }

    private val replay =
        RevocationReplayTask(store, list, AccessProperties.Replay(Duration.ofMinutes(1), pageSize = 2, pagesPerRun = 2), ACCESS_TTL, clock)

    @Test
    fun `a retention run that stops at its batch budget says so, and a run that catches up says nothing`() {
        repeat(5) { session(START.minus(Duration.ofDays(40)), START.minus(Duration.ofDays(10))) }
        val task = SessionRetentionTask(store, Duration.ofDays(7), SESSION_TTL, Duration.ofHours(1), 2, 2, clock)
        Transcript.of(SessionRetentionTask::class).use { log ->
            task.run()
            val stopped = log.lines.single()
            assertThat(stopped.formattedMessage).contains("stopped at its batch budget")
            assertThat(log.attributes(stopped)["expired_deleted"]).isEqualTo("4")

            task.run()

            assertThat(log.lines).hasSize(1)
        }
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun `a replay run that stops at its page budget says so, and the next run continues from where it stopped`() {
        val closed = List(5) { closedAt(START.minusSeconds(60L - it)) }
        Transcript.of(RevocationReplayTask::class).use { log ->
            replay.run()
            val stopped = log.lines.single()
            assertThat(stopped.formattedMessage).contains("stopped at its page budget")
            assertThat(log.attributes(stopped)["sessions_announced"]).isEqualTo("4")

            val next = replay.replayOnce()

            assertThat(next.sessionsAnnounced to next.sessionsCaughtUp).isEqualTo(1 to true)
            assertThat(log.lines).hasSize(1)
        }
        assertThat(list.sessions.map { it.id }).containsExactlyElementsOf(closed)
    }

    @Test
    fun `once the window has moved past where a run stopped, the next run replays only what the window now holds`() {
        repeat(5) { closedAt(START.minus(Duration.ofMinutes(4)).plusSeconds(it.toLong())) }
        assertThat(replay.replayOnce().sessionsCaughtUp).isFalse()
        clock.advance(Duration.ofMinutes(2))
        val fresh = closedAt(START.plus(Duration.ofMinutes(1)))

        val next = replay.replayOnce()

        assertThat(next.sessionsAnnounced to next.sessionsCaughtUp).isEqualTo(1 to true)
        assertThat(list.sessions.last().id).isEqualTo(fresh)
    }

    @Test
    fun `subject cutoffs are replayed a page at a time from a watermark of their own`() {
        val cutOff =
            List(5) { index ->
                SubjectRef(AGENT, UUID.randomUUID()).also { store.upsertCutoff(SubjectCutoff(it, START.minusSeconds(30L - index), null)) }
            }

        val first = replay.replayOnce()
        val second = replay.replayOnce()

        assertThat(first.cutoffsAnnounced to first.cutoffsCaughtUp).isEqualTo(4 to false)
        assertThat(second.cutoffsAnnounced to second.cutoffsCaughtUp).isEqualTo(1 to true)
        assertThat(first.sessionsAnnounced to first.sessionsCaughtUp).isEqualTo(0 to true)
        assertThat(list.cutoffs.map { it.subject }).containsExactlyInAnyOrderElementsOf(cutOff)
    }
}

/** The failures below a fault that rain-access names: each unavailable store a 503 of its own, found wherever it is wrapped. */
class AccessFaultTranslatorTest {
    @Test
    fun `an unavailable audit store, revocation list or attempt store is a retryable refusal naming it, wherever it sits in the chain`() {
        val cases =
            mapOf(
                RuntimeException("wrapped", AuditUnavailableException("access.signed-in", IllegalStateException("down"))) to
                    AccessErrorCodes.AUDIT_UNAVAILABLE,
                RevocationUnavailableException("read", IllegalStateException("down")) to AccessErrorCodes.REVOCATION_UNAVAILABLE,
                IllegalStateException("outer", AttemptStoreUnavailableException(IllegalStateException("down"))) to
                    RainErrorCodes.UNAVAILABLE,
            )

        cases.forEach { (failure, code) ->
            val fault = requireNotNull(AccessFaultTranslator.translate(failure)) { "no fault for $failure" }
            assertThat(fault.kind to fault.code).isEqualTo(FaultKind.RETRYABLE to code)
            assertThat(fault.cause).isSameAs(failure)
        }
    }

    @Test
    fun `any other failure is not this translator's to name`() {
        assertThat(AccessFaultTranslator.translate(IllegalStateException("outer", RuntimeException("inner")))).isNull()
    }
}
