package com.gd.rain.sample.ticket

import com.gd.rain.crud.query.ResourceSchema
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Record4
import org.jooq.Record5
import org.jooq.ResultQuery
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.util.UUID

internal object TicketsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("public", "tickets"))
    val ID: Field<UUID> = DSL.field(DSL.name("id"), SQLDataType.UUID)
    val TITLE: Field<String> = DSL.field(DSL.name("title"), SQLDataType.CLOB)
    val BODY: Field<String> = DSL.field(DSL.name("body"), SQLDataType.CLOB)
    val STATUS: Field<String> = DSL.field(DSL.name("status"), SQLDataType.CLOB)
    val PRIORITY: Field<Int> = DSL.field(DSL.name("priority"), SQLDataType.INTEGER)
    val ASSIGNEE: Field<UUID> = DSL.field(DSL.name("assignee"), SQLDataType.UUID)
    val SUMMARY: Field<String> = DSL.field(DSL.name("summary"), SQLDataType.CLOB)
    val ESCALATED_AT: Field<Instant> = DSL.field(DSL.name("escalated_at"), SQLDataType.INSTANT)
    val CREATED_AT: Field<Instant> = DSL.field(DSL.name("created_at"), SQLDataType.INSTANT)
    val UPDATED_AT: Field<Instant> = DSL.field(DSL.name("updated_at"), SQLDataType.INSTANT)
    val VERSION: Field<Long> = DSL.field(DSL.name("version"), SQLDataType.BIGINT)
}

/** Where a ticket is: its id, its version and its status. What a change event carries. */
data class Revision(
    val id: UUID,
    val version: Long,
    val status: String,
)

/** A ticket's place in `created_at, id` order: the key of a report page and of an escalation batch. */
data class TicketKey(
    val createdAt: Instant,
    val id: UUID,
) {
    /** `<created-at>_<id>`, the form `ticket-report --after` reads. */
    fun written(): String = "${createdAt}_$id"

    companion object {
        val ORDER: Comparator<TicketKey> = compareBy<TicketKey> { it.createdAt }.thenBy { it.id }

        /** The key [written] wrote, or `null` for any other text: an ISO-8601 instant, `_`, a canonical id. */
        fun parse(text: String): TicketKey? {
            val separator = text.lastIndexOf('_')
            if (separator <= 0) return null
            val createdAt =
                try {
                    Instant.parse(text.substring(0, separator))
                } catch (_: java.time.format.DateTimeParseException) {
                    return null
                }
            val id = ResourceSchema.canonicalUuid(text.substring(separator + 1)) ?: return null
            return TicketKey(createdAt, id)
        }
    }
}

data class OpenTicket(
    val id: UUID,
    val title: String,
    val priority: Int,
    val assignee: UUID?,
    val createdAt: Instant,
) {
    val key: TicketKey get() = TicketKey(createdAt, id)
}

data class OpenPage(
    val items: List<OpenTicket>,
    /** Where the next page starts; `null` on the last page. */
    val next: TicketKey?,
)

data class TicketText(
    val title: String,
    val body: String,
)

data class Escalated(
    val key: TicketKey,
    val revision: Revision,
)

/**
 * The statements the helpdesk writes itself, in jOOQ, beside what rain-crud runs for the resource: each is addressed by
 * primary key, or is a keyset page under a `LIMIT` over one of the ticket indexes (`TicketStatementPlansIT`).
 */
