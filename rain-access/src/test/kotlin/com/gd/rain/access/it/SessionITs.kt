package com.gd.rain.access.it

import com.gd.rain.access.AccessProperties
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.revocation.RedisRevocationList
import com.gd.rain.access.internal.revocation.RevocationReplayTask
import com.gd.rain.access.internal.revocation.RevocationVerdict
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.internal.token.RefreshCredential
import com.gd.rain.access.internal.usecase.Rotated
import com.gd.rain.access.internal.usecase.SessionRetentionTask
import com.gd.rain.access.support.ACCESS_TTL
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AccessDatabase
import com.gd.rain.access.support.DatabaseKit
import com.gd.rain.access.support.GRACE
import com.gd.rain.access.support.IDLE_TTL
import com.gd.rain.access.support.RecordingRevocationList
import com.gd.rain.access.support.RedisFactories
import com.gd.rain.access.support.SESSION_TTL
import com.gd.rain.access.support.START
import com.gd.rain.access.support.openSession
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.test.QueryPlans
import com.gd.rain.test.RainRedis
import com.gd.rain.test.RedisPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val IDENTIFIER = "ada@example.test"

private fun refusedAsUnauthenticated(block: () -> Unit) {
    assertThatThrownBy(block).matches({ (it as Fault).kind == FaultKind.UNAUTHORIZED }, "401")
}

/** Gap 15: two requests rotating one credential at once both succeed through the grace; the original after the grace is a replay. */
@Tag("integration")
class ConcurrentRefreshThenGraceIT {
    private val db = AccessDatabase.fresh("access_refresh_race")
    private val revocations = RecordingRevocationList()
    private val kit = DatabaseKit(db, revocations = revocations)

    @Test
    fun `two tabs refreshing at once both get credentials, and the first credential presented after the grace closes the session`() {
        val issued = kit.signIn(kit.enrol(kit.agents, IDENTIFIER), IDENTIFIER)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes =
            try {
                List(2) {
                    executor.submit(
                        Callable {
                            barrier.await(30, TimeUnit.SECONDS)
                            runCatching { kit.refresh.rotate(issued.refresh) }
                        },
                    )
                }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertThat(outcomes).describedAs(outcomes.toString()).allMatch { it.isSuccess }
        val rotated: List<Rotated> = outcomes.map { it.getOrThrow() }
        val session = requireNotNull(db.sessions.findById(issued.session))
        assertThat(session.generation).isEqualTo(3)
        assertThat(rotated.map { RefreshCredential.digest(it.credentials.refresh) })
            .containsExactlyInAnyOrder(session.tokenHash, session.previousTokenHash)
        assertThat(rotated.map { RefreshCredential.prefixOf(it.credentials.refresh)?.generation }).containsExactlyInAnyOrder(2L, 3L)

        db.clock.set(START.plus(GRACE).plusMillis(1))
        refusedAsUnauthenticated { kit.refresh.rotate(issued.refresh) }

        assertThat(requireNotNull(db.sessions.findById(issued.session)).revokedAt).isNotNull()
        assertThat(revocations.sessions.map { it.id }).contains(issued.session)
        rotated.forEach { refusedAsUnauthenticated { kit.refresh.rotate(it.credentials.refresh) } }
    }
}

/** Every branch of a rotation against the real session store. */
@Tag("integration")
class RefreshRotationIT {
    private val db = AccessDatabase.fresh("access_refresh_rotation")
    private val revocations = RecordingRevocationList()
    private val kit = DatabaseKit(db, revocations = revocations)
    private val subject: SubjectRef = kit.enrol(kit.agents, IDENTIFIER)

    @Test
    fun `the current credential rotates to the next generation with a token for the same session`() {
        val issued = kit.signIn(subject, IDENTIFIER)
        db.clock.set(START.plusSeconds(60))

        val rotated = kit.refresh.rotate(issued.refresh).credentials

        assertThat(rotated.session).isEqualTo(issued.session)
        assertThat(RefreshCredential.prefixOf(rotated.refresh)?.generation).isEqualTo(2)
        val stored = requireNotNull(db.sessions.findById(issued.session))
        assertThat(stored.tokenHash).isEqualTo(RefreshCredential.digest(rotated.refresh))
        assertThat(stored.previousTokenHash).isEqualTo(RefreshCredential.digest(issued.refresh))
        assertThat(stored.rotatedAt).isEqualTo(START.plusSeconds(60))
    }

