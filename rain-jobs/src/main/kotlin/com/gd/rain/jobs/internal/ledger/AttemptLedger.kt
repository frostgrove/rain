package com.gd.rain.jobs.internal.ledger

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.jooq.Tables.JOB_INTENT
import com.gd.rain.jobs.jooq.Tables.JOB_INVOCATION
import com.gd.rain.jobs.jooq.tables.JobInvocation
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/** One attempt's hold on an invocation. */
internal data class LeaseRef(
    val invocation: UUID,
    val token: UUID,
)

internal data class ClaimRequest(
    val invocation: UUID,
    val generation: Int,
    val token: UUID,
    val pickedBy: String,
    val now: Instant,
    val leaseExpiresAt: Instant,
)

/** What a successful claim hands the attempt. [attempts] already counts this attempt. */
internal data class ClaimedInvocation(
    val id: UUID,
    val definition: String,
    val profile: String,
    val payloadJson: String,
    val attempts: Int,
    val retrySpent: Int,
    val retryLimit: Int,
    val deferrals: Int,
    val subjectKey: String?,
)

/** Where an invocation is, read when a write or a claim was refused. */
internal data class InvocationSnapshot(
    val id: UUID,
    val state: JobState,
    val generation: Int,
    val eligibleAt: Instant,
    val leaseExpiresAt: Instant?,
)

internal sealed interface ClaimResult {
    data class Claimed(
        val invocation: ClaimedInvocation,
    ) : ClaimResult

    /** [current] is null when no invocation has the id. */
    data class NotClaimed(
        val current: InvocationSnapshot?,
    ) : ClaimResult
}

/** What a lease-guarded write answers. */
internal sealed interface WriteResult {
    data object Written : WriteResult

    data class NotOwner(
        val current: InvocationSnapshot?,
    ) : WriteResult
}

/** What one renewal statement answered, per lease asked about. */
internal data class RenewalAnswer(
    /** Leases whose expiry moved. */
    val renewed: Set<UUID>,
    /** Leases not renewed because another transaction holds the row lock, but whose token is still current. */
    val busy: Set<UUID>,
)

/** The attempt side of the ledger. Each method is one statement (or one statement and a snapshot read) and opens no transaction. */
internal interface AttemptLedger {
    /** Claims a queued, eligible invocation of [ClaimRequest.generation] and releases a collapse reservation, in one statement. */
    fun claim(request: ClaimRequest): ClaimResult

    /** A terminal state under the lease, releasing every reservation the invocation holds, in one statement. */
    fun finish(
        lease: LeaseRef,
        state: JobState,
        code: String?,
        message: String?,
        now: Instant,
    ): WriteResult

    /** A charged retry: back to queued, eligible at [eligibleAt]. */
    fun retry(
        lease: LeaseRef,
        code: String,
        message: String,
        eligibleAt: Instant,
    ): WriteResult

    /** "Not now": back to queued with one more deferral and no retry charged. */
    fun defer(
        lease: LeaseRef,
        eligibleAt: Instant,
    ): WriteResult

    /** An uncharged release: back to queued, due at [eligibleAt]. */
    fun release(
        lease: LeaseRef,
        eligibleAt: Instant,
    ): WriteResult

    /** Inside a transaction: row-locks the invocation and answers whether [lease] still owns it with an unexpired lease. */
    fun fence(
        lease: LeaseRef,
        now: Instant,
    ): Boolean

    fun snapshot(invocation: UUID): InvocationSnapshot?

    /** Extends every lease in [leases] that is still current and not row-locked by another transaction. */
    fun renew(
        leases: Collection<LeaseRef>,
        expiresAt: Instant,
    ): RenewalAnswer
}