class TicketRows(
    private val dsl: DSLContext,
) {
    /** The ticket's revision, its row locked for the rest of the transaction; `null` when there is no such ticket. */
    fun lock(id: UUID): Revision? =
        dsl
            .select(TicketsTable.ID, TicketsTable.VERSION, TicketsTable.STATUS)
            .from(TicketsTable.TABLE)
            .where(TicketsTable.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.let { Revision(it.value1(), it.value2(), it.value3()) }

    fun text(id: UUID): TicketText? =
        dsl
            .select(TicketsTable.TITLE, TicketsTable.BODY)
            .from(TicketsTable.TABLE)
            .where(TicketsTable.ID.eq(id))
            .fetchOne()
            ?.let { TicketText(it.value1(), it.value2()) }

    /** Writes the summary as a new version of the ticket; `null` when the ticket was deleted meanwhile. */
    fun storeSummary(
        id: UUID,
        summary: String,
        now: Instant,
    ): Revision? =
        dsl
            .update(TicketsTable.TABLE)
            .set(TicketsTable.SUMMARY, summary)
            .set(TicketsTable.UPDATED_AT, now)
            .set(TicketsTable.VERSION, TicketsTable.VERSION.plus(1L))
            .where(TicketsTable.ID.eq(id))
            .returningResult(TicketsTable.ID, TicketsTable.VERSION, TicketsTable.STATUS)
            .fetchOne()
            ?.let { Revision(it.value1(), it.value2(), it.value3()) }

    /** One keyset page of open tickets, oldest first, over `ix_tickets_status_created_at`; reads [limit] + 1 rows. */
    fun openPageQuery(
        after: TicketKey?,
        limit: Int,
    ): ResultQuery<Record5<UUID, String, Int, UUID, Instant>> =
        dsl
            .select(TicketsTable.ID, TicketsTable.TITLE, TicketsTable.PRIORITY, TicketsTable.ASSIGNEE, TicketsTable.CREATED_AT)
            .from(TicketsTable.TABLE)
            .where(TicketsTable.STATUS.eq(TicketStatus.OPEN))
            .and(after?.let { DSL.row(TicketsTable.CREATED_AT, TicketsTable.ID).gt(it.createdAt, it.id) } ?: DSL.noCondition())
            .orderBy(TicketsTable.CREATED_AT.asc(), TicketsTable.ID.asc())
            .limit(limit + 1)

    fun openPage(
        after: TicketKey?,
        limit: Int,
    ): OpenPage {
        require(limit >= 1) { "a page holds at least one ticket, got $limit" }
        val rows =
            openPageQuery(after, limit).fetch().map {
                OpenTicket(it.value1(), it.value2(), it.value3(), it.value4(), it.value5())
            }
        val kept = rows.take(limit)
        return OpenPage(kept, if (rows.size > limit) kept.last().key else null)
    }

    /**
     * One batch of the escalation sweep, as one statement: the oldest open tickets created before [cutoff] and not
     * escalated yet, after [after] in `created_at, id` order, at most [batch] of them (read over `ix_tickets_escalation`);
     * each still at the version the batch read is marked escalated, raised to at least [priority], and becomes a new
     * version. A ticket another transaction changed in between keeps that change; the next pass reads it again.
     */
    fun escalationQuery(
        cutoff: Instant,
        after: TicketKey?,
        batch: Int,
        priority: Int,
        now: Instant,
    ): ResultQuery<Record4<UUID, Instant, Long, String>> {
        val picked = DSL.name("picked")
        val pickedId = DSL.field(DSL.name("picked", "ticket_id"), SQLDataType.UUID)
        val pickedVersion = DSL.field(DSL.name("picked", "ticket_version"), SQLDataType.BIGINT)
        val candidates =
            dsl
                .select(TicketsTable.ID.`as`("ticket_id"), TicketsTable.VERSION.`as`("ticket_version"))
                .from(TicketsTable.TABLE)
                .where(TicketsTable.STATUS.eq(TicketStatus.OPEN))
                .and(TicketsTable.ESCALATED_AT.isNull)
                .and(TicketsTable.CREATED_AT.lt(cutoff))
                .and(after?.let { DSL.row(TicketsTable.CREATED_AT, TicketsTable.ID).gt(it.createdAt, it.id) } ?: DSL.noCondition())
                .orderBy(TicketsTable.CREATED_AT.asc(), TicketsTable.ID.asc())
                .limit(batch)
        return dsl
            .with(picked.asMaterialized(candidates))
            .update(TicketsTable.TABLE)
            .set(TicketsTable.ESCALATED_AT, now)
            .set(TicketsTable.PRIORITY, DSL.greatest(TicketsTable.PRIORITY, DSL.`val`(priority)))
            .set(TicketsTable.UPDATED_AT, now)
            .set(TicketsTable.VERSION, TicketsTable.VERSION.plus(1L))
            .from(DSL.table(picked))
            .where(TicketsTable.ID.eq(pickedId))
            .and(TicketsTable.VERSION.eq(pickedVersion))
            .returningResult(TicketsTable.ID, TicketsTable.CREATED_AT, TicketsTable.VERSION, TicketsTable.STATUS)
    }

    fun escalate(
        cutoff: Instant,
        after: TicketKey?,
        batch: Int,
        priority: Int,
        now: Instant,
    ): List<Escalated> =
        escalationQuery(cutoff, after, batch, priority, now).fetch().map {
            Escalated(TicketKey(it.value2(), it.value1()), Revision(it.value1(), it.value3(), it.value4()))
        }
}
