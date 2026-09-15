package com.gd.rain.sample

import com.gd.rain.crud.Caller
import com.gd.rain.crud.CallerLookup
import com.gd.rain.crud.RowScope
import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.persistence.RowReader
import com.gd.rain.crud.proof.CrudPlanProof
import com.gd.rain.crud.proof.ProofScope
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.web.MountedResource
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.sample.config.TicketProperties
import com.gd.rain.sample.ticket.Ticket
import com.gd.rain.sample.ticket.TicketDeclarations
import com.gd.rain.sample.ticket.TicketFields
import com.gd.rain.sample.ticket.TicketKey
import com.gd.rain.sample.ticket.TicketRows
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A migrated, empty helpdesk database: the application's own migrations, no row, no statistics. */
private class ProofDatabase(
    prefix: String,
) {
    val database: RainDatabase = RainPostgres.freshDatabase(prefix)
    val dataSource: DataSource = database.dataSource()
    val dsl: DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

    init {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

/**
 * Every statement the ticket resource runs — each declared query shape's pages and counts, and every statement by
 * identifier of every mounted operation — explained against the migrated, empty database, under both scopes the
 * helpdesk serves, and bounded by plan criterion v3.
 */
@Tag("integration")
class TicketPlanProofIT {
    private val pages = TicketProperties.Pages(defaultLimit = 25, maxLimit = 100, maxOffset = 1000, countCap = 1000)
    private val nobody = CallerLookup { Caller.Anonymous }

    @Test
    fun `every ticket, as a supervisor reads them, is bounded by the ticket indexes`() {
        val proof = ProofDatabase("ticket_proof_every")
        val store = JooqResourceStore(TicketFields.SCHEMA, proof.dsl, UuidV7Ids, RowReader.fields(TicketFields.SCHEMA))
        val mounted =
            MountedResource(TicketDeclarations.PREFIX, TicketDeclarations.OPERATIONS, TicketDeclarations.every(store, nobody, pages))

        val result = CrudPlanProof.verify(store, mounted, listOf(ProofScope("every ticket", RowScope.Everything)), proof.dataSource)

        result.assertBounded()
        assertThat(result.statements).isNotEmpty()
    }

    @Test
    fun `the tickets assigned to one agent, as a responder reads them, are bounded by the ticket indexes`() {
        val proof = ProofDatabase("ticket_proof_assigned")
        val store = JooqResourceStore(TicketFields.SCHEMA, proof.dsl, UuidV7Ids, RowReader.fields(TicketFields.SCHEMA))
        val mounted =
            MountedResource(TicketDeclarations.PREFIX, TicketDeclarations.OPERATIONS, TicketDeclarations.assigned(store, nobody, pages))
        val agent = UUID.fromString("00000000-0000-7000-8000-00000000abcd")

        val result =
            CrudPlanProof.verify(
                store,
                mounted,
                listOf(ProofScope("assigned to one agent", RowScope.Matching(Predicate.eq(TicketFields.ASSIGNEE, agent)))),
                proof.dataSource,
            )

        result.assertBounded()
        assertThat(result.statements).isNotEmpty()
    }
}

/** The statements the helpdesk writes itself, beside rain-crud's: each bounded by plan criterion v3 over `public.tickets`. */
@Tag("integration")
class TicketStatementPlansIT {
    private val proof = ProofDatabase("ticket_statements")
    private val rows = TicketRows(proof.dsl)
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val at = Instant.parse("2026-09-15T10:00:00Z")

    private fun explain(query: Query) = QueryPlans.explain(proof.dataSource, proof.dsl.renderInlined(query), generic = false)

    @Test
    fun `a report page reads the open-ticket index under a limit, first and after a ticket`() {
        listOf(null, TicketKey(at, id)).forEach { after ->
            val plan = explain(rows.openPageQuery(after, 50))

            assertThat(plan.usesIndex("ix_tickets_status_created_at")).describedAs(plan.json).isTrue()
            assertThat(plan.boundedScan("public", "tickets")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
        }
    }

    @Test
    fun `an escalation batch reads the escalation index under a limit and writes by primary key`() {
        listOf(null, TicketKey(at, id)).forEach { after ->
            val plan = explain(rows.escalationQuery(at, after, 200, 90, at))

            assertThat(plan.usesIndex("ix_tickets_escalation")).describedAs(plan.json).isTrue()
            assertThat(plan.boundedScan("public", "tickets")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
        }
    }

    @Test
    fun `locking a ticket, reading its text and storing its summary are lookups by primary key`() {
        val statements: List<Query> =
            listOf(
                proof.dsl
                    .select(DSL.field(DSL.name("version")))
                    .from(DSL.table(DSL.name("public", "tickets")))
                    .where(DSL.field(DSL.name("id"), UUID::class.java).eq(id))
                    .forUpdate(),
            )
        (statements + rowsStatements()).forEach { query ->
            val plan = explain(query)

            assertThat(plan.boundedScan("public", "tickets")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
        }
    }

    /** The helpdesk's own statements by identifier, rendered by jOOQ without executing them. */
    private fun rowsStatements(): List<Query> {
        val table = DSL.table(DSL.name("public", "tickets"))
        val idField = DSL.field(DSL.name("id"), UUID::class.java)
        return listOf(
            proof.dsl
                .select(DSL.field(DSL.name("title")), DSL.field(DSL.name("body")))
                .from(table)
                .where(idField.eq(id)),
            proof.dsl
                .update(table)
                .set(DSL.field(DSL.name("summary"), String::class.java), "s")
                .where(idField.eq(id)),
        )
    }
}
