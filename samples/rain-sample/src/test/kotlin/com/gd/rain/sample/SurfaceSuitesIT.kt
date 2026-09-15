package com.gd.rain.sample

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.GrantsLookup
import com.gd.rain.access.ModuleGrants
import com.gd.rain.sample.access.HelpdeskRoles
import com.gd.rain.sample.access.TicketPermissions
import com.gd.rain.sample.agent.AgentRegistry
import com.gd.rain.sample.agent.Agents
import com.gd.rain.sample.stand.SampleProcess
import com.gd.rain.sample.stand.SharedApi
import com.gd.rain.sample.stand.Staff
import com.gd.rain.sample.stand.Stand
import com.gd.rain.sample.stand.Surface
import com.gd.rain.sample.stand.code
import com.gd.rain.sample.stand.json
import com.gd.rain.test.RainPostgres
import com.gd.rain.web.probe.ProbeSurface
import com.gd.rain.web.route.EndpointDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.http.HttpResponse
import java.util.UUID

private val BODYLESS = setOf("GET", "DELETE", "HEAD", "OPTIONS")

private fun SampleProcess.call(
    declaration: EndpointDeclaration,
    vararg headers: Pair<String, String>,
): HttpResponse<String> =
    http.send(
        declaration.method,
        Surface.filled(declaration, SharedApi.VARIABLES),
        if (declaration.method in
            BODYLESS
        ) {
            null
        } else {
            "{}"
        },
        *headers,
    )

