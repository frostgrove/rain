package com.gd.rain.access.internal.store

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.jooq.Tables.SESSIONS
import com.gd.rain.access.jooq.Tables.SUBJECT_CUTOFFS
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Select
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/** Sessions and subject cutoffs. Every list is a keyset page over a named index and every removal a bounded batch. */
public interface SessionStore {
    public fun insert(session: NewSession)

    public fun findByTokenHash(hash: String): StoredSession?

    public fun findByPreviousTokenHash(hash: String): StoredSession?

    public fun findById(id: UUID): StoredSession?

    /**
     * The rotation's compare-and-set: moves the session to generation `expectedGeneration + 1` with [nextHash] only
     * while it is still at [expectedGeneration] with [currentHash] and unrevoked. Answers the rows changed.
     */
    public fun swap(
        id: UUID,
        expectedGeneration: Long,
        currentHash: String,
        nextHash: String,
        now: Instant,
    ): Int

    /** Closes the session when it is open; answers the rows changed. */
    public fun close(
        id: UUID,
        now: Instant,
        reason: String,
    ): Int

    /** Closes one of [subject]'s sessions, and answers when it was closed and whether this call closed it; `null` when it is not theirs. */
    public fun revokeOne(
        subject: SubjectRef,
        session: UUID,
        now: Instant,
        reason: String,
    ): SessionClosure?

    /** Closes up to [batch] of [subject]'s open sessions issued up to [cutoffAt], except [kept]; answers how many. */
    public fun revokeBatch(
        subject: SubjectRef,
        cutoffAt: Instant,
        kept: UUID?,
        now: Instant,
        reason: String,
        batch: Int,
    ): Int

    public fun livePage(
        subject: SubjectRef,
        now: Instant,
        idleSince: Instant,
        after: SessionCursor?,
        limit: Int,
    ): List<StoredSession>

    /** Sessions closed in `(since, until]`, in closing order, from after [after]. */
    public fun revokedPage(
        since: Instant,
        until: Instant,
        after: RevokedSession?,
        limit: Int,
    ): List<RevokedSession>

    public fun deleteExpired(
        before: Instant,
        batch: Int,
    ): Int

    public fun deleteRevoked(
        before: Instant,
        batch: Int,
    ): Int

    /** Writes [cutoff] unless a later one is already recorded for the subject. */
    public fun upsertCutoff(cutoff: SubjectCutoff)

    public fun cutoffOf(subject: SubjectRef): SubjectCutoff?

    /** Cutoffs written in `(since, until]`, in cutoff order, from after [after]. */
    public fun cutoffPage(
        since: Instant,
        until: Instant,
        after: SubjectCutoff?,
        limit: Int,
    ): List<SubjectCutoff>

    public fun deleteCutoffs(
        before: Instant,
        batch: Int,
    ): Int
}

