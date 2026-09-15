package com.gd.rain.access.internal.revocation

import com.gd.rain.access.AccessProperties
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.jobs.RecurringWork
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What one replay run announced, and whether it reached the present. */
public data class ReplayReport(
    public val sessionsAnnounced: Int,
    public val cutoffsAnnounced: Int,
    public val sessionsCaughtUp: Boolean,
    public val cutoffsCaughtUp: Boolean,
)

/**
 * Tells the revocation list again what the database records: every session closed and every subject cutoff written
 * within the last access-token lifetime, the only ones a live token can still name.
 *
 * Work is keyset pages over `(revoked_at, id)` and `(cutoff_at, subject)`, at most `pages-per-run` of each per run. A
 * watermark remembers where a run stopped; a run that reaches the present lets the next run start the window over, so a
 * revocation committed late — with an instant earlier than the watermark — is announced by the next pass. Announcing
 * again what the list already holds changes nothing. The interval is shorter than the access-token lifetime, which the
 * configuration checks.
 */
public class RevocationReplayTask(
    private val sessions: SessionStore,
    private val list: RevocationList,
    private val replay: AccessProperties.Replay,
    private val accessTtl: Duration,
    private val clock: Clock,
) : RecurringWork {
    override val name: String = NAME
    override val interval: Duration = replay.interval

    @Volatile
    private var sessionWatermark: RevokedSession? = null

    @Volatile
    private var cutoffWatermark: SubjectCutoff? = null

    override fun run() {
        val report = replayOnce()
        if (!report.sessionsCaughtUp || !report.cutoffsCaughtUp) {
            log
                .atWarn()
                .setMessage("revocation replay stopped at its page budget before reaching the present")
                .addKeyValue("sessions_announced", report.sessionsAnnounced)
                .addKeyValue("cutoffs_announced", report.cutoffsAnnounced)
                .log()
        }
    }

    public fun replayOnce(): ReplayReport {
        val now = clock.instant()
        val since = now.minus(accessTtl)
        val (sessionsAnnounced, sessionsCaughtUp) = replaySessions(since, now)
        val (cutoffsAnnounced, cutoffsCaughtUp) = replayCutoffs(since, now)
        return ReplayReport(sessionsAnnounced, cutoffsAnnounced, sessionsCaughtUp, cutoffsCaughtUp)
    }

    private fun replaySessions(
        since: Instant,
        now: Instant,
    ): Pair<Int, Boolean> {
        var position = sessionWatermark?.takeIf { it.revokedAt.isAfter(since) }
        var announced = 0
        repeat(replay.pagesPerRun) {
            val page = sessions.revokedPage(since, now, position, replay.pageSize)
            list.announceSessions(page)
            announced += page.size
            if (page.size < replay.pageSize) {
                sessionWatermark = null
                return announced to true
            }
            position = page.last()
            sessionWatermark = position
        }
        return announced to false
    }

    private fun replayCutoffs(
        since: Instant,
        now: Instant,
    ): Pair<Int, Boolean> {
        var position = cutoffWatermark?.takeIf { it.cutoffAt.isAfter(since) }
        var announced = 0
        repeat(replay.pagesPerRun) {
            val page = sessions.cutoffPage(since, now, position, replay.pageSize)
            page.forEach(list::announceCutoff)
            announced += page.size
            if (page.size < replay.pageSize) {
                cutoffWatermark = null
                return announced to true
            }
            position = page.last()
            cutoffWatermark = position
        }
        return announced to false
    }

    public companion object {
        public const val NAME: String = "access.revocation-replay"

        private val log = LoggerFactory.getLogger(RevocationReplayTask::class.java)
    }
}
