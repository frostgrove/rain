package com.gd.rain.jobs.internal.ledger

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.admin.DeadLetter
import com.gd.rain.jobs.admin.DeadLetterCursor
import com.gd.rain.jobs.jooq.Tables.JOB_INTENT
import org.jooq.DSLContext
import org.jooq.ResultQuery
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/** What a redrive reads, under a row lock, before deciding. */
internal data class RedriveCandidate(
    val id: UUID,
    val definition: String,
    val state: JobState,
    val dedupeMode: DedupeMode,
    val dedupeKey: String?,
    val priority: Int,
)

internal interface AdministrationLedger {
    /** At most `limit + 1` rows, so the caller knows whether another page exists. */
    fun deadLetters(
        definition: String?,
        after: DeadLetterCursor?,
        limit: Int,
    ): List<DeadLetter>

    /** Inside a transaction: the invocation, row-locked. */
    fun lockForRedrive(invocation: UUID): RedriveCandidate?

    /** Requeues a failed or dead invocation under the next generation; answers that generation, or null when it is not failed or dead. */
    fun requeueForRedrive(
        invocation: UUID,
        profile: String,
        retryLimit: Int,
        now: Instant,
    ): Int?

    /** Cancels at most [batch] live invocations about [subject] and releases their reservations; one statement. */
    fun cancelLive(
        subject: SubjectKey,
        batch: Int,
        now: Instant,
    ): Int
}

internal class JooqAdministrationLedger(
    private val dsl: DSLContext,
) : AdministrationLedger {
    private val j = JooqAttemptLedger.J

    override fun deadLetters(
        definition: String?,
        after: DeadLetterCursor?,
        limit: Int,
    ): List<DeadLetter> =
        deadLettersQuery(definition, after, limit).fetch { row ->
            DeadLetter(
                id = row[j.ID],
                definition = row[j.DEFINITION],
                profile = row[j.PROFILE],
                state = JobState.fromWire(row[j.STATE]),
                attempts = row[j.ATTEMPTS],
                retrySpent = row[j.RETRY_SPENT],
                retryLimit = row[j.RETRY_LIMIT],
                failureCode = row[j.FAILURE_CODE],
                failureMessage = row[j.FAILURE_MESSAGE],
                finishedAt = row[j.FINISHED_AT].toInstant(),
                subjectKey = row[j.SUBJECT_KEY]?.let(::SubjectKey),
                absorbedCount = row[j.ABSORBED_COUNT],
            )
        }

    /** A seek below the cursor on `(finished_at DESC, id DESC)`, served by the dead-letter indexes, one row beyond the page. */
    fun deadLettersQuery(
        definition: String?,
        after: DeadLetterCursor?,
        limit: Int,
    ): ResultQuery<*> =
        dsl
            .select(
                j.ID,
                j.DEFINITION,
                j.PROFILE,
                j.STATE,
                j.ATTEMPTS,
                j.RETRY_SPENT,
                j.RETRY_LIMIT,
                j.FAILURE_CODE,
                j.FAILURE_MESSAGE,
                j.FINISHED_AT,
                j.SUBJECT_KEY,
                j.ABSORBED_COUNT,
            ).from(j)
            .where(j.STATE.`in`(JobState.FAILED.wire, JobState.DEAD.wire))
            .and(definition?.let(j.DEFINITION::eq) ?: DSL.noCondition())
            .and(after?.let { DSL.row(j.FINISHED_AT, j.ID).lessThan(at(it.finishedAt), it.id) } ?: DSL.noCondition())
            .orderBy(j.FINISHED_AT.desc(), j.ID.desc())
            .limit(limit + 1)

    override fun lockForRedrive(invocation: UUID): RedriveCandidate? =
        dsl
            .select(j.ID, j.DEFINITION, j.STATE, j.DEDUPE_MODE, j.DEDUPE_KEY, j.PRIORITY)
            .from(j)
            .where(j.ID.eq(invocation))
            .forUpdate()
            .fetchOne { row ->
                RedriveCandidate(
                    id = row.value1(),
                    definition = row.value2(),
                    state = JobState.fromWire(row.value3()),
                    dedupeMode = DedupeMode.fromWire(row.value4()),
                    dedupeKey = row.value5(),
                    priority = row.value6().toInt(),
                )
            }

    override fun requeueForRedrive(
        invocation: UUID,
        profile: String,
        retryLimit: Int,
        now: Instant,
    ): Int? =
        dsl
            .update(j)
            .set(j.STATE, JobState.QUEUED.wire)
            .set(j.PROFILE, profile)
            .set(j.RETRY_LIMIT, retryLimit)
            .set(j.RETRY_SPENT, 0)
            .set(j.DEFERRALS, 0)
            .set(j.ELIGIBLE_AT, at(now))
            .setNull(j.FINISHED_AT)
            .setNull(j.FAILURE_CODE)
            .setNull(j.FAILURE_MESSAGE)
            .set(j.GENERATION, j.GENERATION.plus(1))
            .set(j.VERSION, j.VERSION.plus(1L))
            .where(j.ID.eq(invocation))
            .and(j.STATE.`in`(JobState.FAILED.wire, JobState.DEAD.wire))
            .returningResult(j.GENERATION)
            .fetchOne()
            ?.value1()

    override fun cancelLive(
        subject: SubjectKey,
        batch: Int,
        now: Instant,
    ): Int = cancelQuery(subject, batch, now).fetchSingle().value1()

    /** The subject index serves the pick; the partial predicate is this state list, verbatim. */
    fun cancelQuery(
        subject: SubjectKey,
        batch: Int,
        now: Instant,
    ) = run {
        val picked =
            DSL.name("picked").`as`(
                DSL
                    .select(j.ID)
                    .from(j)
                    .where(j.SUBJECT_KEY.eq(subject.value))
                    .and(j.STATE.`in`(JobState.QUEUED.wire, JobState.RUNNING.wire))
                    .limit(batch)
                    .forUpdate(),
            )
        val cancelled =
            DSL.name("cancelled").`as`(
                DSL
                    .update(j)
                    .set(j.STATE, JobState.CANCELLED.wire)
                    .set(j.FINISHED_AT, at(now))
                    .setNull(j.LEASE_TOKEN)
                    .setNull(j.LEASE_EXPIRES_AT)
                    .set(j.VERSION, j.VERSION.plus(1L))
                    .where(j.ID.`in`(DSL.select(JooqAttemptLedger.cteField(picked, j.ID)).from(picked)))
                    .returningResult(j.ID),
            )
        val released =
            DSL.name("released_cancelled").`as`(
                DSL
                    .update(JOB_INTENT)
                    .set(JOB_INTENT.RELEASED_AT, at(now))
                    .where(JOB_INTENT.INVOCATION_ID.`in`(DSL.select(JooqAttemptLedger.cteField(cancelled, j.ID)).from(cancelled)))
                    .and(JOB_INTENT.RELEASED_AT.isNull)
                    .returningResult(JOB_INTENT.ID),
            )
        dsl
            .with(picked)
            .with(cancelled)
            .with(released)
            .selectCount()
            .from(cancelled)
    }
}
