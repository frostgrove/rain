package com.gd.rain.jobs.internal.ledger

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.jooq.Tables.JOB_INTENT
import org.jooq.DSLContext
import org.jooq.Delete
import org.jooq.Record1
import org.jooq.Select
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/** A running invocation whose lease lapsed, as the reaper locked it. */
internal data class LapsedInvocation(
    val id: UUID,
    val definition: String,
    val retrySpent: Int,
    val retryLimit: Int,
    val version: Long,
    val leaseExpiresAt: Instant,
)

/** The housekeeping side of the ledger. */
internal interface HousekeepingLedger {
    /** Inside a transaction: running invocations whose lease expired at or before [now], earliest first, locked, skipping rows another transaction holds. */
    fun lapsed(
        now: Instant,
        batch: Int,
    ): List<LapsedInvocation>

    /** Back to queued with one retry charged, if the row is still the version [lapsed] read. */
    fun requeue(
        lapsed: LapsedInvocation,
        eligibleAt: Instant,
        code: String,
        message: String,
    ): Boolean

    /** Dead, releasing its reservations in the same statement, if the row is still the version [lapsed] read. */
    fun bury(
        lapsed: LapsedInvocation,
        code: String,
        message: String,
        now: Instant,
    ): Boolean

    /** Deletes at most [batch] terminal invocations of [profile] finished before [before]; one statement. */
    fun deleteTerminal(
        profile: String,
        before: Instant,
        batch: Int,
    ): Int

    /** Deletes at most [batch] released reservations of [profile] released before [before]; one statement. */
    fun deleteReleasedIntents(
        profile: String,
        before: Instant,
        batch: Int,
    ): Int

    /** The smallest profile after [after] (or the smallest at all) that owns a terminal invocation; one index seek. */
    fun nextTerminalProfile(after: String?): String?

    /** The smallest profile after [after] (or the smallest at all) that owns a released reservation; one index seek. */
    fun nextReleasedIntentProfile(after: String?): String?
}

internal class JooqHousekeepingLedger(
    private val dsl: DSLContext,
    private val attempts: JooqAttemptLedger,
) : HousekeepingLedger {
    private val j = JooqAttemptLedger.J

    override fun lapsed(
        now: Instant,
        batch: Int,
    ): List<LapsedInvocation> =
        lapsedQuery(now, batch).fetch { row ->
            LapsedInvocation(
                id = row.value1(),
                definition = row.value2(),
                retrySpent = row.value3(),
                retryLimit = row.value4(),
                version = row.value5(),
                leaseExpiresAt = row.value6().toInstant(),
            )
        }

    fun lapsedQuery(
        now: Instant,
        batch: Int,
    ) = dsl
        .select(j.ID, j.DEFINITION, j.RETRY_SPENT, j.RETRY_LIMIT, j.VERSION, j.LEASE_EXPIRES_AT)
        .from(j)
        .where(j.STATE.eq(JobState.RUNNING.wire))
        .and(j.LEASE_EXPIRES_AT.le(at(now)))
        .orderBy(j.LEASE_EXPIRES_AT)
        .limit(batch)
        .forUpdate()
        .skipLocked()

    override fun requeue(
        lapsed: LapsedInvocation,
        eligibleAt: Instant,
        code: String,
        message: String,
    ): Boolean =
        dsl
            .update(j)
            .set(j.STATE, JobState.QUEUED.wire)
            .set(j.RETRY_SPENT, j.RETRY_SPENT.plus(1))
            .set(j.ELIGIBLE_AT, at(eligibleAt))
            .set(j.FAILURE_CODE, code)
            .set(j.FAILURE_MESSAGE, JooqAttemptLedger.bounded(message))
            .setNull(j.LEASE_TOKEN)
            .setNull(j.LEASE_EXPIRES_AT)
            .set(j.VERSION, j.VERSION.plus(1L))
            .where(j.ID.eq(lapsed.id))
            .and(j.VERSION.eq(lapsed.version))
            .and(j.STATE.eq(JobState.RUNNING.wire))
            .execute() == 1

    override fun bury(
        lapsed: LapsedInvocation,
        code: String,
        message: String,
        now: Instant,
    ): Boolean =
        attempts.terminal(
            lapsed.id,
            j.VERSION.eq(lapsed.version).and(j.STATE.eq(JobState.RUNNING.wire)),
            JobState.DEAD,
            code,
            message,
            now,
        ) == 1

    override fun deleteTerminal(
        profile: String,
        before: Instant,
        batch: Int,
    ): Int = deleteTerminalQuery(profile, before, batch).execute()

    override fun deleteReleasedIntents(
        profile: String,
        before: Instant,
        batch: Int,
    ): Int = deleteReleasedIntentsQuery(profile, before, batch).execute()

    override fun nextTerminalProfile(after: String?): String? = nextTerminalProfileQuery(after).fetchOne()?.value1()

    override fun nextReleasedIntentProfile(after: String?): String? = nextReleasedIntentProfileQuery(after).fetchOne()?.value1()

    /** The leading column of `ix_job_invocation_retention`, in order, under its predicate, one row. */
    fun nextTerminalProfileQuery(after: String?): Select<Record1<String>> =
        dsl
            .select(j.PROFILE)
            .from(j)
            .where(j.STATE.`in`(TERMINAL))
            .and(if (after == null) DSL.noCondition() else j.PROFILE.gt(after))
            .orderBy(j.PROFILE)
            .limit(1)

    /** The leading column of `ix_job_intent_retention`, in order, under its predicate, one row. */
    fun nextReleasedIntentProfileQuery(after: String?): Select<Record1<String>> =
        dsl
            .select(JOB_INTENT.PROFILE)
            .from(JOB_INTENT)
            .where(JOB_INTENT.RELEASED_AT.isNotNull)
            .and(if (after == null) DSL.noCondition() else JOB_INTENT.PROFILE.gt(after))
            .orderBy(JOB_INTENT.PROFILE)
            .limit(1)

    /** The partial index `ix_job_invocation_retention` serves the inner seek: its predicate is this state list, verbatim. */
    fun deleteTerminalQuery(
        profile: String,
        before: Instant,
        batch: Int,
    ): Delete<*> =
        dsl
            .deleteFrom(j)
            .where(
                j.ID.`in`(
                    DSL
                        .select(j.ID)
                        .from(j)
                        .where(j.PROFILE.eq(profile))
                        .and(j.STATE.`in`(TERMINAL))
                        .and(j.FINISHED_AT.lt(at(before)))
                        .orderBy(j.FINISHED_AT)
                        .limit(batch),
                ),
            )

    fun deleteReleasedIntentsQuery(
        profile: String,
        before: Instant,
        batch: Int,
    ): Delete<*> =
        dsl
            .deleteFrom(JOB_INTENT)
            .where(
                JOB_INTENT.ID.`in`(
                    DSL
                        .select(JOB_INTENT.ID)
                        .from(JOB_INTENT)
                        .where(JOB_INTENT.PROFILE.eq(profile))
                        .and(JOB_INTENT.RELEASED_AT.isNotNull)
                        .and(JOB_INTENT.RELEASED_AT.lt(at(before)))
                        .orderBy(JOB_INTENT.RELEASED_AT)
                        .limit(batch),
                ),
            )

    private companion object {
        /** In the order of the partial index predicates, so PostgreSQL proves the implication. */
        val TERMINAL: List<String> =
            listOf(JobState.SUCCEEDED.wire, JobState.FAILED.wire, JobState.DEAD.wire, JobState.CANCELLED.wire)
    }
}