    @Test
    fun `the previous credential inside the grace rotates again, and after it is a replay that closes and announces the session`() {
        val issued = kit.signIn(subject, IDENTIFIER)
        kit.refresh.rotate(issued.refresh)

        db.clock.set(START.plus(GRACE))
        assertThat(
            kit.refresh
                .rotate(issued.refresh)
                .credentials.session,
        ).isEqualTo(issued.session)

        val again = requireNotNull(db.sessions.findById(issued.session))
        db.clock.set(START.plus(GRACE).plus(GRACE).plusSeconds(1))
        refusedAsUnauthenticated { kit.refresh.rotate(issued.refresh) }
        assertThat(again.generation).isEqualTo(3)
        assertThat(requireNotNull(db.sessions.findById(issued.session)).revokedAt).isNotNull()
        assertThat(revocations.sessions.map { it.id }).containsExactly(issued.session)
    }

    @Test
    fun `an idle session, a closed session and a credential it never issued are refused without closing anything`() {
        val idle = kit.signIn(subject, IDENTIFIER)
        val forged = RefreshCredential.mint(1, idle.session)
        refusedAsUnauthenticated { kit.refresh.rotate(forged) }
        assertThat(requireNotNull(db.sessions.findById(idle.session)).revokedAt).isNull()

        val closed = kit.signIn(subject, IDENTIFIER)
        db.sessions.close(closed.session, START, "signed-out")
        refusedAsUnauthenticated { kit.refresh.rotate(closed.refresh) }

        db.clock.set(START.plus(IDLE_TTL).plusSeconds(1))
        refusedAsUnauthenticated { kit.refresh.rotate(idle.refresh) }
        assertThat(revocations.sessions).isEmpty()
    }
}

/** Gap 16: what the database records as closed within a token's lifetime is told to the list again, a bounded page at a time. */
@Tag("integration")
class RevocationReplayIT {
    private val db = AccessDatabase.fresh("access_replay")

    @Test
    fun `the window is replayed in bounded runs from a watermark, ties included, and from the start once caught up`() {
        val factory = RedisFactories.of(RainRedis.shared(RedisPolicy.RETAINING))
        try {
            val list = RedisRevocationList(StringRedisTemplate(factory), "replay-${UUID.randomUUID()}:", ACCESS_TTL, db.clock)
            val subject = SubjectRef(AGENT, UUID.randomUUID())
            val cutOff = SubjectRef(AGENT, UUID.randomUUID())
            val sessions = List(6) { db.openSession(subject, START.minus(Duration.ofHours(1)).plusSeconds(it.toLong())) }
            db.sessions.close(sessions[0], START.minus(ACCESS_TTL).minusSeconds(1), "signed-out")
            sessions.drop(1).forEach { db.sessions.close(it, START.minusSeconds(60), "signed-out") }
            db.sessions.upsertCutoff(SubjectCutoff(cutOff, START.minusSeconds(30), null))
            val task = RevocationReplayTask(db.sessions, list, AccessProperties.Replay(Duration.ofMinutes(1), 2, 2), ACCESS_TTL, db.clock)

            val first = task.replayOnce()
            val second = task.replayOnce()
            val third = task.replayOnce()

            assertThat(first.sessionsAnnounced to first.sessionsCaughtUp).isEqualTo(4 to false)
            assertThat(second.sessionsAnnounced to second.sessionsCaughtUp).isEqualTo(1 to true)
            // A pass that caught up leaves no watermark: the next one tells the list the whole window again, so a list that
            // lost its keys since is repaired rather than trusted.
            assertThat(third.sessionsAnnounced to third.sessionsCaughtUp).isEqualTo(4 to false)
            assertThat(listOf(first, second, third).map { it.cutoffsAnnounced }).containsOnly(1)
            sessions
                .drop(
                    1,
                ).forEach { assertThat(list.verdict(it, subject, START.minus(Duration.ofHours(1)))).isEqualTo(RevocationVerdict.REVOKED) }
            assertThat(list.verdict(sessions[0], subject, START.minus(Duration.ofHours(1)))).isEqualTo(RevocationVerdict.LIVE)
            assertThat(list.verdict(UUID.randomUUID(), cutOff, START.minusSeconds(31))).isEqualTo(RevocationVerdict.REVOKED)
        } finally {
            factory.destroy()
        }
    }
}

/** Gap 22: retention deletes what is due in bounded batches, each one statement over its own index, and nothing else. */
@Tag("integration")
class SessionsRetentionIT {
    private val db = AccessDatabase.fresh("access_retention")
    private val keepFor = Duration.ofDays(7)

