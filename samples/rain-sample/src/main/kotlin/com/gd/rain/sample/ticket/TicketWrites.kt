package com.gd.rain.sample.ticket

import com.gd.rain.audit.AuditDetail
import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditOutcome
import com.gd.rain.audit.AuditRecorder
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.path
import com.gd.rain.crud.CrudIds
import com.gd.rain.crud.CrudResource
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.persistence.tx.TransactionRetry
import com.gd.rain.realtime.Channel
import com.gd.rain.realtime.RealtimePublisher
import com.gd.rain.sample.summary.SummaryJobs
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * Announces a ticket's change on its channel `ticket.<id>`, on the writing transaction: a subscriber hears of a change
 * when it commits and never of one that rolled back. An event carries the id and the revision; the ticket itself is
 * read again by whoever needs it.
 */
class TicketEvents(
    private val publisher: RealtimePublisher,
    private val json: JsonMapper,
) {
    fun changed(revision: Revision) {
        publisher.publish(
            channelOf(revision.id),
            json.writeValueAsString(
                linkedMapOf("id" to revision.id.toString(), "version" to revision.version, "status" to revision.status),
            ),
        )
    }

    fun deleted(id: UUID) {
        publisher.publish(channelOf(id), json.writeValueAsString(linkedMapOf("id" to id.toString(), "deleted" to true)))
    }

    companion object {
        fun channelOf(id: UUID): Channel = Channel.of("ticket.$id")
    }
}

/**
 * Every change to a ticket, each in one transaction with its evidence and its announcement: the row, the audit entry
 * and the `NOTIFY` commit together or not at all. A transaction that fails with a SQLSTATE repetition fixes runs again.
 */
class TicketWrites(
    private val tickets: CrudResource<Ticket>,
    private val rows: TicketRows,
    private val audit: AuditRecorder,
    private val events: TicketEvents,
    private val jobs: JobAdministration,
    private val retry: TransactionRetry,
    transactions: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val transaction = TransactionTemplate(transactions)

    /** Opens a ticket from a client's write body, within the caller's scope (`403 outside_scope` otherwise). */
    fun create(body: String?): Ticket =
        inTransaction {
            val created = tickets.create(TicketInputs.Create(body, clock))
            audit.record(
                AuditEvent(
                    TicketAudit.CREATED,
                    AuditOutcome.OK,
                    idOf(created).toString(),
                    AuditDetail.of(
                        "priority" to (created.getValue(TicketFields.PRIORITY.name) as Int),
                        "assigned" to (created[TicketFields.ASSIGNEE.name] != null),
                    ),
                ),
            )
            created
        }

    /**
     * Applies a client's change to the ticket [id] within the caller's scope: `404` outside it, `409 stale_version` when
     * the ticket is no longer at the version the change states.
     */
    fun change(
        id: String,
        body: String?,
    ): Ticket =
        inTransaction {
            val change = TicketInputs.Change(body, clock)
            val written = tickets.update(id, change)
            val revision = revisionOf(written)
            audit.record(
                AuditEvent(
                    TicketAudit.UPDATED,
                    AuditOutcome.OK,
                    revision.id.toString(),
                    AuditDetail.of("fields" to change.fields.joinToString(","), "version" to revision.version),
                ),
            )
            events.changed(revision)
            written
        }

    /** Closes the ticket [id] within the caller's scope; a ticket that is closed already is `409 ticket_closed`. */
    fun close(id: String): Ticket =
        inTransaction {
            val ticketId = idOf(tickets.get(id, NO_PARAMETERS))
            val locked = rows.lock(ticketId) ?: throw Fault.notFound()
            if (locked.status == TicketStatus.CLOSED) throw Fault.conflict(SampleErrorCodes.TICKET_CLOSED)
            val written =
                tickets.update(
                    ticketId,
                    mapOf(
                        TicketFields.STATUS.name to TicketStatus.CLOSED,
                        TicketFields.UPDATED_AT.name to clock.instant(),
                        TicketFields.VERSION.name to locked.version,
                    ),
                )
            val revision = revisionOf(written)
            audit.record(
                AuditEvent(
                    TicketAudit.CLOSED,
                    AuditOutcome.OK,
                    revision.id.toString(),
                    AuditDetail.of("version" to revision.version),
                ),
            )
            events.changed(revision)
            written
        }

    /** Deletes the ticket [id] within the caller's scope and cancels every job about it, in the same transaction. */
    fun delete(id: String) {
        inTransaction {
            tickets.delete(id)
            val ticketId = CrudIds.parse(id, path("id"))
            val cancelled = jobs.cancelBySubject(SummaryJobs.subjectOf(ticketId))
            audit.record(
                AuditEvent(
                    TicketAudit.DELETED,
                    AuditOutcome.OK,
                    ticketId.toString(),
                    AuditDetail.of("cancelled_jobs" to cancelled.cancelled),
                ),
            )
            events.deleted(ticketId)
        }
    }

    private fun <T : Any> inTransaction(work: () -> T): T =
        retry.run { checkNotNull(transaction.execute { work() }) { "a ticket transaction answered nothing" } }

    companion object {
        private val NO_PARAMETERS: Map<String, List<String>> = emptyMap()

        fun idOf(ticket: Ticket): UUID = ticket.getValue(TicketFields.ID.name) as UUID

        fun revisionOf(ticket: Ticket): Revision =
            Revision(
                idOf(ticket),
                ticket.getValue(TicketFields.VERSION.name) as Long,
                ticket.getValue(TicketFields.STATUS.name) as String,
            )
    }
}
