package com.gd.rain.jobs.internal.ledger

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.jooq.Tables.JOB_INTENT
import com.gd.rain.jobs.jooq.Tables.JOB_INVOCATION
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** How an intent is released, as `job_intent.mode` / `job_invocation.dedupe_mode` store it. */
internal enum class DedupeMode(
    val wire: String,
) {
    NONE("none"),
    UNIQUE("unique"),
    COLLAPSE("collapse"),
    ;

    companion object {
        fun of(dedupe: Dedupe): DedupeMode =
            when (dedupe) {
                Dedupe.None -> NONE
                is Dedupe.Unique -> UNIQUE
                is Dedupe.Collapse -> COLLAPSE
            }

        fun keyOf(dedupe: Dedupe): String? =
            when (dedupe) {
                Dedupe.None -> null
                is Dedupe.Unique -> dedupe.key
                is Dedupe.Collapse -> dedupe.key
            }

        fun fromWire(wire: String): DedupeMode =
            entries.firstOrNull { it.wire == wire } ?: throw IllegalStateException("\"$wire\" is not a dedupe mode")
    }
}

/** What a reservation attempt answers, in one statement. */
internal sealed interface Reservation {
    data object Reserved : Reservation

    /** The key is held; [holder] is the invocation holding it, as a fresh read saw it (null when released meanwhile). */
    data class Held(
        val holder: UUID?,
    ) : Reservation
}

/** An order as the enqueue writes it. */
internal data class NewInvocation(
    val id: UUID,
    val definition: String,
    val profile: String,
    val priority: JobPriority,
    val payloadJson: String,
    val dedupeMode: DedupeMode,
    val dedupeKey: String?,
    val subjectKey: SubjectKey?,
    val retryLimit: Int,
    val createdAt: Instant,
    val eligibleAt: Instant,
)

/** The enqueue side of the ledger. Every method joins the caller's transaction and opens none. */
internal interface IntentLedger {
    fun reserve(
        definition: String,
        profile: String,
        key: String,
        mode: DedupeMode,
        invocation: UUID,
        now: Instant,
    ): Reservation

    /** Counts one absorbed order on the current holder of (definition, key); null when nothing holds it any more. */
    fun absorb(
        definition: String,
        key: String,
        now: Instant,
    ): UUID?

    fun insert(order: NewInvocation)
}

internal class JooqIntentLedger(
    private val dsl: DSLContext,
    private val ids: IdGenerator,
) : IntentLedger {
    /**
     * `INSERT … ON CONFLICT (definition, dedupe_key) WHERE released_at IS NULL DO NOTHING`: PostgreSQL infers the
     * partial unique index, so a concurrent duplicate is decided inside the insert and never raises 23505 into the
     * caller's transaction.
     */
    override fun reserve(
        definition: String,
        profile: String,
        key: String,
        mode: DedupeMode,
        invocation: UUID,
        now: Instant,
    ): Reservation {
        check(mode != DedupeMode.NONE) { "an order without dedupe reserves nothing" }
        val written =
            dsl
                .insertInto(JOB_INTENT)
                .set(JOB_INTENT.ID, ids.next())
                .set(JOB_INTENT.DEFINITION, definition)
                .set(JOB_INTENT.PROFILE, profile)
                .set(JOB_INTENT.DEDUPE_KEY, key)
                .set(JOB_INTENT.MODE, mode.wire)
                .set(JOB_INTENT.INVOCATION_ID, invocation)
                .set(JOB_INTENT.RESERVED_AT, at(now))
                .onConflict(JOB_INTENT.DEFINITION, JOB_INTENT.DEDUPE_KEY)
                .where(JOB_INTENT.RELEASED_AT.isNull)
                .doNothing()
                .execute()
        return if (written == 1) Reservation.Reserved else Reservation.Held(holderOf(definition, key))
    }

    /** One statement: the holder is read and its absorption counted on the same snapshot. */
    override fun absorb(
        definition: String,
        key: String,
        now: Instant,
    ): UUID? =
        dsl
            .update(JOB_INVOCATION)
            .set(JOB_INVOCATION.ABSORBED_COUNT, JOB_INVOCATION.ABSORBED_COUNT.plus(1))
            .set(JOB_INVOCATION.LAST_ABSORBED_AT, at(now))
            .set(JOB_INVOCATION.VERSION, JOB_INVOCATION.VERSION.plus(1L))
            .where(JOB_INVOCATION.ID.eq(DSL.field(heldBy(definition, key))))
            .returningResult(JOB_INVOCATION.ID)
            .fetchOne()
            ?.value1()

    override fun insert(order: NewInvocation) {
        dsl
            .insertInto(JOB_INVOCATION)
            .set(JOB_INVOCATION.ID, order.id)
            .set(JOB_INVOCATION.DEFINITION, order.definition)
            .set(JOB_INVOCATION.PROFILE, order.profile)
            .set(JOB_INVOCATION.STATE, JobState.QUEUED.wire)
            .set(JOB_INVOCATION.PRIORITY, order.priority.value.toShort())
            .set(JOB_INVOCATION.PAYLOAD, JSONB.valueOf(order.payloadJson))
            .set(JOB_INVOCATION.DEDUPE_MODE, order.dedupeMode.wire)
            .set(JOB_INVOCATION.DEDUPE_KEY, order.dedupeKey)
            .set(JOB_INVOCATION.SUBJECT_KEY, order.subjectKey?.value)
            .set(JOB_INVOCATION.GENERATION, 0)
            .set(JOB_INVOCATION.ATTEMPTS, 0)
            .set(JOB_INVOCATION.RETRY_SPENT, 0)
            .set(JOB_INVOCATION.RETRY_LIMIT, order.retryLimit)
            .set(JOB_INVOCATION.DEFERRALS, 0)
            .set(JOB_INVOCATION.CREATED_AT, at(order.createdAt))
            .set(JOB_INVOCATION.ELIGIBLE_AT, at(order.eligibleAt))
            .set(JOB_INVOCATION.ABSORBED_COUNT, 0)
            .set(JOB_INVOCATION.VERSION, 0L)
            .execute()
    }

    private fun holderOf(
        definition: String,
        key: String,
    ): UUID? = dsl.fetchOne(heldBy(definition, key))?.value1()

    private fun heldBy(
        definition: String,
        key: String,
    ) = DSL
        .select(JOB_INTENT.INVOCATION_ID)
        .from(JOB_INTENT)
        .where(JOB_INTENT.DEFINITION.eq(definition))
        .and(JOB_INTENT.DEDUPE_KEY.eq(key))
        .and(JOB_INTENT.RELEASED_AT.isNull)
}

internal fun at(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