    @Test
    fun `expired, long-closed sessions and old cutoffs go in bounded batches over runs, and what is still due stays`() {
        val subject = SubjectRef(AGENT, UUID.randomUUID())
        repeat(5) { db.openSession(subject, START.minus(Duration.ofDays(40)), START.minus(Duration.ofDays(10))) }
        val expiredRecently = db.openSession(subject, START.minus(Duration.ofDays(31)), START.minus(Duration.ofDays(1)))
        repeat(3) {
            db
                .openSession(
                    subject,
                    START.minus(Duration.ofDays(9)),
                ).also { db.sessions.close(it, START.minus(Duration.ofDays(8)), "signed-out") }
        }
        val closedRecently =
            db.openSession(subject, START.minus(Duration.ofDays(2))).also {
                db.sessions.close(it, START.minus(Duration.ofDays(1)), "signed-out")
            }
        val live = List(2) { db.openSession(subject, START.minus(Duration.ofHours(1))) }
        repeat(
            3,
        ) { db.sessions.upsertCutoff(SubjectCutoff(SubjectRef(AGENT, UUID.randomUUID()), START.minus(SESSION_TTL).minusSeconds(1), null)) }
        val recentCutoff = SubjectCutoff(SubjectRef(AGENT, UUID.randomUUID()), START.minus(Duration.ofDays(1)), null)
        db.sessions.upsertCutoff(recentCutoff)
        val task = SessionRetentionTask(db.sessions, keepFor, SESSION_TTL, Duration.ofHours(1), 2, 2, db.clock)

        val first = task.retainOnce()
        val second = task.retainOnce()

        assertThat(listOf(first.expiredDeleted, first.revokedDeleted, first.cutoffsDeleted)).containsExactly(4, 3, 3)
        assertThat(first.caughtUp).isFalse()
        assertThat(listOf(second.expiredDeleted, second.revokedDeleted, second.cutoffsDeleted)).containsExactly(1, 0, 0)
        assertThat(second.caughtUp).isTrue()
        assertThat(db.count("SELECT count(*) FROM rain_access.sessions")).isEqualTo(4)
        listOf(expiredRecently, closedRecently, *live.toTypedArray()).forEach { assertThat(db.sessions.findById(it)).isNotNull() }
        assertThat(db.sessions.cutoffOf(recentCutoff.subject)).isEqualTo(recentCutoff)
        assertThat(db.count("SELECT count(*) FROM rain_access.subject_cutoffs")).isEqualTo(1)
    }

    @Test
    fun `each retention statement deletes a limited batch found through its own index`() {
        val before = START.minus(keepFor)
        val plans =
            mapOf(
                "ix_sessions_expired" to db.sessions.deleteExpiredQuery(before, 500),
                "ix_sessions_revoked" to db.sessions.deleteRevokedQuery(before, 500),
                "ix_subject_cutoffs_cutoff" to db.sessions.deleteCutoffsQuery(START.minus(SESSION_TTL), 500),
            )

        plans.forEach { (index, query) ->
            val plan = QueryPlans.explain(db.dataSource, db.dsl.renderInlined(query), generic = false)
            assertThat(plan.usesIndex(index)).describedAs("uses $index:\n$plan").isTrue()
            assertThat(plan.hasLimit()).describedAs("limited:\n$plan").isTrue()
            assertThat(plan.scansSequentially("sessions") || plan.scansSequentially("subject_cutoffs")).describedAs(plan.json).isFalse()
        }
    }
}