internal class JooqAttemptLedger(
    private val dsl: DSLContext,
) : AttemptLedger {
    override fun claim(request: ClaimRequest): ClaimResult {
        val claimed =
            DSL.name("claimed").`as`(
                DSL
                    .update(J)
                    .set(J.STATE, JobState.RUNNING.wire)
                    .set(J.ATTEMPTS, J.ATTEMPTS.plus(1))
                    .set(J.STARTED_AT, DSL.coalesce(J.STARTED_AT, DSL.value(at(request.now))))
                    .set(J.LEASE_TOKEN, request.token)
                    .set(J.LEASE_EXPIRES_AT, at(request.leaseExpiresAt))
                    .set(J.PICKED_BY, request.pickedBy)
                    .set(J.VERSION, J.VERSION.plus(1L))
                    .where(J.ID.eq(request.invocation))
                    .and(J.GENERATION.eq(request.generation))
                    .and(J.STATE.eq(JobState.QUEUED.wire))
                    .and(J.ELIGIBLE_AT.le(at(request.now)))
                    .returningResult(
                        J.ID,
                        J.DEFINITION,
                        J.PROFILE,
                        J.PAYLOAD,
                        J.ATTEMPTS,
                        J.RETRY_SPENT,
                        J.RETRY_LIMIT,
                        J.DEFERRALS,
                        J.SUBJECT_KEY,
                    ),
            )
        val claimedId = cteField(claimed, J.ID)
        val released =
            DSL.name("released_collapse").`as`(
                DSL
                    .update(JOB_INTENT)
                    .set(JOB_INTENT.RELEASED_AT, at(request.now))
                    .where(JOB_INTENT.INVOCATION_ID.`in`(DSL.select(claimedId).from(claimed)))
                    .and(JOB_INTENT.RELEASED_AT.isNull)
                    .and(JOB_INTENT.MODE.eq(DedupeMode.COLLAPSE.wire))
                    .returningResult(JOB_INTENT.ID),
            )
        val row =
            dsl
                .with(claimed)
                .with(released)
                .select(claimed.fields().toList())
                .from(claimed)
                .fetchOne()
                ?: return ClaimResult.NotClaimed(snapshot(request.invocation))
        return ClaimResult.Claimed(
            ClaimedInvocation(
                id = row.get(J.ID.name, UUID::class.java),
                definition = row.get(J.DEFINITION.name, String::class.java),
                profile = row.get(J.PROFILE.name, String::class.java),
                payloadJson = row.get(J.PAYLOAD.name, org.jooq.JSONB::class.java).data(),
                attempts = row.get(J.ATTEMPTS.name, Int::class.java),
                retrySpent = row.get(J.RETRY_SPENT.name, Int::class.java),
                retryLimit = row.get(J.RETRY_LIMIT.name, Int::class.java),
                deferrals = row.get(J.DEFERRALS.name, Int::class.java),
                subjectKey = row.get(J.SUBJECT_KEY.name, String::class.java),
            ),
        )
    }

    override fun finish(
        lease: LeaseRef,
        state: JobState,
        code: String?,
        message: String?,
        now: Instant,
    ): WriteResult {
        check(state.terminal) { "finish writes a terminal state, not ${state.wire}" }
        val written = terminal(lease.invocation, owns(lease), state, code, message, now)
        return if (written == 1) WriteResult.Written else WriteResult.NotOwner(snapshot(lease.invocation))
    }

    override fun retry(
        lease: LeaseRef,
        code: String,
        message: String,
        eligibleAt: Instant,
    ): WriteResult =
        requeued(
            lease,
            dsl
                .update(J)
                .set(J.STATE, JobState.QUEUED.wire)
                .set(J.RETRY_SPENT, J.RETRY_SPENT.plus(1))
                .set(J.ELIGIBLE_AT, at(eligibleAt))
                .set(J.FAILURE_CODE, code)
                .set(J.FAILURE_MESSAGE, bounded(message))
                .setNull(J.LEASE_TOKEN)
                .setNull(J.LEASE_EXPIRES_AT)
                .set(J.VERSION, J.VERSION.plus(1L))
                .where(owns(lease))
                .execute(),
        )

    override fun defer(
        lease: LeaseRef,
        eligibleAt: Instant,
    ): WriteResult =
        requeued(
            lease,
            dsl
                .update(J)
                .set(J.STATE, JobState.QUEUED.wire)
                .set(J.DEFERRALS, J.DEFERRALS.plus(1))
                .set(J.ELIGIBLE_AT, at(eligibleAt))
                .setNull(J.LEASE_TOKEN)
                .setNull(J.LEASE_EXPIRES_AT)
                .set(J.VERSION, J.VERSION.plus(1L))
                .where(owns(lease))
                .execute(),
        )

    override fun release(
        lease: LeaseRef,
        eligibleAt: Instant,
    ): WriteResult =
        requeued(
            lease,
            dsl
                .update(J)
                .set(J.STATE, JobState.QUEUED.wire)
                .set(J.ELIGIBLE_AT, at(eligibleAt))
                .setNull(J.LEASE_TOKEN)
                .setNull(J.LEASE_EXPIRES_AT)
                .set(J.VERSION, J.VERSION.plus(1L))
                .where(owns(lease))
                .execute(),
        )

    override fun fence(
        lease: LeaseRef,
        now: Instant,
    ): Boolean =
        dsl
            .select(DSL.inline(1))
            .from(J)
            .where(owns(lease))
            .and(J.LEASE_EXPIRES_AT.gt(at(now)))
            .forUpdate()
            .fetchOne() != null

    override fun snapshot(invocation: UUID): InvocationSnapshot? =
        dsl
            .select(J.ID, J.STATE, J.GENERATION, J.ELIGIBLE_AT, J.LEASE_EXPIRES_AT)
            .from(J)
            .where(J.ID.eq(invocation))
            .fetchOne { row ->
                InvocationSnapshot(
                    id = row.value1(),
                    state = JobState.fromWire(row.value2()),
                    generation = row.value3(),
                    eligibleAt = row.value4().toInstant(),
                    leaseExpiresAt = row.value5()?.toInstant(),
                )
            }

    override fun renew(
        leases: Collection<LeaseRef>,
        expiresAt: Instant,
    ): RenewalAnswer {
        if (leases.isEmpty()) return RenewalAnswer(emptySet(), emptySet())
        val current = DSL.row(J.ID, J.LEASE_TOKEN).`in`(leases.map { DSL.row(it.invocation, it.token) })
        val locked =
            DSL.name("renewable").`as`(
                DSL
                    .select(J.ID)
                    .from(J)
                    .where(current)
                    .and(J.STATE.eq(JobState.RUNNING.wire))
                    .forUpdate()
                    .skipLocked(),
            )
        val renewed =
            dsl
                .with(locked)
                .update(J)
                .set(J.LEASE_EXPIRES_AT, at(expiresAt))
                .where(J.ID.`in`(DSL.select(cteField(locked, J.ID)).from(locked)))
                .returningResult(J.ID)
                .fetch()
                .map { it.value1() }
                .toSet()
        val unanswered = leases.filterNot { it.invocation in renewed }
        val busy =
            if (unanswered.isEmpty()) {
                emptySet()
            } else {
                dsl
                    .select(J.ID)
                    .from(J)
                    .where(DSL.row(J.ID, J.LEASE_TOKEN).`in`(unanswered.map { DSL.row(it.invocation, it.token) }))
                    .and(J.STATE.eq(JobState.RUNNING.wire))
                    .fetch()
                    .map { it.value1() }
                    .toSet()
            }
        return RenewalAnswer(renewed, busy)
    }

    /**
     * A terminal write guarded by [guard], releasing the invocation's held reservations in the same statement: the
     * state and the reservation can never disagree, and a refused write releases nothing.
     */
    internal fun terminal(
        invocation: UUID,
        guard: Condition,
        state: JobState,
        code: String?,
        message: String?,
        now: Instant,
    ): Int = terminalQuery(invocation, guard, state, code, message, now).fetchSingle().value1()

    /** The statement [terminal] runs: the invocation by primary key, its held reservation through `uq_job_intent_held_invocation`. */
    internal fun terminalQuery(
        invocation: UUID,
        guard: Condition,
        state: JobState,
        code: String?,
        message: String?,
        now: Instant,
    ): org.jooq.SelectJoinStep<org.jooq.Record1<Int>> {
        val owned =
            DSL.name("owned").`as`(
                DSL
                    .update(J)
                    .set(J.STATE, state.wire)
                    .set(J.FINISHED_AT, at(now))
                    .set(J.FAILURE_CODE, code)
                    .set(J.FAILURE_MESSAGE, message?.let(::bounded))
                    .setNull(J.LEASE_TOKEN)
                    .setNull(J.LEASE_EXPIRES_AT)
                    .set(J.VERSION, J.VERSION.plus(1L))
                    .where(J.ID.eq(invocation))
                    .and(guard)
                    .returningResult(J.ID),
            )
        val released =
            DSL.name("released_terminal").`as`(
                DSL
                    .update(JOB_INTENT)
                    .set(JOB_INTENT.RELEASED_AT, at(now))
                    .where(JOB_INTENT.INVOCATION_ID.`in`(DSL.select(cteField(owned, J.ID)).from(owned)))
                    .and(JOB_INTENT.RELEASED_AT.isNull)
                    .returningResult(JOB_INTENT.ID),
            )
        return dsl
            .with(owned)
            .with(released)
            .selectCount()
            .from(owned)
    }

    private fun requeued(
        lease: LeaseRef,
        written: Int,
    ): WriteResult = if (written == 1) WriteResult.Written else WriteResult.NotOwner(snapshot(lease.invocation))

    internal companion object {
        val J: JobInvocation = JOB_INVOCATION

        /** `failure_message` is read by a person; the full failure is in the log. */
        const val MAX_MESSAGE: Int = 2_000

        fun owns(lease: LeaseRef): Condition =
            J.ID
                .eq(lease.invocation)
                .and(J.LEASE_TOKEN.eq(lease.token))
                .and(J.STATE.eq(JobState.RUNNING.wire))

        fun bounded(message: String): String = message.take(MAX_MESSAGE)

        fun <T> cteField(
            cte: org.jooq.Table<*>,
            field: Field<T>,
        ): Field<T> = checkNotNull(cte.field(field)) { "common table expression ${cte.name} has no column ${field.name}" }
    }
}
