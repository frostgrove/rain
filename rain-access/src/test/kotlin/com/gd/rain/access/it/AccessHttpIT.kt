package com.gd.rain.access.it

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.Enrolment
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.support.AccessApplication
import com.gd.rain.access.support.DEFAULT_PASSWORD
import com.gd.rain.access.support.Http
import com.gd.rain.access.support.RecordingAuditRecorder
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
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse

private val JSON: JsonMapper = JsonMapper.builder().build()

private fun HttpResponse<String>.json(): JsonNode = JSON.readTree(body())

private fun HttpResponse<String>.setCookies(): List<String> = headers().allValues("set-cookie")

private fun HttpResponse<String>.cookie(name: String): String =
    setCookies().single { it.startsWith("$name=") }.substringAfter('=').substringBefore(';')

private fun credentials(identifier: String): String = """{"identifier":"$identifier","password":"$DEFAULT_PASSWORD"}"""

/** rain-access served by a real application over HTTP: both deliveries, rotation, enforcement and the credential surface. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessHttpIT {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var http: Http

    @BeforeAll
    fun start() {
        val database = RainPostgres.freshDatabase("access_http")
        context =
            startAccessApplication(
                AccessApplication::class.java,
                WebApplicationType.SERVLET,
                *accessProperties("server.port=0"),
                *database.springProperties().toTypedArray(),
            )
        http = Http(context.port())
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
        delivery: String,
    ): HttpResponse<String> = http.send("POST", "/api/auth/agent/login", credentials(identifier), "Rain-Auth-Delivery" to delivery)

    @Test
    fun `a sign-in delivered in cookies sets both credential cookies, answers no token, and the access cookie authenticates`() {
        val subject = enrolled("cookie@example.test")

        val response = signIn("cookie@example.test", "cookies")

        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        assertThat(response.setCookies()).hasSize(2).allMatch {
            it.contains("HttpOnly") && it.contains("Secure") &&
                it.contains("SameSite=Strict")
        }
        assertThat(response.setCookies().single { it.startsWith("__Host-rain-access=") }).contains("Path=/;")
        assertThat(response.setCookies().single { it.startsWith("__Secure-rain-refresh=") }).contains("Path=/api/auth/refresh;")
        assertThat(response.json()["principal"]["subject"]["id"].asString()).isEqualTo(subject.id.toString())
        assertThat(response.json().has("accessToken") || response.json().has("refreshToken")).isFalse()

        val me = http.send("GET", "/api/auth/me", null, "Cookie" to "__Host-rain-access=${response.cookie("__Host-rain-access")}")
        assertThat(me.statusCode()).describedAs(me.body()).isEqualTo(200)
        assertThat(me.json()["subject"]["id"].asString()).isEqualTo(subject.id.toString())
    }

    @Test
    fun `a refresh cookie rotates, and once every session is closed the next one is refused and cleared`() {
        enrolled("rotate@example.test")
        val signedIn = signIn("rotate@example.test", "cookies")
        val refresh = signedIn.cookie("__Secure-rain-refresh")

        val rotated = http.send("POST", "/api/auth/refresh", null, "Cookie" to "__Secure-rain-refresh=$refresh")
        assertThat(rotated.statusCode()).describedAs(rotated.body()).isEqualTo(200)
        assertThat(rotated.cookie("__Secure-rain-refresh")).isNotEqualTo(refresh)

        val access = "__Host-rain-access=${rotated.cookie("__Host-rain-access")}"
        val closed = http.send("POST", "/api/auth/logout-all", """{"includingCurrent":true}""", "Cookie" to access)
        assertThat(closed.statusCode()).describedAs(closed.body()).isEqualTo(200)
        assertThat(closed.json()["closed"].asLong()).isEqualTo(1)

        val refused =
            http.send(
                "POST",
                "/api/auth/refresh",
                null,
                "Cookie" to "__Secure-rain-refresh=${rotated.cookie("__Secure-rain-refresh")}",
            )
        assertThat(refused.statusCode()).describedAs(refused.body()).isEqualTo(401)
        assertThat(refused.setCookies()).anyMatch { it.startsWith("__Secure-rain-refresh=;") && it.contains("Max-Age=0") }
    }

    @Test
    fun `a sign-in delivered in the body answers tokens that authenticate by header, rotate by body, and list the session`() {
        enrolled("body@example.test")
        val answer = signIn("body@example.test", "body").json()
        val bearer = "Authorization" to "Bearer ${answer["accessToken"].asString()}"

        assertThat(http.send("GET", "/api/auth/me", null, bearer).statusCode()).isEqualTo(200)
        val rotated = http.send("POST", "/api/auth/refresh", """{"refreshToken":"${answer["refreshToken"].asString()}"}""")
        assertThat(rotated.statusCode()).describedAs(rotated.body()).isEqualTo(200)
        assertThat(rotated.json()["refreshToken"].asString()).isNotEqualTo(answer["refreshToken"].asString())
        assertThat(rotated.setCookies()).isEmpty()

        val sessions = http.send("GET", "/api/auth/sessions?limit=10", null, bearer)
        assertThat(sessions.statusCode()).describedAs(sessions.body()).isEqualTo(200)
        assertThat(sessions.json()["items"].single()["current"].asBoolean()).isTrue()
    }

    @Test
    fun `a permissioned route is 401 anonymous, 403 without the permission and 200 once a role grants it`() {
        val subject = enrolled("tickets@example.test")
        val bearer = "Authorization" to "Bearer ${signIn("tickets@example.test", "body").json()["accessToken"].asString()}"

        assertThat(http.send("GET", "/tickets").statusCode()).isEqualTo(401)
        assertThat(http.send("GET", "/tickets", null, bearer).statusCode()).isEqualTo(403)
        context.getBean(AccessProvisioning::class.java).grantRole(subject, "administrator")
        assertThat(http.send("GET", "/tickets", null, bearer).statusCode()).isEqualTo(200)
    }

    @Test
    fun `the credential surface refuses a form, a non-canonical id, an unknown subject type, and sign-up without a registrar`() {
        enrolled("surface@example.test")
        val bearer = "Authorization" to "Bearer ${signIn("surface@example.test", "body").json()["accessToken"].asString()}"

        val form =
            http.send(
                "POST",
                "/api/auth/agent/login",
                "identifier=surface%40example.test",
                "Content-Type" to "application/x-www-form-urlencoded",
            )
        val id = http.send("DELETE", "/api/auth/sessions/1-1-1-1-1", null, bearer)
        val robot = http.send("POST", "/api/auth/robot/login", credentials("robot@example.test"), "Rain-Auth-Delivery" to "body")
        val register = http.send("POST", "/api/auth/agent/register", credentials("new@example.test"), "Rain-Auth-Delivery" to "body")

        assertThat(form.statusCode()).isEqualTo(415)
        assertThat(id.statusCode() to id.json()["code"].asString()).isEqualTo(400 to "invalid_id")
        assertThat(robot.statusCode() to robot.json()["code"].asString()).isEqualTo(400 to "unknown_subject_type")
        assertThat(register.statusCode()).isEqualTo(404)
    }

    @Test
    fun `the probes, functional routes declared public, answer without a credential and with one that authenticates nobody`() {
        val live = http.send("GET", "/live")
        val ready = http.send("GET", "/ready")
        val withStaleToken = http.send("GET", "/ready", null, "Authorization" to "Bearer not-a-token")

        assertThat(live.statusCode()).describedAs(live.body()).isEqualTo(200)
        assertThat(ready.statusCode()).describedAs(ready.body()).isEqualTo(200)
        assertThat(withStaleToken.statusCode()).describedAs(withStaleToken.body()).isEqualTo(200)
    }
}

/** An audit recorder that refuses the signed-in event, standing in for an audit store that is down. */
@Configuration(proxyBeanMethods = false)
class FailingSignInAudit {
    @Bean
    fun auditRecorder(): RecordingAuditRecorder = RecordingAuditRecorder(failing = setOf(AccessAuditTypes.SIGNED_IN.id))
}

