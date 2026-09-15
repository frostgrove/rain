package com.gd.rain.access.it

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.Enrolment
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.support.AccessApplication
import com.gd.rain.access.support.DEFAULT_PASSWORD
import com.gd.rain.access.support.accessProperties
import com.gd.rain.access.support.directory
import com.gd.rain.test.ApplicationHttp
import com.gd.rain.test.RainApplication
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse
import java.util.UUID

private val MAPPER: JsonMapper = JsonMapper.builder().build()

private fun HttpResponse<String>.json(): JsonNode = MAPPER.readTree(body())

/** A refusal as a client reads it: the status, the code, and each violation as its pointer and code. */
private data class Refusal(
    val status: Int,
    val code: String,
    val errors: List<Pair<String, String>> = emptyList(),
)

private fun HttpResponse<String>.refusal(): Refusal {
    val problem = json()
    return Refusal(
        statusCode(),
        problem["code"].asString(),
        problem["errors"]?.values()?.map { it["pointer"].asString() to it["code"].asString() }.orEmpty(),
    )
}

private fun credentials(
    identifier: String,
    password: String,
): String = """{"identifier":"$identifier","password":"$password"}"""

/**
 * The directory surface over HTTP on a real application: roles, their permissions, the catalogue, what a subject holds
 * and an operator setting a password — each route enforced by its declaration, each change recorded against what it
 * changed, each list a keyset page, and every refusal a problem a client can branch on.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectorySurfaceIT {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var http: ApplicationHttp
    private lateinit var jdbc: JdbcTemplate
    private lateinit var operator: String
    private lateinit var stranger: String

    @BeforeAll
    fun start() {
        val database = RainPostgres.freshDatabase("access_directory_surface")
        val application =
            RainApplication.start(
                listOf(AccessApplication::class.java),
                WebApplicationType.SERVLET,
                accessProperties("server.port=0", "rain.access.grants.max-roles-per-subject=4", "rain.access.web.max-bulk-ids=3").toList() +
                    database.springProperties(),
            )
        context = application.context
        http = application.http
        jdbc = JdbcTemplate(database.dataSource())
        context.getBean(AccessProvisioning::class.java).grantRole(enrolled("operator@example.test"), "administrator")
        operator = bearerOf("operator@example.test")
        enrolled("stranger@example.test")
        stranger = bearerOf("stranger@example.test")
    }

    @AfterAll
    fun stop() {
        if (::context.isInitialized) context.close()
    }

    private fun enrolled(identifier: String): SubjectRef {
        val subject = context.directory().add(identifier)
        assertThat(context.getBean(AccessProvisioning::class.java).enrolPassword(subject, identifier, DEFAULT_PASSWORD))
            .isEqualTo(Enrolment.Enrolled)
        return subject
    }

    private fun signIn(
        identifier: String,
        password: String = DEFAULT_PASSWORD,
    ): HttpResponse<String> = http.send("POST", "/api/auth/agent/login", credentials(identifier, password), "Rain-Auth-Delivery" to "body")

    private fun bearerOf(identifier: String): String {
        val answer = signIn(identifier)
        check(answer.statusCode() == 200) { answer.body() }
        return "Bearer ${answer.json()["accessToken"].asString()}"
    }

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        bearer: String? = operator,
    ): HttpResponse<String> =
        if (bearer ==
            null
        ) {
            http.send(method, path, body)
        } else {
            http.send(method, path, body, "Authorization" to bearer)
        }

    private fun createRole(slug: String): UUID {
        val created = call("POST", "/api/roles", """{"slug":"$slug","name":"Role $slug"}""")
        check(created.statusCode() == 201) { created.body() }
        return UUID.fromString(created.json()["id"].asString())
    }

    private fun administrator(): UUID =
        requireNotNull(jdbc.queryForObject("SELECT id FROM rain_access.roles WHERE slug = 'administrator'", UUID::class.java))

    /** The `detail ->> key` of every rain-access audit row of [action] against [resourceId], in the order they were written. */
    private fun recorded(
        action: String,
        resourceId: String,
        key: String,
    ): List<String?> =
        jdbc.queryForList(
            "SELECT detail ->> ? FROM rain_audit.audit_log WHERE module = 'access' AND action = ? AND resource_id = ? ORDER BY occurred_at, id",
            String::class.java,
            key,
            action,
            resourceId,
        )

    private fun subjectPath(subject: SubjectRef): String = "/api/subjects/${subject.type.name}/${subject.id}"

    @Test
    fun `every directory route refuses an anonymous caller with 401 and a caller without its permission with 403`() {
        val role = createRole("guarded")
        val target = subjectPath(enrolled("guarded@example.test"))
        val routes =
            listOf(
                "GET" to "/api/roles",
                "POST" to "/api/roles",
                "GET" to "/api/roles/$role",
                "PATCH" to "/api/roles/$role",
                "DELETE" to "/api/roles/$role",
                "POST" to "/api/roles/bulk-delete",
                "GET" to "/api/roles/$role/permissions",
                "POST" to "/api/roles/$role/permissions",
                "DELETE" to "/api/roles/$role/permissions/ticket.read",
                "GET" to "/api/permissions",
                "GET" to "$target/roles",
                "POST" to "$target/roles",
                "DELETE" to "$target/roles/guarded",
                "GET" to "$target/permissions",
                "POST" to "$target/permissions",
                "DELETE" to "$target/permissions/ticket.read",
                "PUT" to "$target/password",
            )

        routes.forEach { (method, path) ->
            val body = if (method in setOf("POST", "PATCH", "PUT")) "{}" else null
            assertThat(
                call(method, path, body, bearer = null).refusal(),
            ).describedAs("$method $path anonymous").isEqualTo(Refusal(401, "unauthenticated"))
            assertThat(
                call(method, path, body, stranger).refusal(),
            ).describedAs("$method $path stranger").isEqualTo(Refusal(403, "forbidden"))
        }
        assertThat(call("GET", "/api/roles/$role").statusCode()).describedAs("no refused request changed the role").isEqualTo(200)
    }

    @Test
    fun `a permission granted directly opens exactly the routes that declare it, and revoking it closes them again`() {
        val reader = enrolled("reader@example.test")
        val bearer = bearerOf("reader@example.test")

        assertThat(call("POST", "${subjectPath(reader)}/permissions", """{"permission":"access.role.read"}""").statusCode()).isEqualTo(204)

        assertThat(call("GET", "/api/roles", bearer = bearer).statusCode()).isEqualTo(200)
        assertThat(call("GET", "/api/permissions", bearer = bearer).statusCode()).isEqualTo(200)
        assertThat(
            call("POST", "/api/roles", """{"slug":"by-reader","name":"No"}""", bearer).refusal(),
        ).isEqualTo(Refusal(403, "forbidden"))
        assertThat(call("GET", "${subjectPath(reader)}/roles", bearer = bearer).refusal()).isEqualTo(Refusal(403, "forbidden"))
        val held = call("GET", "${subjectPath(reader)}/permissions").json()["items"]
        assertThat(held.values().map { it["code"].asString() }).containsExactly("access.role.read")

        assertThat(call("DELETE", "${subjectPath(reader)}/permissions/access.role.read").statusCode()).isEqualTo(204)

        assertThat(call("GET", "/api/roles", bearer = bearer).refusal()).isEqualTo(Refusal(403, "forbidden"))
        assertThat(recorded("grant-changed", reader.resourceId, "change")).containsExactly("granted", "revoked")
    }

    @Test
    fun `a role is created, read, renamed and deleted, and each change is recorded against the role's id`() {
        val created = call("POST", "/api/roles", """{"slug":"night-shift","name":"Night shift"}""")
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(201)
        val id = created.json()["id"].asString()
        assertThat(listOf("slug", "name", "system", "grantsEveryPermission").map { created.json()[it].asString() })
            .containsExactly("night-shift", "Night shift", "false", "false")

        val read = call("GET", "/api/roles/$id")
        val renamed = call("PATCH", "/api/roles/$id", """{"name":"Night crew"}""")
        val deleted = call("DELETE", "/api/roles/$id")
        val gone = call("GET", "/api/roles/$id")

        assertThat(read.json()["slug"].asString()).isEqualTo("night-shift")
        assertThat(renamed.json()["name"].asString()).isEqualTo("Night crew")
        assertThat(deleted.statusCode()).isEqualTo(204)
        assertThat(gone.refusal()).isEqualTo(Refusal(404, "not_found"))
        assertThat(recorded("role-changed", id, "change")).containsExactly("created", "renamed", "deleted")
    }

    @Test
    fun `roles are listed a keyset page at a time in slug order, each page naming the slug the next one starts after`() {
        listOf("kp-c", "kp-a", "kp-e", "kp-b", "kp-d").forEach(::createRole)

        val seen = mutableListOf<String>()
        var after = "kp"
        var pages = 0
        while (true) {
            val page = call("GET", "/api/roles?limit=2&after=$after").json()
            val slugs = page["items"].values().map { it["slug"].asString() }
            pages++
            assertThat(slugs).hasSizeLessThanOrEqualTo(2)
            seen += slugs.filter { it.startsWith("kp-") }
            val next = page["next"]
            if (next.isNull || slugs.any { !it.startsWith("kp-") }) break
            assertThat(next.asString()).isEqualTo(slugs.last())
            after = next.asString()
            check(pages <= 10) { "paging never ended" }
        }

        assertThat(seen).containsExactly("kp-a", "kp-b", "kp-c", "kp-d", "kp-e")
        assertThat(pages).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `a system role refuses rename, detach and delete with 403 system_role, and a bulk delete naming it deletes nothing`() {
        val administrator = administrator()
        val neighbour = createRole("system-neighbour")

        val refusals =
            listOf(
                call("PATCH", "/api/roles/$administrator", """{"name":"Root"}"""),
                call("DELETE", "/api/roles/$administrator/permissions/ticket.read"),
                call("DELETE", "/api/roles/$administrator"),
                call("POST", "/api/roles/bulk-delete", """{"ids":["$neighbour","$administrator"]}"""),
            )

        assertThat(refusals.map { it.refusal() }).containsOnly(Refusal(403, "system_role"))
        assertThat(call("GET", "/api/roles/$neighbour").statusCode()).isEqualTo(200)
        assertThat(call("GET", "/api/roles/$administrator").json()["name"].asString()).isEqualTo("Administrator")
    }

    @Test
    fun `a role's permissions are attached once, listed a page at a time, detached once, and each change is recorded once`() {
        val role = createRole("ticket-readers")

        repeat(
            2,
        ) { assertThat(call("POST", "/api/roles/$role/permissions", """{"permission":"ticket.read"}""").statusCode()).isEqualTo(204) }
        call("POST", "/api/roles/$role/permissions", """{"permission":"access.role.read"}""")
        val first = call("GET", "/api/roles/$role/permissions?limit=1").json()
        val last = call("GET", "/api/roles/$role/permissions?limit=1&after=${first["next"].asString()}").json()
        val unknown = call("POST", "/api/roles/$role/permissions", """{"permission":"nothing.declared"}""")
        val unknownDetach = call("DELETE", "/api/roles/$role/permissions/nothing.declared")
        repeat(2) { assertThat(call("DELETE", "/api/roles/$role/permissions/ticket.read").statusCode()).isEqualTo(204) }
        val ofNoRole = call("GET", "/api/roles/${UUID.randomUUID()}/permissions")

        assertThat(
            (first["items"].values() + last["items"].values()).map { it["code"].asString() },
        ).containsExactlyInAnyOrder("ticket.read", "access.role.read")
        assertThat(first["next"].asString()).isEqualTo(first["items"].single()["id"].asString())
        assertThat(last["next"].isNull).isTrue()
        assertThat(unknown.refusal()).isEqualTo(Refusal(422, "unknown_permission", listOf("/permission" to "unknown_permission")))
        assertThat(unknownDetach.refusal()).isEqualTo(Refusal(422, "unknown_permission", listOf("/code" to "unknown_permission")))
        assertThat(ofNoRole.refusal()).isEqualTo(Refusal(404, "not_found"))
        assertThat(recorded("role-changed", role.toString(), "change")).containsExactly("created", "attached", "attached", "detached")
        assertThat(
            recorded("role-changed", role.toString(), "permission"),
        ).containsExactly(null, "ticket.read", "access.role.read", "ticket.read")
    }

    @Test
    fun `a bulk delete removes every role it names with its holders, records each, and names no more than max-bulk-ids ids`() {
        val ids = List(3) { createRole("bulk-$it") }
        val holder = enrolled("bulk-holder@example.test")
        listOf("bulk-0", "bulk-1").forEach { call("POST", "${subjectPath(holder)}/roles", """{"role":"$it"}""") }

        val deleted = call("POST", "/api/roles/bulk-delete", """{"ids":["${ids[0]}","${ids[1]}"]}""")
        val tooMany = call("POST", "/api/roles/bulk-delete", """{"ids":["${ids[2]}","${ids[2]}","${ids[2]}","${ids[2]}"]}""")
        val malformedId = call("POST", "/api/roles/bulk-delete", """{"ids":["${ids[2]}","1-1-1-1-1"]}""")
        val unknownId = call("POST", "/api/roles/bulk-delete", """{"ids":["${ids[2]}","${UUID.randomUUID()}"]}""")
        val empty = call("POST", "/api/roles/bulk-delete", """{"ids":[]}""")

        assertThat(deleted.statusCode()).describedAs(deleted.body()).isEqualTo(204)
        ids.take(2).forEach {
            assertThat(call("GET", "/api/roles/$it").statusCode()).isEqualTo(404)
            assertThat(recorded("role-changed", it.toString(), "change")).containsExactly("created", "deleted")
        }
        assertThat(call("GET", "${subjectPath(holder)}/roles").json()["items"]).isEmpty()
        assertThat(tooMany.refusal()).isEqualTo(Refusal(400, "bad_request", listOf("/ids" to "out_of_range")))
        assertThat(malformedId.refusal()).isEqualTo(Refusal(422, "validation_failed", listOf("/ids/1" to "invalid_id")))
        assertThat(unknownId.refusal()).isEqualTo(Refusal(404, "not_found"))
        assertThat(empty.refusal()).isEqualTo(Refusal(422, "validation_failed", listOf("/ids" to "invalid_format")))
        assertThat(call("GET", "/api/roles/${ids[2]}").statusCode()).describedAs("no refused bulk delete deleted anything").isEqualTo(200)
    }

    @Test
    fun `the permission catalogue is listed a keyset page at a time in code order, and a cursor that is not a code is refused`() {
        val codes = mutableListOf<String>()
        var path = "/api/permissions?limit=3"
        do {
            val page = call("GET", path).json()
            val onPage = page["items"].values().map { it["code"].asString() }
            assertThat(onPage).hasSizeLessThanOrEqualTo(3)
            codes += onPage
            val next = page["next"]
            if (!next.isNull) assertThat(next.asString()).isEqualTo(onPage.last())
            path = "/api/permissions?limit=3&after=${if (next.isNull) "" else next.asString()}"
        } while (!next.isNull)

        assertThat(codes).isSorted().doesNotHaveDuplicates().contains("ticket.read", "access.role.read", "access.credential.write")
        assertThat(call("GET", "/api/permissions?after=Not.A.Code").refusal()).isEqualTo(Refusal(400, "invalid_cursor"))
    }

    @Test
    fun `a role granted to a subject is listed with its slug, granted and revoked once each, and recorded against the subject`() {
        val subject = enrolled("granted@example.test")
        val role = createRole("grant-me")

        repeat(2) { assertThat(call("POST", "${subjectPath(subject)}/roles", """{"role":"grant-me"}""").statusCode()).isEqualTo(204) }
        val held = call("GET", "${subjectPath(subject)}/roles").json()
        repeat(2) { assertThat(call("DELETE", "${subjectPath(subject)}/roles/grant-me").statusCode()).isEqualTo(204) }

        assertThat(
            held["items"].values().map { it["slug"].asString() to it["roleId"].asString() },
        ).containsExactly("grant-me" to role.toString())
        assertThat(held["next"].isNull).isTrue()
        assertThat(call("GET", "${subjectPath(subject)}/roles").json()["items"]).isEmpty()
        assertThat(recorded("grant-changed", subject.resourceId, "change")).containsExactly("granted", "revoked")
    }

    @Test
    fun `granting names a declared role, a served type, a canonical id and an active subject, and each refusal points at what was wrong`() {
        val subject = enrolled("refused@example.test")
        createRole("refusable")

        val unknownRole = call("POST", "${subjectPath(subject)}/roles", """{"role":"no-such-role"}""")
        val unknownType = call("POST", "/api/subjects/robot/${subject.id}/roles", """{"role":"refusable"}""")
        val malformedId = call("POST", "/api/subjects/agent/1-1-1-1-1/roles", """{"role":"refusable"}""")
        val unknownRevoke = call("DELETE", "${subjectPath(subject)}/roles/no-such-role")
        val unknownTypeRead = call("GET", "/api/subjects/robot/${subject.id}/permissions")
        context.directory().deactivate(subject.id)
        val inactive = call("POST", "${subjectPath(subject)}/roles", """{"role":"refusable"}""")

        assertThat(unknownRole.refusal()).isEqualTo(Refusal(422, "unknown_role", listOf("/role" to "unknown_role")))
        assertThat(unknownType.refusal()).isEqualTo(Refusal(400, "unknown_subject_type", listOf("/subjectType" to "unknown_subject_type")))
        assertThat(malformedId.refusal()).isEqualTo(Refusal(400, "invalid_id", listOf("/subjectId" to "invalid_id")))
        assertThat(unknownRevoke.refusal()).isEqualTo(Refusal(422, "unknown_role", listOf("/slug" to "unknown_role")))
        assertThat(unknownTypeRead.refusal().code).isEqualTo("unknown_subject_type")
        assertThat(inactive.refusal()).isEqualTo(Refusal(422, "unusable_subject", listOf("/subjectId" to "unusable_subject")))
        assertThat(recorded("grant-changed", subject.resourceId, "change")).isEmpty()
    }

    @Test
    fun `a subject holds at most max-roles-per-subject roles, and granting again one it holds at the ceiling succeeds`() {
        val subject = enrolled("ceiling@example.test")
        val slugs = List(5) { "ceiling-$it" }.onEach(::createRole)

        slugs
            .take(
                4,
            ).forEach { assertThat(call("POST", "${subjectPath(subject)}/roles", """{"role":"$it"}""").statusCode()).isEqualTo(204) }
        val fifth = call("POST", "${subjectPath(subject)}/roles", """{"role":"${slugs[4]}"}""")
        val again = call("POST", "${subjectPath(subject)}/roles", """{"role":"${slugs[1]}"}""")

        assertThat(fifth.refusal()).isEqualTo(Refusal(409, "too_many_roles"))
        assertThat(again.statusCode()).isEqualTo(204)
        assertThat(call("GET", "${subjectPath(subject)}/roles?limit=10").json()["items"]).hasSize(4)
    }

    @Test
    fun `an operator sets a subject's password - the new one signs in, the old one and every session it had are refused`() {
        val target = enrolled("reset@example.test")
        val before = signIn("reset@example.test").json()

        val set = call("PUT", "${subjectPath(target)}/password", """{"password":"a password an operator chose"}""")

        assertThat(set.statusCode()).describedAs(set.body()).isEqualTo(204)
        assertThat(signIn("reset@example.test").refusal()).isEqualTo(Refusal(401, "bad_credentials"))
        assertThat(signIn("reset@example.test", "a password an operator chose").statusCode()).isEqualTo(200)
        val refresh = http.send("POST", "/api/auth/refresh", """{"refreshToken":"${before["refreshToken"].asString()}"}""")
        assertThat(refresh.refusal()).isEqualTo(Refusal(401, "unauthenticated"))
        assertThat(recorded("password-changed", target.resourceId, "by")).containsExactly("operator")
    }

    @Test
    fun `setting a password refuses a weak one, a subject the directory does not hold, and an identifier another subject signs in with`() {
        val holder = enrolled("taken@example.test")
        val newcomer = context.directory().add("taken@example.test")
        val enrolledElsewhere = context.directory().add("taken@example.test")
        context.getBean(AccessProvisioning::class.java).enrolPassword(enrolledElsewhere, "own@example.test", DEFAULT_PASSWORD)
        val chosen = """{"password":"a password an operator chose"}"""

        val weak = call("PUT", "${subjectPath(holder)}/password", """{"password":"short"}""")
        val missing = call("PUT", "/api/subjects/agent/${UUID.randomUUID()}/password", chosen)
        val takenWithoutPassword = call("PUT", "${subjectPath(newcomer)}/password", chosen)
        val takenWithPassword = call("PUT", "${subjectPath(enrolledElsewhere)}/password", chosen)

        assertThat(weak.refusal()).isEqualTo(Refusal(422, "validation_failed", listOf("/password" to "weak_password")))
        assertThat(missing.refusal()).isEqualTo(Refusal(404, "not_found"))
        assertThat(takenWithoutPassword.refusal()).isEqualTo(Refusal(409, "identifier_taken"))
        assertThat(takenWithPassword.refusal()).isEqualTo(Refusal(409, "identifier_taken"))
        assertThat(signIn("taken@example.test").statusCode()).isEqualTo(200)
        assertThat(signIn("own@example.test").statusCode()).isEqualTo(200)
        assertThat(recorded("password-changed", enrolledElsewhere.resourceId, "by")).isEmpty()
    }

    @Test
    fun `a body names only the fields its route reads, each of the type it reads, and nothing refused is created`() {
        val cases =
            listOf(
                """{"slug":"strict-a","name":"Strict","system":true}""" to
                    Refusal(422, "validation_failed", listOf("/system" to "unknown_field")),
                """{"slug":7,"name":"Strict"}""" to Refusal(422, "validation_failed", listOf("/slug" to "invalid_format")),
                """{"slug":"strict-b"}""" to Refusal(422, "validation_failed", listOf("/name" to "required")),
                """[{"slug":"strict-c","name":"Strict"}]""" to Refusal(400, "malformed_body"),
                """{"slug":"Strict D","name":"Strict"}""" to Refusal(422, "invalid_format", listOf("/slug" to "invalid_format")),
                """{"slug":"strict-e","name":"${"x".repeat(257)}"}""" to Refusal(422, "too_long", listOf("/name" to "too_long")),
                """{"slug":"strict-f","name":"  "}""" to Refusal(422, "required", listOf("/name" to "required")),
            )

        cases.forEach { (body, refusal) -> assertThat(call("POST", "/api/roles", body).refusal()).describedAs(body).isEqualTo(refusal) }
        assertThat(http.send("POST", "/api/roles", null, "Authorization" to operator).refusal())
            .isEqualTo(Refusal(422, "validation_failed", listOf("/slug" to "required")))
        assertThat(call("POST", "/api/roles", """{"slug":""").refusal()).isEqualTo(Refusal(400, "bad_request"))
        createRole("strict-g")
        assertThat(call("POST", "/api/roles", """{"slug":"strict-g","name":"Again"}""").refusal())
            .isEqualTo(Refusal(422, "unique", listOf("/slug" to "unique")))
        val renamed = createRole("strict-h")
        assertThat(call("PATCH", "/api/roles/$renamed", """{"name":"Renamed","slug":"strict-x"}""").refusal())
            .isEqualTo(Refusal(422, "validation_failed", listOf("/slug" to "unknown_field")))

        val strict =
            call("GET", "/api/roles?after=strict&limit=20")
                .json()["items"]
                .values()
                .map {
                    it["slug"].asString()
                }.filter { it.startsWith("strict-") }
        assertThat(strict).containsExactly("strict-g", "strict-h")
    }

    @Test
    fun `a list reads only limit and after, a limit within the declared page stated once, and a cursor it can read`() {
        val subject = enrolled("paged@example.test")
        val role = createRole("paged")

        mapOf(
            "limit=0" to Refusal(400, "bad_query", listOf("/limit" to "out_of_range")),
            "limit=201" to Refusal(400, "bad_query", listOf("/limit" to "out_of_range")),
            "limit=ten" to Refusal(400, "bad_query", listOf("/limit" to "invalid_format")),
            "limit=1&limit=2" to Refusal(400, "bad_query"),
        ).forEach { (query, refusal) ->
            assertThat(call("GET", "/api/roles?$query").refusal()).describedAs(query).isEqualTo(refusal)
        }
        assertThat(call("GET", "/api/roles?include=permissions").refusal()).isEqualTo(Refusal(400, "unknown_parameter"))
        assertThat(call("GET", "/api/roles/$role?limit=1").refusal()).isEqualTo(Refusal(400, "unknown_parameter"))
        assertThat(call("GET", "/api/roles?after=Not-A-Slug").refusal()).isEqualTo(Refusal(400, "invalid_cursor"))
        assertThat(call("GET", "${subjectPath(subject)}/roles?after=1-1-1-1-1").refusal()).isEqualTo(Refusal(400, "invalid_cursor"))
        assertThat(call("POST", "/api/roles?dryRun=true", """{"slug":"paged-two","name":"Paged"}""").refusal())
            .isEqualTo(Refusal(400, "unknown_parameter"))
    }
}