public class JooqSessionStore(
    private val dsl: DSLContext,
) : SessionStore {
    override fun insert(session: NewSession) {
        dsl
            .insertInto(SESSIONS)
            .set(SESSIONS.ID, session.id)
            .set(SESSIONS.SUBJECT_TYPE, session.subject.type.name)
            .set(SESSIONS.SUBJECT_ID, session.subject.id)
            .set(SESSIONS.TOKEN_HASH, session.tokenHash)
            .set(SESSIONS.GENERATION, 1L)
            .set(SESSIONS.USER_AGENT, session.userAgent)
            .set(SESSIONS.ADDRESS, session.address)
            .set(SESSIONS.CREATED_AT, session.createdAt.utc())
            .set(SESSIONS.LAST_USED_AT, session.createdAt.utc())
            .set(SESSIONS.EXPIRES_AT, session.expiresAt.utc())
            .execute()
    }

    override fun findByTokenHash(hash: String): StoredSession? =
        dsl.selectFrom(SESSIONS).where(SESSIONS.TOKEN_HASH.eq(hash)).fetchOne(::sessionOf)

    override fun findByPreviousTokenHash(hash: String): StoredSession? =
        dsl.selectFrom(SESSIONS).where(SESSIONS.PREVIOUS_TOKEN_HASH.eq(hash)).fetchOne(::sessionOf)

    override fun findById(id: UUID): StoredSession? = dsl.selectFrom(SESSIONS).where(SESSIONS.ID.eq(id)).fetchOne(::sessionOf)

    override fun swap(
        id: UUID,
        expectedGeneration: Long,
        currentHash: String,
        nextHash: String,
        now: Instant,
    ): Int =
        dsl
            .update(SESSIONS)
            .set(SESSIONS.TOKEN_HASH, nextHash)
            .set(SESSIONS.PREVIOUS_TOKEN_HASH, currentHash)
            .set(SESSIONS.GENERATION, expectedGeneration + 1)
            .set(SESSIONS.ROTATED_AT, now.utc())
            .set(SESSIONS.LAST_USED_AT, now.utc())
            .where(SESSIONS.ID.eq(id))
            .and(SESSIONS.GENERATION.eq(expectedGeneration))
            .and(SESSIONS.TOKEN_HASH.eq(currentHash))
            .and(SESSIONS.REVOKED_AT.isNull)
            .execute()

    override fun close(
        id: UUID,
        now: Instant,
        reason: String,
    ): Int =
        dsl
            .update(SESSIONS)
            .set(SESSIONS.REVOKED_AT, now.utc())
            .set(SESSIONS.REVOKED_REASON, reason)
            .where(SESSIONS.ID.eq(id))
            .and(SESSIONS.REVOKED_AT.isNull)
            .execute()

    override fun revokeOne(
        subject: SubjectRef,
        session: UUID,
        now: Instant,
        reason: String,
    ): SessionClosure? {
        val ofSubject =
            SESSIONS.ID
                .eq(session)
                .and(SESSIONS.SUBJECT_TYPE.eq(subject.type.name))
                .and(SESSIONS.SUBJECT_ID.eq(subject.id))
        val closed =
            dsl
                .update(SESSIONS)
                .set(SESSIONS.REVOKED_AT, now.utc())
                .set(SESSIONS.REVOKED_REASON, reason)
                .where(ofSubject)
                .and(SESSIONS.REVOKED_AT.isNull)
                .execute()
        val closedAt =
            dsl
                .select(SESSIONS.REVOKED_AT)
                .from(SESSIONS)
                .where(ofSubject)
                .fetchOne(SESSIONS.REVOKED_AT)
                ?.toInstant() ?: return null
        return SessionClosure(closedAt, closed == 1)
    }

    override fun revokeBatch(
        subject: SubjectRef,
        cutoffAt: Instant,
        kept: UUID?,
        now: Instant,
        reason: String,
        batch: Int,
    ): Int {
        require(batch >= 1) { "a revocation batch closes at least one session, got $batch" }
        val chosen =
            dsl
                .select(SESSIONS.ID)
                .from(SESSIONS)
                .where(SESSIONS.SUBJECT_TYPE.eq(subject.type.name))
                .and(SESSIONS.SUBJECT_ID.eq(subject.id))
                .and(SESSIONS.REVOKED_AT.isNull)
                .and(SESSIONS.CREATED_AT.le(cutoffAt.utc()))
                .and(kept?.let(SESSIONS.ID::ne) ?: DSL.noCondition())
                .orderBy(SESSIONS.CREATED_AT, SESSIONS.ID)
                .limit(batch)
                .forUpdate()
        return dsl
            .update(SESSIONS)
            .set(SESSIONS.REVOKED_AT, now.utc())
            .set(SESSIONS.REVOKED_REASON, reason)
            .where(SESSIONS.ID.`in`(chosen))
            .execute()
    }

    override fun livePage(
        subject: SubjectRef,
        now: Instant,
        idleSince: Instant,
        after: SessionCursor?,
        limit: Int,
    ): List<StoredSession> = dsl.fetch(livePageQuery(subject, now, idleSince, after, limit)).map { sessionOf(it) }

    /** The live page as one statement, for its plan to be read. */
    public fun livePageQuery(
        subject: SubjectRef,
        now: Instant,
        idleSince: Instant,
        after: SessionCursor?,
        limit: Int,
    ): Select<*> {
        require(limit >= 1) { "a page holds at least one row, got $limit" }
        return dsl
            .select(SESSIONS.fields().toList())
            .from(SESSIONS)
            .where(SESSIONS.SUBJECT_TYPE.eq(subject.type.name))
            .and(SESSIONS.SUBJECT_ID.eq(subject.id))
            .and(SESSIONS.REVOKED_AT.isNull)
            .and(SESSIONS.EXPIRES_AT.gt(now.utc()))
            .and(SESSIONS.LAST_USED_AT.gt(idleSince.utc()))
            .and(after?.let { DSL.row(SESSIONS.CREATED_AT, SESSIONS.ID).lt(it.createdAt.utc(), it.id) } ?: DSL.noCondition())
            .orderBy(SESSIONS.CREATED_AT.desc(), SESSIONS.ID.desc())
            .limit(limit)
    }

    override fun revokedPage(
        since: Instant,
        until: Instant,
        after: RevokedSession?,
        limit: Int,
    ): List<RevokedSession> =
        dsl.fetch(revokedPageQuery(since, until, after, limit)).map { RevokedSession(it[SESSIONS.ID], it[SESSIONS.REVOKED_AT].toInstant()) }

    public fun revokedPageQuery(
        since: Instant,
        until: Instant,
        after: RevokedSession?,
        limit: Int,
    ): Select<*> {
        require(limit >= 1) { "a page holds at least one row, got $limit" }
        return dsl
            .select(SESSIONS.ID, SESSIONS.REVOKED_AT)
            .from(SESSIONS)
            .where(SESSIONS.REVOKED_AT.gt(since.utc()))
            .and(SESSIONS.REVOKED_AT.le(until.utc()))
            .and(after?.let { DSL.row(SESSIONS.REVOKED_AT, SESSIONS.ID).gt(it.revokedAt.utc(), it.id) } ?: DSL.noCondition())
            .orderBy(SESSIONS.REVOKED_AT, SESSIONS.ID)
            .limit(limit)
    }

    override fun deleteExpired(
        before: Instant,
        batch: Int,
    ): Int = dsl.execute(deleteExpiredQuery(before, batch))

    public fun deleteExpiredQuery(
        before: Instant,
        batch: Int,
    ): org.jooq.Delete<*> {
        require(batch >= 1) { "a retention batch deletes at least one row, got $batch" }
        return dsl
            .deleteFrom(SESSIONS)
            .where(
                SESSIONS.ID.`in`(
                    dsl
                        .select(SESSIONS.ID)
                        .from(SESSIONS)
                        .where(SESSIONS.EXPIRES_AT.lt(before.utc()))
                        .orderBy(SESSIONS.EXPIRES_AT, SESSIONS.ID)
                        .limit(batch),
                ),
            )
    }

    override fun deleteRevoked(
        before: Instant,
        batch: Int,
    ): Int = dsl.execute(deleteRevokedQuery(before, batch))

    public fun deleteRevokedQuery(
        before: Instant,
        batch: Int,
    ): org.jooq.Delete<*> {
        require(batch >= 1) { "a retention batch deletes at least one row, got $batch" }
        return dsl
            .deleteFrom(SESSIONS)
            .where(
                SESSIONS.ID.`in`(
                    dsl
                        .select(SESSIONS.ID)
                        .from(SESSIONS)
                        .where(SESSIONS.REVOKED_AT.lt(before.utc()))
                        .orderBy(SESSIONS.REVOKED_AT, SESSIONS.ID)
                        .limit(batch),
                ),
            )
    }

    override fun upsertCutoff(cutoff: SubjectCutoff) {
        dsl
            .insertInto(SUBJECT_CUTOFFS)
            .set(SUBJECT_CUTOFFS.SUBJECT_TYPE, cutoff.subject.type.name)
            .set(SUBJECT_CUTOFFS.SUBJECT_ID, cutoff.subject.id)
            .set(SUBJECT_CUTOFFS.CUTOFF_AT, cutoff.cutoffAt.utc())
            .set(SUBJECT_CUTOFFS.KEPT_SESSION_ID, cutoff.keptSession)
            .onConflict(SUBJECT_CUTOFFS.SUBJECT_TYPE, SUBJECT_CUTOFFS.SUBJECT_ID)
            .doUpdate()
            .set(SUBJECT_CUTOFFS.CUTOFF_AT, DSL.excluded(SUBJECT_CUTOFFS.CUTOFF_AT))
            .set(SUBJECT_CUTOFFS.KEPT_SESSION_ID, DSL.excluded(SUBJECT_CUTOFFS.KEPT_SESSION_ID))
            .where(SUBJECT_CUTOFFS.CUTOFF_AT.le(DSL.excluded(SUBJECT_CUTOFFS.CUTOFF_AT)))
            .execute()
    }

    override fun cutoffOf(subject: SubjectRef): SubjectCutoff? =
        dsl
            .selectFrom(SUBJECT_CUTOFFS)
            .where(SUBJECT_CUTOFFS.SUBJECT_TYPE.eq(subject.type.name))
            .and(SUBJECT_CUTOFFS.SUBJECT_ID.eq(subject.id))
            .fetchOne(::cutoffOf)

    override fun cutoffPage(
        since: Instant,
        until: Instant,
        after: SubjectCutoff?,
        limit: Int,
    ): List<SubjectCutoff> = dsl.fetch(cutoffPageQuery(since, until, after, limit)).map { cutoffOf(it) }

    public fun cutoffPageQuery(
        since: Instant,
        until: Instant,
        after: SubjectCutoff?,
        limit: Int,
    ): Select<*> {
        require(limit >= 1) { "a page holds at least one row, got $limit" }
        val position =
            after?.let {
                DSL
                    .row(SUBJECT_CUTOFFS.CUTOFF_AT, SUBJECT_CUTOFFS.SUBJECT_TYPE, SUBJECT_CUTOFFS.SUBJECT_ID)
                    .gt(it.cutoffAt.utc(), it.subject.type.name, it.subject.id)
            } ?: DSL.noCondition()
        return dsl
            .select(SUBJECT_CUTOFFS.fields().toList())
            .from(SUBJECT_CUTOFFS)
            .where(SUBJECT_CUTOFFS.CUTOFF_AT.gt(since.utc()))
            .and(SUBJECT_CUTOFFS.CUTOFF_AT.le(until.utc()))
            .and(position)
            .orderBy(SUBJECT_CUTOFFS.CUTOFF_AT, SUBJECT_CUTOFFS.SUBJECT_TYPE, SUBJECT_CUTOFFS.SUBJECT_ID)
            .limit(limit)
    }

    override fun deleteCutoffs(
        before: Instant,
        batch: Int,
    ): Int = deleteCutoffsQuery(before, batch).execute()

    /** Up to [batch] cutoffs written before [before], found in `ix_subject_cutoffs_cutoff` order. */
    public fun deleteCutoffsQuery(
        before: Instant,
        batch: Int,
    ): org.jooq.Delete<*> {
        require(batch >= 1) { "a retention batch deletes at least one row, got $batch" }
        val chosen =
            dsl
                .select(SUBJECT_CUTOFFS.SUBJECT_TYPE, SUBJECT_CUTOFFS.SUBJECT_ID)
                .from(SUBJECT_CUTOFFS)
                .where(SUBJECT_CUTOFFS.CUTOFF_AT.lt(before.utc()))
                .orderBy(SUBJECT_CUTOFFS.CUTOFF_AT, SUBJECT_CUTOFFS.SUBJECT_TYPE, SUBJECT_CUTOFFS.SUBJECT_ID)
                .limit(batch)
        return dsl
            .deleteFrom(SUBJECT_CUTOFFS)
            .where(DSL.row(SUBJECT_CUTOFFS.SUBJECT_TYPE, SUBJECT_CUTOFFS.SUBJECT_ID).`in`(chosen))
    }

    private fun sessionOf(record: Record): StoredSession =
        StoredSession(
            id = record[SESSIONS.ID],
            subject = SubjectRef(SubjectType(record[SESSIONS.SUBJECT_TYPE]), record[SESSIONS.SUBJECT_ID]),
            tokenHash = record[SESSIONS.TOKEN_HASH],
            previousTokenHash = record[SESSIONS.PREVIOUS_TOKEN_HASH],
            generation = record[SESSIONS.GENERATION],
            userAgent = record[SESSIONS.USER_AGENT],
            address = record[SESSIONS.ADDRESS],
            createdAt = record[SESSIONS.CREATED_AT].toInstant(),
            lastUsedAt = record[SESSIONS.LAST_USED_AT].toInstant(),
            rotatedAt = record[SESSIONS.ROTATED_AT]?.toInstant(),
            expiresAt = record[SESSIONS.EXPIRES_AT].toInstant(),
            revokedAt = record[SESSIONS.REVOKED_AT]?.toInstant(),
            revokedReason = record[SESSIONS.REVOKED_REASON],
        )

    private fun cutoffOf(record: Record): SubjectCutoff =
        SubjectCutoff(
            subject = SubjectRef(SubjectType(record[SUBJECT_CUTOFFS.SUBJECT_TYPE]), record[SUBJECT_CUTOFFS.SUBJECT_ID]),
            cutoffAt = record[SUBJECT_CUTOFFS.CUTOFF_AT].toInstant(),
            keptSession = record[SUBJECT_CUTOFFS.KEPT_SESSION_ID],
        )
}