/** Gap 17: a sign-in whose evidence cannot be written does not happen; a failed attempt is still recorded on its own. */
@Tag("integration")
class AuditFailureLoginIT {
    @Test
    fun `a sign-in whose audit row cannot be written is 503 audit_unavailable, with no session and no cookie`() {
        val database = RainPostgres.freshDatabase("access_audit_failure")
        SpringApplicationBuilder(AccessApplication::class.java, FailingSignInAudit::class.java)
            .web(WebApplicationType.SERVLET)
            .logStartupInfo(false)
            .properties(*accessProperties("server.port=0"), *database.springProperties().toTypedArray())
            .run()
            .use { context ->
                val audit = context.getBean(RecordingAuditRecorder::class.java)
                val subject = context.directory().add("ada@example.test")
                context.getBean(AccessProvisioning::class.java).enrolPassword(subject, "ada@example.test", DEFAULT_PASSWORD)
                val http = Http(context.port())

                val refused = http.send("POST", "/api/auth/agent/login", credentials("ada@example.test"), "Rain-Auth-Delivery" to "cookies")

                assertThat(refused.statusCode()).describedAs(refused.body()).isEqualTo(503)
                assertThat(refused.json()["code"].asString()).isEqualTo("audit_unavailable")
                assertThat(refused.setCookies()).isEmpty()
                assertThat(
                    JdbcTemplate(database.dataSource()).queryForObject("SELECT count(*) FROM rain_access.sessions", Long::class.java),
                ).isZero()
                assertThat(audit.ofType(AccessAuditTypes.SIGNED_IN.id)).isEmpty()

                val wrong =
                    http.send(
                        "POST",
                        "/api/auth/agent/login",
                        """{"identifier":"ada@example.test","password":"not the password"}""",
                        "Rain-Auth-Delivery" to "cookies",
                    )
                assertThat(wrong.statusCode()).describedAs(wrong.body()).isEqualTo(401)
                assertThat(audit.independent.map { it.type.id }).contains(AccessAuditTypes.SIGN_IN_FAILED.id)
            }
    }
}
