package com.gd.rain.sample.ticket

import com.gd.rain.access.GrantsLookup
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

/** The ticket resource as rain-crud serves it: shapes, policy, the two scopes and the mounted operations. */
object TicketDeclarations {
    const val PREFIX = "/v1/tickets"

    const val READ_WHY = "every agent reads tickets; ticket.read widens what it reads from the tickets assigned to it to every ticket"

    /**
     * The list queries a caller holding `ticket.read` is answered: the tickets changed most recently, the tickets of a
     * status newest first, and one agent's tickets of a status highest priority first. Any other query is `400 not_offered`.
     */
    val EVERY_SHAPES: List<QueryShape> =
        listOf(
            QueryShape.of(SortKey.parse("-updatedAt")),
            QueryShape.of(SortKey.parse("-createdAt"), "status" to Operator.EQ),
            QueryShape.of(SortKey.parse("-priority"), "assignee" to Operator.EQ, "status" to Operator.EQ),
        )

    /** The list queries a responder, confined to the tickets assigned to it, is answered: its recent changes, and its tickets of a status by priority. */
    val ASSIGNED_SHAPES: List<QueryShape> =
        listOf(
            QueryShape.of(SortKey.parse("-updatedAt")),
            QueryShape.of(SortKey.parse("-priority"), "status" to Operator.EQ),
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
     * The shapes and their indexes (`V1__helpdesk.sql`) are chosen so that no two shapes of one scope sort by the same
     * field while one's equality filters are part of the other's: an index serving the smaller set would then serve the
     * larger with a `Filter`, and on the empty table the plan proof explains against PostgreSQL picks between the two by
     * a cost tie. `TicketPlanProofIT` proves every statement of both resources bounded.
     */
    fun rules(
        shapes: List<QueryShape>,
        pages: TicketProperties.Pages,
    ): QueryRules =
        QueryRules(
            shapes = shapes,
            selectable = FieldGrant.All,
            includable = FieldGrant.None,
            pagination = Pagination(pages.defaultLimit, pages.maxLimit, pages.maxOffset, pages.countCap),
        )

    /** The tickets assigned to the calling agent. */
    val ASSIGNED: ScopeRule = ScopeRule.Rows { caller -> Predicate.eq(TicketFields.ASSIGNEE, agentOf(caller)) }

    fun every(
        store: CrudStore<Ticket>,
        callers: CallerLookup,
        pages: TicketProperties.Pages,
    ): CrudResource<Ticket> =
        CrudResource(rules(EVERY_SHAPES, pages), ResourcePolicy(ACCESS, ScopeRule.Unrestricted, WRITABLE), store, callers, emptyList())

    fun assigned(
        store: CrudStore<Ticket>,
        callers: CallerLookup,
        pages: TicketProperties.Pages,
    ): CrudResource<Ticket> =
        CrudResource(rules(ASSIGNED_SHAPES, pages), ResourcePolicy(ACCESS, ASSIGNED, WRITABLE), store, callers, emptyList())

    private fun agentOf(caller: Caller.Authenticated): UUID {
        if (caller.actor.type != Agents.TYPE.name) throw Fault.forbidden(message = "tickets are assigned to agents only")
        return UUID.fromString(caller.actor.id)
    }
}

/**
 * The ticket resource for the caller of this request.
 *
 * rain-crud's scope rule answers a predicate for every authenticated caller and has no way to say "every row" for some
 * of them, so the two scopes are two resources over one store with the same rules and policy: [every] for a caller
 * holding `ticket.read`, [assigned] for any other, each with the query shapes its callers are served. Both enforce the same
 * access, and [mounted] — whose declarations come from that policy — is the one table the routes are verified against.
 */
class TicketResources(
    val every: CrudResource<Ticket>,
    val assigned: CrudResource<Ticket>,
    private val grants: GrantsLookup,
) {
    val mounted: MountedResource<Ticket> = MountedResource(TicketDeclarations.PREFIX, TicketDeclarations.OPERATIONS, every)

    fun forCaller(): CrudResource<Ticket> {
        // An anonymous caller is refused by either resource; the unrestricted one refuses it the same way.
        val principal = grants.principalOf() ?: return every
        return if (TicketPermissions.READ in grants.heldBy(principal.subject, setOf(TicketPermissions.READ))) every else assigned
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
