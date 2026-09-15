package com.gd.rain.sample

import com.gd.rain.audit.AuditRecorder
import com.gd.rain.sample.access.HelpdeskRoles
import com.gd.rain.sample.stand.SampleProcess
import com.gd.rain.sample.stand.Staff
import com.gd.rain.sample.stand.Stand
import com.gd.rain.sample.stand.code
import com.gd.rain.sample.stand.json
import com.gd.rain.sample.stand.query
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import java.util.UUID

/** The ticket resource over HTTP, as agents use it: pages, counts, the scope of a responder, writes and their evidence. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketCrudE2E {
    private lateinit var database: RainDatabase
    private lateinit var api: SampleProcess
    private lateinit var supervisor: Pair<String, String>
    private lateinit var supervisorId: UUID

    @BeforeAll
    fun start() {
        database = RainPostgres.freshDatabase("ticket_crud")
        Stand.migrate(database)
        api = SampleProcess.api(database, "sample.tickets.pages.count-cap=3")
        Staff.ensureRoles(api)
        supervisorId = Staff.enrol(api, SUPERVISOR, HelpdeskRoles.SUPERVISOR)
        supervisor = Staff.bearer(api, SUPERVISOR)
    }

    @AfterAll
    fun stop() {
        if (::api.isInitialized) api.close()
    }

    private fun open(
        caller: Pair<String, String>,
        title: String,
        assignee: UUID?,
        priority: Int = 10,
    ): JsonNode {
        val assigned = assignee?.let { "\"$it\"" } ?: "null"
        val answer =
            api.http.send(
                "POST",
                "/v1/tickets",
                """{"title":"$title","body":"the body of $title","priority":$priority,"assignee":$assigned}""",
                caller,
            )
        assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(201)
        return answer.json()
    }

    private fun agent(name: String): Pair<UUID, Pair<String, String>> {
        val identifier = "$name@helpdesk.example"
        val id = Staff.enrol(api, identifier, HelpdeskRoles.RESPONDER)
        return id to Staff.bearer(api, identifier)
    }

    private fun ids(page: JsonNode): List<String> = page["items"].values().map { it["id"].asString() }

    @Test
    fun `cursor pages walk forward and back over one agent's open tickets, highest priority first`() {
        val (pager, _) = agent("pager")
        val opened = listOf(30, 10, 50, 20, 40).map { open(supervisor, "page $it", pager, priority = it) }
        val newestFirst =
            opened
                .sortedWith(
                    compareBy<JsonNode>({ it["priority"].asInt() }, { UUID.fromString(it["id"].asString()) }).reversed(),
                ).map { it["id"].asString() }
        val shape =
            arrayOf(
                "filter[assignee][eq]" to pager.toString(),
                "filter[status][eq]" to "open",
                "sort" to "-priority",
                "limit" to "2",
            )

        val first = api.http.get("/v1/tickets" + query(*shape), supervisor).json()
        val second = api.http.get("/v1/tickets" + query(*shape, "cursor" to first["page"]["next"].asString()), supervisor).json()
        val third = api.http.get("/v1/tickets" + query(*shape, "cursor" to second["page"]["next"].asString()), supervisor).json()
        val back = api.http.get("/v1/tickets" + query(*shape, "cursor" to third["page"]["prev"].asString()), supervisor).json()

        assertThat(ids(first)).isEqualTo(newestFirst.subList(0, 2))
        assertThat(first["page"].has("prev")).isFalse()
        assertThat(ids(second)).isEqualTo(newestFirst.subList(2, 4))
        assertThat(ids(third)).isEqualTo(newestFirst.subList(4, 5))
        assertThat(third["page"].has("next")).isFalse()
        assertThat(ids(back)).isEqualTo(newestFirst.subList(2, 4))
    }

    @Test
    fun `a count reads no further than the declared cap`() {
        val (few, _) = agent("few")
        val (many, _) = agent("many")
        repeat(2) { open(supervisor, "few $it", few) }
        repeat(4) { open(supervisor, "many $it", many) }

        fun count(assignee: UUID): JsonNode =
            api.http
                .get(
                    "/v1/tickets/count" + query("filter[assignee][eq]" to assignee.toString(), "filter[status][eq]" to "open"),
                    supervisor,
                ).json()

        assertThat(count(few)["count"].toString()).isEqualTo("""{"value":2,"exact":true}""")
        assertThat(count(many)["count"].toString()).isEqualTo("""{"value":3,"exact":false}""")
    }

    @Test
    fun `a responder without ticket read reaches only the tickets assigned to it`() {
        val (responder, bearer) = agent("scoped")
        val (other, _) = agent("elsewhere")
        val mine = open(supervisor, "mine", responder)["id"].asString()
        val theirs = open(supervisor, "theirs", other)["id"].asString()

        val listed = api.http.get("/v1/tickets" + query("sort" to "-updatedAt"), bearer).json()
        val counted = api.http.get("/v1/tickets/count", bearer).json()

        assertThat(ids(listed)).containsExactly(mine)
        assertThat(counted["count"]["value"].asLong()).isEqualTo(1)
        assertThat(api.http.get("/v1/tickets/$mine", bearer).statusCode()).isEqualTo(200)
        assertThat(api.http.get("/v1/tickets/$theirs", bearer).statusCode()).isEqualTo(404)
        assertThat(api.http.send("POST", "/v1/tickets/$theirs/close", null, bearer).statusCode()).isEqualTo(404)
        assertThat(api.http.get("/v1/tickets/$theirs", supervisor).statusCode()).isEqualTo(200)
    }

    @Test
    fun `a responder opens tickets for itself and is refused one assigned to somebody else`() {
        val (responder, bearer) = agent("opener")
        val (other, _) = agent("recipient")

        val refused =
            api.http.send("POST", "/v1/tickets", """{"title":"t","body":"b","priority":1,"assignee":"$other"}""", bearer)
        val opened = open(bearer, "for me", responder)

        assertThat(refused.statusCode() to refused.code()).isEqualTo(403 to "outside_scope")
        assertThat(opened["assignee"].asString()).isEqualTo(responder.toString())
        assertThat(opened["version"].asLong()).isEqualTo(1)
    }

    @Test
    fun `HEAD on the ticket list is held to its GET declaration - 401 for nobody, 200 with no body for an agent`() {
        val recent = "/v1/tickets" + query("sort" to "-updatedAt")
        val anonymous = api.http.send("HEAD", recent)
        val signedIn = api.http.send("HEAD", recent, null, supervisor)

        assertThat(anonymous.statusCode()).isEqualTo(401)
        assertThat(signedIn.statusCode()).describedAs(signedIn.headers().map().toString()).isEqualTo(200)
        assertThat(signedIn.body()).isEmpty()
    }

    @Test
    fun `a responder lists its own open tickets by priority with the shape a supervisor uses for any agent`() {
        val (responder, bearer) = agent("prioritised")
        val (other, _) = agent("unprioritised")
        val mine = open(supervisor, "mine by priority", responder, priority = 70)["id"].asString()
        open(supervisor, "theirs by priority", other, priority = 90)

        val own =
            api.http.get(
                "/v1/tickets" +
                    query(
                        "filter[assignee][eq]" to responder.toString(),
                        "filter[status][eq]" to "open",
                        "sort" to "-priority",
                    ),
                bearer,
            )
        val another =
            api.http.get(
                "/v1/tickets" + query("filter[assignee][eq]" to other.toString(), "filter[status][eq]" to "open", "sort" to "-priority"),
                bearer,
            )

        assertThat(ids(own.json())).containsExactly(mine)
        assertThat(ids(another.json())).describedAs("the scope confines a filter naming another agent").isEmpty()
    }

    @Test
    fun `a query no declared shape serves is 400 not_offered`() {
        val bySort = api.http.get("/v1/tickets" + query("sort" to "title"), supervisor)
        val byFilter = api.http.get("/v1/tickets" + query("filter[priority][eq]" to "1"), supervisor)

        assertThat(bySort.statusCode() to bySort.code()).isEqualTo(400 to "not_offered")
        assertThat(byFilter.statusCode() to byFilter.code()).isEqualTo(400 to "not_offered")
    }

    @Test
    fun `a write body names every problem it has at once`() {
        val refused = api.http.send("POST", "/v1/tickets", """{"title":" ","priority":500,"status":"closed","colour":"red"}""", supervisor)

        assertThat(refused.statusCode() to refused.code()).isEqualTo(422 to "validation_failed")
        assertThat(refused.json()["errors"].values().map { it["pointer"].asString() to it["code"].asString() })
            .contains(
                "/title" to "required",
                "/body" to "required",
                "/priority" to "out_of_range",
                "/status" to "field_not_granted",
                "/colour" to "unknown_field",
            )
    }

    @Test
    fun `a change states its version, closing twice is ticket_closed, and every change leaves evidence in its transaction`() {
        val ticket = open(supervisor, "lifecycle", supervisorId)
        val id = ticket["id"].asString()

        val changed = api.http.send("PATCH", "/v1/tickets/$id", """{"version":1,"title":"lifecycle, renamed"}""", supervisor)
        val stale = api.http.send("PATCH", "/v1/tickets/$id", """{"version":1,"priority":50}""", supervisor)
        val closed = api.http.send("POST", "/v1/tickets/$id/close", null, supervisor)
        val closedAgain = api.http.send("POST", "/v1/tickets/$id/close", null, supervisor)
        val deleted = api.http.send("DELETE", "/v1/tickets/$id", null, supervisor)
        val deletedAgain = api.http.send("DELETE", "/v1/tickets/$id", null, supervisor)

        assertThat(changed.statusCode()).describedAs(changed.body()).isEqualTo(200)
        assertThat(changed.json()["version"].asLong()).isEqualTo(2)
        assertThat(stale.statusCode() to stale.code()).isEqualTo(409 to "stale_version")
        assertThat(closed.json()["status"].asString() to closed.json()["version"].asLong()).isEqualTo("closed" to 3L)
        assertThat(closedAgain.statusCode() to closedAgain.code()).isEqualTo(409 to "ticket_closed")
        assertThat(deleted.body()).isEqualTo("""{"deleted":1}""")
        assertThat(deletedAgain.statusCode()).isEqualTo(404)

        val trail = api.bean(AuditRecorder::class).ofResource("ticket", id, null, 10).entries
        assertThat(trail.map { it.action }).containsExactly("ticket-deleted", "ticket-closed", "ticket-updated", "ticket-created")
        assertThat(trail.map { it.actor?.id }.distinct()).containsExactly(supervisorId.toString())
        assertThat(Stand.JSON.readTree(trail.first { it.action == "ticket-updated" }.detailJson))
            .isEqualTo(Stand.JSON.readTree("""{"fields":"title","version":2}"""))
    }

    private companion object {
        const val SUPERVISOR = "crud-supervisor@helpdesk.example"
    }
}