/**
 * Every route that needs a caller, asked by nobody at all. A declaration is what the start-up verification compares with
 * the mounted routes; this is the other half — what each route answers when no credential is presented.
 * Ported from the Lease `AnonymousSurfaceIT`: the sweep reads the surface from the running process, probes and error
 * dispatch aside, and the allow-list of routes that may serve an anonymous caller is empty.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnonymousSurfaceIT {
    private val api get() = SharedApi.process

    private fun guarded(): List<EndpointDeclaration> = Surface.of(api.context).filterNot { it.public }

    @Test
    fun `the sweep asks the whole guarded surface`() {
        assertThat(
            guarded().map {
                it.key
            },
        ).contains("GET /v1/tickets", "POST /v1/tickets/{id}/close", "GET /v1/tickets/{id}/events", "GET /v1/roles")
    }

    @Test
    fun `no route that needs a caller answers one nobody authenticated, and every refusal is unauthenticated problem json`() {
        val answered =
            guarded().mapNotNull { declaration ->
                val answer = api.call(declaration)
                val refusal =
                    answer.statusCode() == 401 && answer.headers().firstValue("Content-Type").orElse("") == "application/problem+json"
                if (refusal && answer.code() == "unauthenticated") null else "${declaration.key} -> ${answer.statusCode()} ${answer.body()}"
            }

        assertThat(answered).isEmpty()
    }
}

/**
 * What a caller who is signed in and holds the wrong permission gets, and the refusals the directory owes an
 * administrator. Ported from the Lease `AuthorizationIT` onto the helpdesk's tickets and rain-access's own routes.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationIT {
    private val api get() = SharedApi.process
    private lateinit var admin: Pair<String, String>
    private lateinit var nobody: Pair<String, String>

    @BeforeAll
    fun signIn() {
        admin = Staff.bearer(api, SharedApi.ADMINISTRATOR)
        nobody = Staff.bearer(api, SharedApi.NOBODY)
    }

    private fun holder(
        name: String,
        vararg permissions: String,
    ): Pair<UUID, Pair<String, String>> {
        val slug = "holds-$name"
        api.bean(AccessProvisioning::class).ensureRole(slug, "Holds $name", permissions.toSet())
        val identifier = "$name@helpdesk.example"
        val id = Staff.enrol(api, identifier, slug)
        return id to Staff.bearer(api, identifier)
    }

    @Test
    fun `no route that names a permission answers a signed-in agent holding none`() {
        val served =
            Surface.of(api.context).filter { it.permissions.isNotEmpty() }.mapNotNull { declaration ->
                val answer = api.call(declaration, nobody)
                if (answer.statusCode() == 403 && answer.code() == "forbidden") null else "${declaration.key} -> ${answer.statusCode()}"
            }

        assertThat(served).isEmpty()
    }

    @Test
    fun `an anonymous caller reaches nothing that needs a caller, and a signed-in one holding nothing is forbidden, not unauthenticated`() {
        listOf(
            "GET" to "/v1/auth/me",
            "GET" to "/v1/auth/sessions",
            "POST" to "/v1/auth/logout",
            "GET" to "/v1/tickets",
            "GET" to "/v1/roles",
        ).forEach { (method, path) ->
            assertThat(api.http.send(method, path).statusCode()).describedAs("$method $path").isEqualTo(401)
        }
        assertThat(api.http.get("/v1/roles", nobody).let { it.statusCode() to it.code() }).isEqualTo(403 to "forbidden")
    }

    @Test
    fun `an anonymous write on a role does not say whether the role exists`() {
        val role =
            api
                .bean(
                    AccessProvisioning::class,
                ).ensureRole(HelpdeskRoles.SUPERVISOR, "Supervisor", HelpdeskRoles.SUPERVISOR_PERMISSIONS)

        listOf(
            api.http.send("DELETE", "/v1/roles/$role"),
            api.http.send("PATCH", "/v1/roles/$role", """{"name":"Root"}"""),
            api.http.send("POST", "/v1/roles/bulk-delete", """{"ids":["$role"]}"""),
        ).forEach { answer ->
            assertThat(answer.statusCode()).isEqualTo(401)
            assertThat(answer.body()).doesNotContain(HelpdeskRoles.SUPERVISOR)
        }
    }

    @Test
    fun `a permission is checked per verb, ticket write does not delete, read roles, grant or set passwords`() {
        val (id, writer) = holder("writer", TicketPermissions.WRITE)
        val ticket =
            api.http
                .send(
                    "POST",
                    "/v1/tickets",
                    """{"title":"t","body":"b","priority":1,"assignee":"$id"}""",
                    writer,
                ).json()["id"]
                .asString()

        assertThat(
            api.http.send("POST", "/v1/tickets/$ticket/close", null, writer).statusCode(),
        ).describedAs("close is ticket.write").isEqualTo(200)
        listOf(
            api.http.send("DELETE", "/v1/tickets/$ticket", null, writer),
            api.http.get("/v1/roles", writer),
            api.http.send("POST", "/v1/subjects/agent/$id/roles", """{"role":"${HelpdeskRoles.SUPERVISOR}"}""", writer),
            api.http.send("PUT", "/v1/subjects/agent/$id/password", """{"password":"$LONG_PASSWORD"}""", writer),
        ).forEach { answer -> assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(403) }
    }

    @Test
    fun `deactivating an agent locks its live session out and keeps its row`() {
        val (id, bearer) = holder("leaver", TicketPermissions.WRITE)
        assertThat(api.http.get("/v1/auth/me", bearer).statusCode()).isEqualTo(200)

        api.bean(AgentRegistry::class).setActive(id, false)

        assertThat(api.http.get("/v1/auth/me", bearer).statusCode()).isEqualTo(401)
        assertThat(api.bean(AgentRegistry::class).find(id)?.identifier).isEqualTo("leaver@helpdesk.example")
    }

    @Test
    fun `the system role cannot be renamed or removed, an ordinary role can, and no role made through the api is a system role`() {
        val administrator = api.bean(AccessProvisioning::class).ensureRole(HelpdeskRoles.ADMINISTRATOR, "Administrator", emptySet())

        listOf(
            api.http.send("PATCH", "/v1/roles/$administrator", """{"name":"Root"}""", admin),
            api.http.send("DELETE", "/v1/roles/$administrator", null, admin),
            api.http.send("POST", "/v1/roles/bulk-delete", """{"ids":["$administrator"]}""", admin),
        ).forEach { answer -> assertThat(answer.statusCode() to answer.code()).isEqualTo(403 to "system_role") }

        val created = api.http.send("POST", "/v1/roles", """{"slug":"temporary","name":"Temporary","system":true}""", admin)
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(201)
        assertThat(created.json()["system"].asBoolean()).isFalse()
        assertThat(api.http.send("DELETE", "/v1/roles/${created.json()["id"].asString()}", null, admin).statusCode()).isEqualTo(204)
    }

    @Test
    fun `the permission catalogue is read only over http`() {
        assertThat(api.http.get("/v1/permissions", admin).statusCode()).isEqualTo(200)
        assertThat(api.http.send("POST", "/v1/permissions", """{"code":"made.up"}""", admin).statusCode()).isEqualTo(405)
    }

    @Test
    fun `setting a password needs access credential write, not access grant write`() {
        val (_, manager) = holder("manager", "access.grant.read", "access.grant.write", "access.role.read")
        val victim = Staff.enrol(api, "managed@helpdesk.example")

        assertThat(
            api.http.send("POST", "/v1/subjects/agent/$victim/roles", """{"role":"${HelpdeskRoles.SUPERVISOR}"}""", manager).statusCode(),
        ).describedAs("granting a role is what access.grant.write buys")
            .isEqualTo(204)
        assertThat(api.http.send("PUT", "/v1/subjects/agent/$victim/password", """{"password":"$LONG_PASSWORD"}""", manager).statusCode())
            .isEqualTo(403)
        Staff.bearer(api, "managed@helpdesk.example")
    }

    @Test
    fun `an administrator setting a password still meets the password floor`() {
        val subject = Staff.enrol(api, "floor@helpdesk.example")

        val answer = api.http.send("PUT", "/v1/subjects/agent/$subject/password", """{"password":"tiny"}""", admin)

        assertThat(answer.statusCode()).isEqualTo(422)
        assertThat(answer.json()["errors"][0]["pointer"].asString() to answer.json()["errors"][0]["code"].asString())
            .isEqualTo("/password" to "weak_password")
        Staff.bearer(api, "floor@helpdesk.example")
    }

    private companion object {
        const val LONG_PASSWORD = "a perfectly long password"
    }
}

/** The HTTP surface the running api process verified at start-up. Ported from the Lease `HttpSurfaceIT`. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpSurfaceIT {
    private val api get() = SharedApi.process

    @Test
    fun `every verified route has one method and one path, and each is declared once`() {
        val declarations = Surface.of(api.context)

        assertThat(declarations.map { it.key }).doesNotHaveDuplicates()
        assertThat(declarations).allMatch({
            it.path.startsWith("/") && it.method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        }, "a method and a path")
        assertThat(declarations.flatMap { it.problems() }).isEmpty()
    }

    @Test
    fun `the literal count route is served beside the item route, not read as an id`() {
        val bearer = Staff.bearer(api, SharedApi.ADMINISTRATOR)

        assertThat(Surface.of(api.context).map { it.key }).contains("GET /v1/tickets/count", "GET /v1/tickets/{id}")
        assertThat(
            api.http
                .get("/v1/tickets/count", bearer)
                .json()
                .propertyNames(),
        ).containsExactly("count")
    }

    @Test
    fun `no route names a permission a module does not declare`() {
        val catalogue =
            api.context
                .getBeansOfType(ModuleGrants::class.java)
                .values
                .flatMap { module ->
                    module.permissions.map { it.code }
                }.toSet()

        assertThat(Surface.of(api.context).flatMap { it.permissions }.toSet()).isSubsetOf(catalogue)
    }

    @Test
    fun `the ticket event stream is declared like every other route, and the probes declare themselves`() {
        val byKey = Surface.of(api.context).associateBy { it.key }

        assertThat(byKey.getValue("GET /v1/tickets/{id}/events").authenticated).isTrue()
        assertThat(byKey.getValue("GET /live")).isEqualTo(EndpointDeclaration("GET", "/live", public = true, why = ProbeSurface.WHY))
        assertThat(byKey.getValue("GET /ready").public).isTrue()
    }
}

/** What a mistaken request gets from the dispatcher and the binders, which no controller writes. Ported from the Lease `RouterIT`. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouterIT {
    private val api get() = SharedApi.process
    private lateinit var admin: Pair<String, String>

    @BeforeAll
    fun signIn() {
        admin = Staff.bearer(api, SharedApi.ADMINISTRATOR)
    }

    private fun HttpResponse<String>.violation(): Pair<String, String> =
        json()["errors"][0].let {
            it["pointer"].asString() to
                it["code"].asString()
        }

    @Test
    fun `a routing mistake keeps its status and answers problem json`() {
        val missing = api.http.get("/v1/nothing-here", admin)
        val wrongVerb = api.http.send("PUT", "/v1/permissions", "{}", admin)

        assertThat(missing.statusCode() to missing.code()).isEqualTo(404 to "not_found")
        assertThat(wrongVerb.statusCode() to wrongVerb.code()).isEqualTo(405 to "method_not_allowed")
    }

    @Test
    fun `a path parameter that is not what its type says names the parameter`() {
        val subjectId = api.http.get("/v1/subjects/agent/not-a-uuid/roles", admin)
        val subjectType = api.http.get("/v1/subjects/robot/${UUID.randomUUID()}/roles", admin)
        val ticketId = api.http.get("/v1/tickets/not-a-uuid", admin)

        assertThat(subjectId.statusCode() to subjectId.violation()).isEqualTo(400 to ("/subjectId" to "invalid_id"))
        assertThat(subjectType.statusCode() to subjectType.violation()).isEqualTo(400 to ("/subjectType" to "unknown_subject_type"))
        assertThat(ticketId.statusCode() to ticketId.violation()).isEqualTo(400 to ("/id" to "invalid_id"))
    }

    @Test
    fun `a grant for a subject type nobody serves is refused before anything is written`() {
        val subject = UUID.randomUUID()

        val answer = api.http.send("POST", "/v1/subjects/robot/$subject/roles", """{"role":"${HelpdeskRoles.SUPERVISOR}"}""", admin)

        assertThat(answer.statusCode() to answer.code()).isEqualTo(400 to "unknown_subject_type")
        assertThat(
            Stand.jdbc(SharedApi.database).queryForObject(
                "SELECT count(*) FROM rain_access.subject_roles WHERE subject_type = 'robot' AND subject_id = ?",
                Long::class.java,
                subject,
            ),
        ).isZero()
    }
}

/**
 * The grant routes `/v1/subjects/{subjectType}/{subjectId}/…` and `/v1/roles/{roleId}/permissions`, exercised as
 * requests, every refusal with its status and its code. Ported from the Lease `SubjectGrantSurfaceIT`; what a subject
 * holds in effect is asked of `GrantsLookup`, which is how rain-access answers it.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubjectGrantSurfaceIT {
    private val api get() = SharedApi.process
    private lateinit var admin: Pair<String, String>

    @BeforeAll
    fun signIn() {
        admin = Staff.bearer(api, SharedApi.ADMINISTRATOR)
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        caller: Pair<String, String> = admin,
    ): HttpResponse<String> = api.http.send(method, path, body, caller)

    private fun rolesOf(subject: UUID): List<String> =
        send("GET", "/v1/subjects/agent/$subject/roles").json()["items"].values().map { it["slug"].asString() }

    private fun directOf(subject: UUID): List<String> =
        send("GET", "/v1/subjects/agent/$subject/permissions").json()["items"].values().map { it["code"].asString() }

    private fun holds(
        subject: UUID,
        code: String,
    ): Boolean = code in api.bean(GrantsLookup::class).heldBy(Agents.ref(subject), setOf(code))

    @Test
    fun `granting a role is 204 however often, and revoking it is 204 however often`() {
        val subject = Staff.enrol(api, "granted@helpdesk.example")

        repeat(2) { assertThat(send("POST", "/v1/subjects/agent/$subject/roles", """{"role":"responder"}""").statusCode()).isEqualTo(204) }
        assertThat(rolesOf(subject)).containsExactly("responder")
        repeat(2) { assertThat(send("DELETE", "/v1/subjects/agent/$subject/roles/responder").statusCode()).isEqualTo(204) }
        assertThat(rolesOf(subject)).isEmpty()
    }

    @Test
    fun `granting and revoking a direct permission is 204 each way, and what the subject holds follows`() {
        val subject = Staff.enrol(api, "direct@helpdesk.example")

        assertThat(
            send("POST", "/v1/subjects/agent/$subject/permissions", """{"permission":"ticket.delete"}""").statusCode(),
        ).isEqualTo(204)
        assertThat(directOf(subject)).containsExactly("ticket.delete")
        assertThat(holds(subject, "ticket.delete")).isTrue()
        assertThat(send("DELETE", "/v1/subjects/agent/$subject/permissions/ticket.delete").statusCode()).isEqualTo(204)
        assertThat(directOf(subject)).isEmpty()
        assertThat(holds(subject, "ticket.delete")).isFalse()
    }

    @Test
    fun `setting a subject's password closes its sessions, and only the new password signs in, however the identifier is cased`() {
        val subject = Staff.enrol(api, "reset.me@helpdesk.example")
        val session =
            api.http
                .send(
                    "POST",
                    Staff.AGENT_LOGIN,
                    Staff.credentials("reset.me@helpdesk.example"),
                    "Rain-Auth-Delivery" to "body",
                ).json()

        val set = send("PUT", "/v1/subjects/agent/$subject/password", """{"password":"$NEXT"}""")

        assertThat(set.statusCode()).isEqualTo(204)
        assertThat(api.http.send("POST", "/v1/auth/refresh", """{"refreshToken":"${session["refreshToken"].asString()}"}""").statusCode())
            .isEqualTo(401)
        assertThat(
            api.http
                .send(
                    "POST",
                    Staff.AGENT_LOGIN,
                    Staff.credentials("reset.me@helpdesk.example"),
                    "Rain-Auth-Delivery" to "body",
                ).statusCode(),
        ).isEqualTo(401)
        assertThat(
            api.http
                .send(
                    "POST",
                    Staff.AGENT_LOGIN,
                    Staff.credentials("RESET.ME@helpdesk.example", NEXT),
                    "Rain-Auth-Delivery" to "body",
                ).statusCode(),
        ).isEqualTo(200)
    }

    @Test
    fun `attaching a permission to a role gives it to every holder, and detaching takes it away`() {
        val role = api.bean(AccessProvisioning::class).ensureRole("attachable", "Attachable", emptySet())
        val subject = Staff.enrol(api, "holder@helpdesk.example", "attachable")

        assertThat(send("POST", "/v1/roles/$role/permissions", """{"permission":"ticket.delete"}""").statusCode()).isEqualTo(204)
        assertThat(holds(subject, "ticket.delete")).isTrue()
        assertThat(send("DELETE", "/v1/roles/$role/permissions/ticket.delete").statusCode()).isEqualTo(204)
        assertThat(holds(subject, "ticket.delete")).isFalse()
    }

    @Test
    fun `an unserved subject type, a malformed id, an undeclared role and an undeclared permission are each refused with their code`() {
        val subject = Staff.enrol(api, "refusals@helpdesk.example")
        val role = api.bean(AccessProvisioning::class).ensureRole("refusing", "Refusing", emptySet())

        listOf(
            send("GET", "/v1/subjects/robot/$subject/roles") to (400 to "unknown_subject_type"),
            send("GET", "/v1/subjects/agent/not-a-uuid/roles") to (400 to "invalid_id"),
            send("POST", "/v1/subjects/agent/$subject/roles", """{"role":"wizard"}""") to (422 to "unknown_role"),
            send("DELETE", "/v1/subjects/agent/$subject/roles/wizard") to (422 to "unknown_role"),
            send(
                "POST",
                "/v1/subjects/agent/$subject/permissions",
                """{"permission":"ticket.teleport"}""",
            ) to (422 to "unknown_permission"),
            send("POST", "/v1/roles/$role/permissions", """{"permission":"ticket.teleport"}""") to (422 to "unknown_permission"),
            send("POST", "/v1/roles/not-a-uuid/permissions", """{"permission":"ticket.read"}""") to (400 to "invalid_id"),
            send("POST", "/v1/roles/${UUID.randomUUID()}/permissions", """{"permission":"ticket.read"}""") to (404 to "not_found"),
            send("POST", "/v1/subjects/agent/$subject/roles", """{"role":""}""") to (422 to "unknown_role"),
        ).forEach { (answer, expected) -> assertThat(answer.statusCode() to answer.code()).describedAs(answer.body()).isEqualTo(expected) }
        assertThat(send("POST", "/v1/subjects/agent/$subject/roles", """{"role":"wizard"}""").json()["errors"][0]["code"].asString())
            .isEqualTo("unknown_role")
        assertThat(
            send("POST", "/v1/roles/$role/permissions", """{"permission":"ticket.teleport"}""").json()["errors"][0]["code"].asString(),
        ).isEqualTo("unknown_permission")
    }

    @Test
    fun `granting to a deactivated agent is refused as unusable_subject, and revoking from it still works`() {
        val subject = Staff.enrol(api, "deactivated-grant@helpdesk.example", "responder")
        api.bean(AgentRegistry::class).setActive(subject, false)

        val granted = send("POST", "/v1/subjects/agent/$subject/permissions", """{"permission":"ticket.read"}""")
        val revoked = send("DELETE", "/v1/subjects/agent/$subject/roles/responder")

        assertThat(granted.statusCode()).isEqualTo(422)
        assertThat(granted.json()["errors"][0]["code"].asString()).isEqualTo("unusable_subject")
        assertThat(revoked.statusCode()).isEqualTo(204)
        assertThat(rolesOf(subject)).isEmpty()
    }

    @Test
    fun `the system role's permissions cannot be detached`() {
        val administrator = api.bean(AccessProvisioning::class).ensureRole(HelpdeskRoles.ADMINISTRATOR, "Administrator", emptySet())

        val refused = send("DELETE", "/v1/roles/$administrator/permissions/ticket.read")

        assertThat(refused.statusCode() to refused.code()).isEqualTo(403 to "system_role")
    }

    @Test
    fun `an agent without the grant permissions is forbidden, and nobody at all is unauthenticated`() {
        val subject = Staff.enrol(api, "target@helpdesk.example")
        val plain = Staff.bearer(api, SharedApi.NOBODY)

        assertThat(send("POST", "/v1/subjects/agent/$subject/roles", """{"role":"responder"}""", plain).statusCode()).isEqualTo(403)
        assertThat(api.http.send("POST", "/v1/subjects/agent/$subject/roles", """{"role":"responder"}""").statusCode()).isEqualTo(401)
    }

    private companion object {
        const val NEXT = "a new and long enough password"
    }
}

/**
 * The start-up contract of the credential and directory surface against the real application: the surface verifies,
 * the catalogue is written, and the two agree — on a second start too. Ported from the Lease `DirectorySurfaceIT`.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectorySurfaceIT {
    private val database = RainPostgres.freshDatabase("directory_surface").also(Stand::migrate)

    private fun declaredCodes(process: SampleProcess): List<String> =
        process.context
            .getBeansOfType(ModuleGrants::class.java)
            .values
            .flatMap { module -> module.permissions.map { it.code } }
            .sorted()

    private fun catalogueRow(code: String): Map<String, Any?> =
        Stand.jdbc(database).queryForMap("SELECT id, name, module FROM rain_access.permissions WHERE code = ?", code)

    private fun roleRow(slug: String): Map<String, Any?> =
        Stand.jdbc(database).queryForMap("SELECT id, is_system, grants_every_permission FROM rain_access.roles WHERE slug = ?", slug)

    @Test
    fun `the declared surface is the mounted surface, each declaration well formed, with the permission each verb spends`() {
        SampleProcess.api(database).use { api ->
            val byKey = Surface.of(api.context).associateBy { it.key }

            assertThat(byKey.values.flatMap { it.problems() }).isEmpty()
            assertThat(
                byKey.filterKeys { it.substringAfter(' ').startsWith("/v1/tickets") }.mapValues { (_, it) ->
                    it.permissions to
                        it.authenticated
                },
            ).isEqualTo(
                mapOf(
                    "GET /v1/tickets" to (emptyList<String>() to true),
                    "GET /v1/tickets/count" to (emptyList<String>() to true),
                    "GET /v1/tickets/{id}" to (emptyList<String>() to true),
                    "POST /v1/tickets" to (listOf("ticket.write") to false),
                    "PATCH /v1/tickets/{id}" to (listOf("ticket.write") to false),
                    "DELETE /v1/tickets/{id}" to (listOf("ticket.delete") to false),
                    "POST /v1/tickets/{id}/close" to (listOf("ticket.write") to false),
                    "POST /v1/tickets/{id}/summary" to (listOf("ticket.write") to false),
                    "GET /v1/tickets/{id}/events" to (emptyList<String>() to true),
                ),
            )
            assertThat(byKey.getValue("GET /v1/subjects/{subjectType}/{subjectId}/roles").permissions).containsExactly("access.grant.read")
            assertThat(
                byKey.getValue("POST /v1/subjects/{subjectType}/{subjectId}/roles").permissions,
            ).containsExactly("access.grant.write")
            assertThat(
                byKey.getValue("PUT /v1/subjects/{subjectType}/{subjectId}/password").permissions,
            ).containsExactly("access.credential.write")
            assertThat(byKey.getValue("POST /v1/roles/{roleId}/permissions").permissions).containsExactly("access.role.write")
            val auth = byKey.values.filter { it.path.startsWith("/v1/auth") }
            assertThat(auth).allMatch({ it.permissions.isEmpty() && it.why.isNotBlank() }, "public or authenticated, with a reason")
            assertThat(
                auth.filter { it.public }.map { it.key },
            ).containsExactlyInAnyOrder("POST /v1/auth/{subjectType}/login", "POST /v1/auth/refresh")
            assertThat(byKey.getValue("GET /live").public && byKey.getValue("GET /ready").public).isTrue()
        }
    }

    @Test
    fun `the catalogue written at start holds every declared code, the system role holds every one, and a second start changes nothing`() {
        val (codes, before) =
            SampleProcess.api(database).use { api ->
                val codes = declaredCodes(api)
                val admin = Staff.enrol(api, "catalogue-admin@helpdesk.example", HelpdeskRoles.ADMINISTRATOR)
                assertThat(
                    api.bean(GrantsLookup::class).heldBy(Agents.ref(admin), codes.toSet()),
                ).containsExactlyInAnyOrderElementsOf(codes)
                codes to (codes.map(::catalogueRow) + roleRow(HelpdeskRoles.ADMINISTRATOR))
            }

        assertThat(codes).contains("ticket.read", "ticket.write", "ticket.delete", "access.role.read", "access.credential.write")
        assertThat(roleRow(HelpdeskRoles.ADMINISTRATOR)).containsEntry("is_system", true).containsEntry("grants_every_permission", true)

        SampleProcess.api(database).close()

        assertThat(codes.map(::catalogueRow) + roleRow(HelpdeskRoles.ADMINISTRATOR)).isEqualTo(before)
    }
}
