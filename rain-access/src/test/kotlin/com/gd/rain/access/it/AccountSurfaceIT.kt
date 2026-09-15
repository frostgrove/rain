package com.gd.rain.access.it

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.attempt.AttemptKeys
import com.gd.rain.access.support.AccessApplication
import com.gd.rain.access.support.DEFAULT_PASSWORD
import com.gd.rain.access.support.Http
import com.gd.rain.access.support.accessProperties
import com.gd.rain.access.support.directory
import com.gd.rain.access.support.port
import com.gd.rain.access.support.startAccessApplication
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

private val READER: JsonMapper = JsonMapper.builder().build()

private fun HttpResponse<String>.json(): JsonNode = READER.readTree(body())

private const val NEW_PASSWORD = "a completely new password"

/**
 * What a signed-in subject does to its own account over HTTP on a real application: changing its password, closing its
 * sessions one at a time, listing them, and signing out — each recorded, each refusing a body that names another field.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountSurfaceIT {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var http: Http
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun start() {
        val database = RainPostgres.freshDatabase("access_account_surface")
        context =
            startAccessApplication(
                AccessApplication::class.java,
                WebApplicationType.SERVLET,
                *accessProperties("server.port=0"),
                *database.springProperties().toTypedArray(),
            )
        http = Http(context.port())
        jdbc = JdbcTemplate(database.dataSource())
    }

    @AfterAll
    fun stop() {
        if (::context.isInitialized) context.close()
    }

    private fun enrolled(identifier: String): SubjectRef {
        val subject = context.directory().add(identifier)
        context.getBean(AccessProvisioning::class.java).enrolPassword(subject, identifier, DEFAULT_PASSWORD)
        return subject
    }

    private fun signIn(
        identifier: String,
        password: String = DEFAULT_PASSWORD,
        extra: String = "",
    ): HttpResponse<String> =
        http.send(
            "POST",
            "/api/auth/agent/login",
            """{"identifier":"$identifier","password":"$password"$extra}""",
            "Rain-Auth-Delivery" to "body",
        )

    /** A session of [identifier]: its bearer header, its id and its refresh credential. */
    private data class Session(
        val bearer: Pair<String, String>,
        val id: String,
        val refresh: String,
    )

    private fun session(identifier: String): Session {
        val answer = signIn(identifier)
        check(answer.statusCode() == 200) { answer.body() }
        val json = answer.json()
        return Session(
            "Authorization" to "Bearer ${json["accessToken"].asString()}",
            json["principal"]["session"].asString(),
            json["refreshToken"].asString(),
        )
    }

    private fun refresh(session: Session): HttpResponse<String> =
        http.send("POST", "/api/auth/refresh", """{"refreshToken":"${session.refresh}"}""")

    private fun rows(
        action: String,
        resourceId: String,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM rain_audit.audit_log WHERE module = 'access' AND action = ? AND resource_id = ?",
                Long::class.java,
                action,
                resourceId,
            ),
        )

    private fun HttpResponse<String>.problem(): Triple<Int, String, List<String>> {
        val json = json()
        val errors = json["errors"]?.values()?.map { "${it["pointer"].asString()} ${it["code"].asString()}" }.orEmpty()
        return Triple(statusCode(), json["code"].asString(), errors)
    }

    @Test
    fun `a subject changes its own password - the new one signs in, the old one does not, its other sessions close and this one stays`() {
        val subject = enrolled("changer@example.test")
        val current = session("changer@example.test")
        val other = session("changer@example.test")

        val changed =
            http.send("POST", "/api/auth/password", """{"current":"$DEFAULT_PASSWORD","next":"$NEW_PASSWORD"}""", current.bearer)

        assertThat(changed.statusCode()).describedAs(changed.body()).isEqualTo(204)
        assertThat(signIn("changer@example.test").problem()).isEqualTo(Triple(401, "bad_credentials", emptyList<String>()))
        assertThat(signIn("changer@example.test", NEW_PASSWORD).statusCode()).isEqualTo(200)
        assertThat(refresh(other).statusCode()).describedAs("another session of the subject").isEqualTo(401)
        assertThat(refresh(current).statusCode()).describedAs("the session the change was made from").isEqualTo(200)
        assertThat(rows("password-changed", subject.resourceId)).isEqualTo(1)
    }

    @Test
    fun `a wrong current password is 401 and a failed attempt on the account, a weak new one is 422 at next, and another field is 422`() {
        enrolled("careful@example.test")
        val current = session("careful@example.test")

        val wrong = http.send("POST", "/api/auth/password", """{"current":"not the password","next":"$NEW_PASSWORD"}""", current.bearer)
        val weak = http.send("POST", "/api/auth/password", """{"current":"$DEFAULT_PASSWORD","next":"short"}""", current.bearer)
        val confirmed =
            http.send(
                "POST",
                "/api/auth/password",
                """{"current":"$DEFAULT_PASSWORD","next":"$NEW_PASSWORD","confirm":"$NEW_PASSWORD"}""",
                current.bearer,
            )

        assertThat(wrong.problem()).isEqualTo(Triple(401, "bad_credentials", emptyList<String>()))
        assertThat(weak.problem()).isEqualTo(Triple(422, "validation_failed", listOf("/next weak_password")))
        assertThat(confirmed.problem()).isEqualTo(Triple(422, "validation_failed", listOf("/confirm unknown_field")))
        val failures =
            jdbc.queryForObject(
                "SELECT count(*) FROM rain_audit.audit_log " +
                    "WHERE module = 'access' AND action = 'sign-in-failed' AND detail ->> 'identifier_fp' = ?",
                Long::class.java,
                AttemptKeys.fingerprint("careful@example.test"),
            )
        assertThat(failures).isEqualTo(1)
        assertThat(signIn("careful@example.test").statusCode()).describedAs("nothing was changed").isEqualTo(200)
    }

    @Test
    fun `a subject closes another of its sessions by id, another subject's session id closes nothing, and closing twice records once`() {
        enrolled("closer@example.test")
        enrolled("bystander@example.test")
        val current = session("closer@example.test")
        val other = session("closer@example.test")
        val bystander = session("bystander@example.test")

        val closed = http.send("DELETE", "/api/auth/sessions/${other.id}", null, current.bearer)
        val again = http.send("DELETE", "/api/auth/sessions/${other.id}", null, current.bearer)
        val foreign = http.send("DELETE", "/api/auth/sessions/${current.id}", null, bystander.bearer)

        assertThat(listOf(closed, again, foreign).map { it.statusCode() }).containsOnly(204)
        assertThat(refresh(other).statusCode()).isEqualTo(401)
        assertThat(refresh(current).statusCode()).describedAs("another subject closed nothing").isEqualTo(200)
        assertThat(rows("session-revoked", other.id)).describedAs("closing a closed session records nothing more").isEqualTo(1)
        assertThat(rows("session-revoked", current.id)).isZero()
    }

    @Test
    fun `the session list pages newest first with a cursor it can read back, and refuses one it cannot`() {
        enrolled("lister@example.test")
        val sessions = List(3) { session("lister@example.test") }
        val bearer = sessions.last().bearer

        val first = http.send("GET", "/api/auth/sessions?limit=2", null, bearer).json()
        val last = http.send("GET", "/api/auth/sessions?limit=2&after=${first["next"].asString()}", null, bearer).json()

        val listed = (first["items"].values() + last["items"].values()).map { it["id"].asString() }
        assertThat(listed).containsExactlyInAnyOrderElementsOf(sessions.map { it.id }).doesNotHaveDuplicates()
        assertThat(first["items"]).hasSize(2)
        assertThat(last["next"].isNull).isTrue()
        assertThat((first["items"].values() + last["items"].values()).filter { it["current"].asBoolean() }.map { it["id"].asString() })
            .containsExactly(sessions.last().id)
        assertThat(http.send("GET", "/api/auth/sessions?after=yesterday", null, bearer).problem().second).isEqualTo("invalid_cursor")
        assertThat(http.send("GET", "/api/auth/sessions?limit=0", null, bearer).problem().second).isEqualTo("bad_query")
    }

    @Test
    fun `signing out ends the session - its refresh credential no longer rotates, and the sign-out is recorded once`() {
        enrolled("leaver@example.test")
        val current = session("leaver@example.test")

        val signedOut = http.send("POST", "/api/auth/logout", null, current.bearer)

        assertThat(signedOut.statusCode()).isEqualTo(204)
        assertThat(refresh(current).statusCode()).isEqualTo(401)
        assertThat(rows("signed-out", current.id)).isEqualTo(1)
    }

    @Test
    fun `sign-in and sign-out everywhere read only the fields they name, each of the type they read`() {
        enrolled("strict@example.test")
        val current = session("strict@example.test")

        val remembered = signIn("strict@example.test", extra = ""","remember":true""")
        val notBoolean = http.send("POST", "/api/auth/logout-all", """{"includingCurrent":"yes"}""", current.bearer)
        val nothing = http.send("POST", "/api/auth/logout-all", "{}", current.bearer)

        assertThat(remembered.problem()).isEqualTo(Triple(422, "validation_failed", listOf("/remember unknown_field")))
        assertThat(notBoolean.problem()).isEqualTo(Triple(422, "validation_failed", listOf("/includingCurrent invalid_format")))
        assertThat(nothing.problem()).isEqualTo(Triple(422, "validation_failed", listOf("/includingCurrent required")))
        assertThat(refresh(current).statusCode()).describedAs("no refused sign-out closed anything").isEqualTo(200)
    }
}
