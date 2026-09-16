package com.gd.rain.sample.ticket

import com.gd.rain.audit.AuditEventType
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.Fault
import com.gd.rain.crud.Action
import com.gd.rain.crud.ActionAccess
import com.gd.rain.crud.Caller
import com.gd.rain.crud.CallerLookup
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.CrudStore
import com.gd.rain.crud.ResourcePolicy
import com.gd.rain.crud.ScopeRule
import com.gd.rain.crud.query.FieldGrant
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.Pagination
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.QueryRules
import com.gd.rain.crud.query.QueryShape
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
import com.gd.rain.crud.query.SortKey
import com.gd.rain.crud.query.TableName
import com.gd.rain.crud.web.CrudOperation
import com.gd.rain.crud.web.MountedResource
import com.gd.rain.sample.access.TicketPermissions
import com.gd.rain.sample.agent.Agents
import com.gd.rain.sample.config.TicketProperties
import java.util.UUID

/** A ticket as the resource answers it: field name to value, each value of its field's kind. */
typealias Ticket = Map<String, Any?>

/** The ticket resource's fields: wire name, column, kind and nullability, stated rather than read from the database. */
object TicketFields {
    val ID = SchemaField("id", "id", FieldKind.UUID, nullable = false)
    val TITLE = SchemaField("title", "title", FieldKind.TEXT, nullable = false)
    val BODY = SchemaField("body", "body", FieldKind.TEXT, nullable = false)
    val STATUS = SchemaField("status", "status", FieldKind.TEXT, nullable = false)
    val PRIORITY = SchemaField("priority", "priority", FieldKind.INT, nullable = false)
    val ASSIGNEE = SchemaField("assignee", "assignee", FieldKind.UUID, nullable = true)
    val SUMMARY = SchemaField("summary", "summary", FieldKind.TEXT, nullable = true)
    val ESCALATED_AT = SchemaField("escalatedAt", "escalated_at", FieldKind.TIMESTAMP, nullable = true)
    val CREATED_AT = SchemaField("createdAt", "created_at", FieldKind.TIMESTAMP, nullable = false)
    val UPDATED_AT = SchemaField("updatedAt", "updated_at", FieldKind.TIMESTAMP, nullable = false)
    val VERSION = SchemaField("version", "version", FieldKind.LONG, nullable = false)

    val SCHEMA =
        ResourceSchema(
            name = "tickets",
            table = TableName("public", "tickets"),
            id = ID,
            fields = listOf(TITLE, BODY, STATUS, PRIORITY, ASSIGNEE, SUMMARY, ESCALATED_AT, CREATED_AT, UPDATED_AT, VERSION),
            // The store writes it: 1 on insert, one more on every update; a change states the version it read.
            version = VERSION,
        )
}

object TicketStatus {
    const val OPEN = "open"
    const val CLOSED = "closed"
}

/** The ticket resource as rain-crud serves it: shapes, policy, its scope and the mounted operations. */
object TicketDeclarations {
    const val PREFIX = "/v1/tickets"

    const val READ_WHY = "every agent reads tickets; ticket.read widens what it reads from the tickets assigned to it to every ticket"

    /**
     * The list queries every caller is answered, over the rows its scope gives it: the tickets changed most recently, the
     * tickets of a status newest first, and one agent's tickets of a status highest priority first — for a responder, its
     * own. Any other query is `400 not_offered`.
     */
    val SHAPES: List<QueryShape> =
        listOf(
            QueryShape.of(SortKey.parse("-updatedAt")),
            QueryShape.of(SortKey.parse("-createdAt"), "status" to Operator.EQ),
            QueryShape.of(SortKey.parse("-priority"), "assignee" to Operator.EQ, "status" to Operator.EQ),
        )

    val OPERATIONS: Set<CrudOperation> =
        setOf(CrudOperation.CREATE, CrudOperation.COUNT, CrudOperation.LIST, CrudOperation.GET, CrudOperation.UPDATE, CrudOperation.DELETE)

    val ACCESS: Map<Action, ActionAccess> =
        mapOf(
            Action.READ to ActionAccess.Authenticated(READ_WHY),
            Action.CREATE to ActionAccess.permissions(TicketPermissions.WRITE),
            Action.UPDATE to ActionAccess.permissions(TicketPermissions.WRITE),
            Action.DELETE to ActionAccess.permissions(TicketPermissions.DELETE),
        )

    /**
     * The fields a write may name, the version aside, which the store writes. A client states only title, body, priority
     * and assignee (`TicketInputs`); the helpdesk states the rest — status, timestamps — on the same write.
     */
    val WRITABLE: FieldGrant =
        FieldGrant.only("title", "body", "status", "priority", "assignee", "summary", "escalatedAt", "createdAt", "updatedAt")

    /**
     * Each shape has an index under each scope (`V1__helpdesk.sql`): every ticket, and the tickets of one assignee. No two
     * shapes sort by the same field while one's equality filters are part of the other's: an index serving the smaller set
     * would then serve the larger with a `Filter`, and on the empty table the plan proof explains against PostgreSQL picks
     * between the two by a cost tie. `TicketPlanProofIT` proves every statement bounded under both scopes.
     */
    fun rules(pages: TicketProperties.Pages): QueryRules =
        QueryRules(
            shapes = SHAPES,
            selectable = FieldGrant.All,
            includable = FieldGrant.None,
            pagination = Pagination(pages.defaultLimit, pages.maxLimit, pages.maxOffset, pages.countCap),
        )

    /** The tickets assigned to the calling agent. */
    val ASSIGNED: ScopeRule.Rows = ScopeRule.Rows { caller -> Predicate.eq(TicketFields.ASSIGNEE, agentOf(caller)) }

    /** Every ticket for an agent holding `ticket.read`; the tickets assigned to it for any other — decided per request. */
    val SCOPE: ScopeRule = ScopeRule.EveryRowWhenHolding(setOf(TicketPermissions.READ), ASSIGNED)

    fun resource(
        store: CrudStore<Ticket>,
        callers: CallerLookup,
        pages: TicketProperties.Pages,
    ): CrudResource<Ticket> = CrudResource(rules(pages), ResourcePolicy(ACCESS, SCOPE, WRITABLE), store, callers, emptyList())

    /** The one table the ticket routes and their access declarations come from. */
    fun mounted(resource: CrudResource<Ticket>): MountedResource<Ticket> = MountedResource(PREFIX, OPERATIONS, resource)

    private fun agentOf(caller: Caller.Authenticated): UUID {
        if (caller.actor.type != Agents.TYPE.name) throw Fault.forbidden(message = "tickets are assigned to agents only")
        return UUID.fromString(caller.actor.id)
    }
}

/** The codes this application lets reach a client. */
object SampleErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-sample"

    val TICKET_CLOSED: ErrorCode = ErrorCode.of("ticket_closed", "the ticket is already closed")

    override val codes: List<ErrorCode> = listOf(TICKET_CLOSED)
}

/** The evidence the helpdesk records, in the transaction of the change it describes. */
object TicketAudit {
    const val MODULE = "helpdesk"
    const val RESOURCE = "ticket"

    val CREATED = AuditEventType(MODULE, "ticket-created", RESOURCE, setOf("priority", "assigned"))
    val UPDATED = AuditEventType(MODULE, "ticket-updated", RESOURCE, setOf("fields", "version"))
    val CLOSED = AuditEventType(MODULE, "ticket-closed", RESOURCE, setOf("version"))
    val DELETED = AuditEventType(MODULE, "ticket-deleted", RESOURCE, setOf("cancelled_jobs"))
}
