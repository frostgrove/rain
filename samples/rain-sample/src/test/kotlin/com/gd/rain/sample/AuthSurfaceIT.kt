package com.gd.rain.sample

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gd.rain.sample.agent.AgentRegistry
import com.gd.rain.sample.stand.Awaits
import com.gd.rain.sample.stand.SampleProcess
import com.gd.rain.sample.stand.Staff
import com.gd.rain.sample.stand.Stand
import com.gd.rain.sample.stand.code
import com.gd.rain.sample.stand.cookie
import com.gd.rain.sample.stand.json
import com.gd.rain.sample.stand.setCookies
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import com.gd.rain.web.filter.RequestLogFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.net.InetAddress
import java.net.Socket
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The process's clock, moved by the test: rotation grace, token lifetimes and cookie ages are read from it. */
class MovableClock {
    @Bean
    @Primary
    fun clockMovedByTheTest(): Clock = clock

    companion object {
        val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
    }
}

/**
 * The credential routes under `/v1/auth`, end to end on a real port against PostgreSQL and Redis: both deliveries, the
 * cookies' names and protections, rotation with its grace and its replay, sign-out, sessions, password changes, lockout,
 * and the refusals of a request that disagrees with itself about its credential.
 *
 * Ported from the Lease `AuthSurfaceIT`. The helpdesk mounts no registrar, so sign-up is absent rather than exercised;
 * the principal carries no roles or permissions, which rain-access answers per decision instead.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSurfaceIT {
    private lateinit var database: RainDatabase
    private lateinit var api: SampleProcess
    private val clock = MovableClock.clock

    @BeforeAll
    fun start() {
        database = RainPostgres.freshDatabase("auth_surface")
        Stand.migrate(database)
        api = SampleProcess.api(database, "rain.access.attempts.per-identifier=3", sources = listOf(MovableClock::class.java))
    }

    @AfterAll
    fun stop() {
        if (::api.isInitialized) api.close()
    }

    private fun agent(name: String): Pair<UUID, String> {
        val identifier = "$name@helpdesk.example"
        return Staff.enrol(api, identifier) to identifier
    }

    private fun signIn(
        identifier: String,
        delivery: String,
        password: String = Stand.PASSWORD,
    ): HttpResponse<String> =
        api.http.send(
            "POST",
            Staff.AGENT_LOGIN,
            Staff.credentials(identifier, password),
            "Rain-Auth-Delivery" to delivery,
        )

    private fun cookies(signedIn: HttpResponse<String>): String =
        signedIn
            .setCookies()
            .map { it.substringBefore(';') }
            .filterNot { it.endsWith("=") }
            .joinToString("; ")

    private fun accessCookie(signedIn: HttpResponse<String>): Pair<String, String> =
        "Cookie" to "__Host-rain-access=${signedIn.cookie("__Host-rain-access")}"

    private fun refresh(credential: String): HttpResponse<String> =
        api.http.send(
            "POST",
            "/v1/auth/refresh",
            null,
            "Cookie" to "__Secure-rain-refresh=$credential",
        )

    private fun me(vararg headers: Pair<String, String>): Int = api.http.get("/v1/auth/me", *headers).statusCode()

    @Test
    fun `a sign-in delivered in cookies answers the principal alone and sets both cookies with their names, paths and protections`() {
        val (id, identifier) = agent("cookies")

        val signedIn = signIn(identifier, "cookies")

        assertThat(signedIn.statusCode()).describedAs(signedIn.body()).isEqualTo(200)
        assertThat(signedIn.json().propertyNames()).containsExactly("principal")
        assertThat(signedIn.json()["principal"]["subject"].toString()).isEqualTo("""{"type":"agent","id":"$id"}""")
        assertThat(signedIn.json()["principal"]["profile"]["identifier"].asString()).isEqualTo(identifier)
        val access = signedIn.setCookies().single { it.startsWith("__Host-rain-access=") }
        val refresh = signedIn.setCookies().single { it.startsWith("__Secure-rain-refresh=") }
        assertThat(access).contains("Path=/;", "Max-Age=300;", "Secure", "HttpOnly", "SameSite=Strict")
        assertThat(refresh).contains("Path=/v1/auth/refresh;", "Max-Age=2592000;", "Secure", "HttpOnly", "SameSite=Strict")
    }

    @Test
    fun `a sign-in delivered in the body answers both credentials and their expiries, and sets no cookie`() {
        val (_, identifier) = agent("body")

        val signedIn = signIn(identifier, "body")

        assertThat(
            signedIn.json().propertyNames(),
        ).containsExactlyInAnyOrder("principal", "accessToken", "accessExpiresAt", "refreshToken", "refreshExpiresAt")
        assertThat(Instant.parse(signedIn.json()["accessExpiresAt"].asString())).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)))
        assertThat(signedIn.setCookies()).isEmpty()
        assertThat(me("Authorization" to "Bearer ${signedIn.json()["accessToken"].asString()}")).isEqualTo(200)
    }

    @Test
    fun `a sign-in that states no delivery, or one that is not a delivery, is refused with invalid_delivery`() {
        val (_, identifier) = agent("delivery")

        val unstated = api.http.send("POST", Staff.AGENT_LOGIN, Staff.credentials(identifier))
        val unknown = signIn(identifier, "smoke-signal")

        assertThat(unstated.statusCode() to unstated.code()).isEqualTo(400 to "invalid_delivery")
        assertThat(unknown.statusCode() to unknown.code()).isEqualTo(400 to "invalid_delivery")
    }

    @Test
    fun `a wrong password is 401 bad_credentials, and an identifier nobody holds answers the same bytes`() {
        val (_, identifier) = agent("wrong")

        val wrong = signIn(identifier, "body", "not the password at all")
        val nobody = signIn("nobody-at-all@helpdesk.example", "body")

        assertThat(wrong.statusCode() to wrong.code()).isEqualTo(401 to "bad_credentials")
        assertThat(nobody.body()).isEqualTo(wrong.body())
    }

    @Test
    fun `the access cookie alone authenticates, and nothing at all does not`() {
        val (_, identifier) = agent("cookie-alone")

        val signedIn = signIn(identifier, "cookies")

        assertThat(me(accessCookie(signedIn))).isEqualTo(200)
        assertThat(api.http.get("/v1/auth/me").let { it.statusCode() to it.code() }).isEqualTo(401 to "unauthenticated")
    }

    @Test
    fun `an agent deactivated after signing in stops being authenticated and signs in no more, and its row stays`() {
        val (id, identifier) = agent("deactivated")
        val signedIn = signIn(identifier, "cookies")
        assertThat(me(accessCookie(signedIn))).isEqualTo(200)

        api.bean(AgentRegistry::class).setActive(id, false)

        assertThat(me(accessCookie(signedIn))).isEqualTo(401)
        assertThat(signIn(identifier, "body").let { it.statusCode() to it.code() }).isEqualTo(401 to "bad_credentials")
        assertThat(api.bean(AgentRegistry::class).find(id)?.active).isFalse()
    }

    @Test
    fun `a token for another subject type, or signed with another key, is refused`() {
        val (_, identifier) = agent("forged")
        val token = signIn(identifier, "body").json()["accessToken"].asString()
        val key = Base64.getDecoder().decode(Stand.SIGNING_KEY.removePrefix("base64:"))

        val robot = resigned(token, key) { payload -> payload.replace("\"agent\"", "\"robot\"") }
        val foreign = resigned(token, ByteArray(32) { 1 }) { it }

        assertThat(me("Authorization" to "Bearer $token")).isEqualTo(200)
        assertThat(robot).describedAs("the token names its subject type as a claim").isNotEqualTo(token)
        assertThat(me("Authorization" to "Bearer $robot")).isEqualTo(401)
        assertThat(me("Authorization" to "Bearer $foreign")).isEqualTo(401)
    }

    @Test
    fun `a request that presents its credential twice is refused, whichever way it repeats it`() {
        val (_, identifier) = agent("twice")
        val signedIn = signIn(identifier, "cookies")
        val token = signedIn.cookie("__Host-rain-access")

        val both = api.http.get("/v1/auth/me", accessCookie(signedIn), "Authorization" to "Bearer $token")
        val headers = raw("Authorization: Bearer $token", "Authorization: Bearer $token")
        val cookieHeaders = raw("Cookie: __Host-rain-access=$token", "Cookie: __Host-rain-access=$token")

        assertThat(both.statusCode() to both.code()).isEqualTo(401 to "unauthenticated")
        assertThat(headers).startsWith("HTTP/1.1 401")
        assertThat(cookieHeaders).startsWith("HTTP/1.1 401")
    }

    /** One request written on a socket, because every HTTP client folds a repeated header into one. */
    private fun raw(vararg headers: String): String =
        Socket(InetAddress.getLoopbackAddress(), api.port).use { socket ->
            val request =
                (
                    listOf(
                        "GET /v1/auth/me HTTP/1.1",
                        "Host: 127.0.0.1",
                    ) + headers + listOf("Connection: close")
                ).joinToString("\r\n")
            socket.getOutputStream().write("$request\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            String(socket.inputStream.readAllBytes(), Charsets.UTF_8)
        }

    @Test
    fun `the request log names the agent of an authenticated call and no principal for an anonymous one`() {
        val (id, identifier) = agent("logged")
        val bearer = "Authorization" to "Bearer ${signIn(identifier, "body").json()["accessToken"].asString()}"
        val logger = LoggerFactory.getLogger(RequestLogFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            api.http.get("/v1/auth/me", bearer, "X-Request-ID" to "logged-authenticated")
            api.http.get("/v1/auth/me", "X-Request-ID" to "logged-anonymous")

            fun line(requestId: String): Map<String, Any?>? =
                appender.list
                    .map { event -> event.keyValuePairs.orEmpty().associate { it.key to it.value } }
                    .firstOrNull { it["request_id"] == requestId }

            Awaits.until("both request lines") { line("logged-authenticated") != null && line("logged-anonymous") != null }
            assertThat(line("logged-authenticated")?.get("principal")).isEqualTo("agent:$id")
            assertThat(line("logged-anonymous")).doesNotContainKey("principal")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `two refreshes of one credential at once both rotate, inside the grace`() {
        val (_, identifier) = agent("concurrent")
        val credential = signIn(identifier, "cookies").cookie("__Secure-rain-refresh")
        val barrier = CyclicBarrier(2)
        val pool = Executors.newVirtualThreadPerTaskExecutor()

        val answers =
            pool.use { executor ->
                List(2) {
                    executor.submit<HttpResponse<String>> {
                        barrier.await(Awaits.BOUND_SECONDS, TimeUnit.SECONDS)
                        refresh(credential)
                    }
                }.map { it.get(Awaits.BOUND_SECONDS, TimeUnit.SECONDS) }
            }

        assertThat(answers.map { it.statusCode() }).describedAs(answers.joinToString { it.body() }).containsExactly(200, 200)
        assertThat(answers.map { it.cookie("__Secure-rain-refresh") }).doesNotContain(credential)
        assertThat(answers.map { me("Cookie" to "__Host-rain-access=${it.cookie("__Host-rain-access")}") }).containsExactly(200, 200)
    }

    @Test
    fun `a credential spent two rotations ago, past the grace, closes the session and clears the refresh cookie`() {
        val (_, identifier) = agent("replayed")
        val original = signIn(identifier, "cookies").cookie("__Secure-rain-refresh")
        val first = refresh(original)
        clock.advance(Duration.ofSeconds(11))
        val second = refresh(first.cookie("__Secure-rain-refresh"))
        assertThat(second.statusCode()).describedAs(second.body()).isEqualTo(200)

        val replayed = refresh(original)

        assertThat(replayed.statusCode() to replayed.code()).isEqualTo(401 to "unauthenticated")
        assertThat(replayed.setCookies()).anyMatch { it.startsWith("__Secure-rain-refresh=;") && it.contains("Max-Age=0") }
        assertThat(me("Cookie" to "__Host-rain-access=${second.cookie("__Host-rain-access")}"))
            .describedAs("the replay closed the session, and the revocation list stops its token at once")
            .isEqualTo(401)
    }

    @Test
    fun `a refresh reads the refresh cookie when the body is empty`() {
        val (_, identifier) = agent("empty-body")

        val rotated = refresh(signIn(identifier, "cookies").cookie("__Secure-rain-refresh"))

        assertThat(rotated.statusCode()).isEqualTo(200)
        assertThat(rotated.json().propertyNames()).containsExactly("principal")
    }

    @Test
    fun `signing out closes the session, clears both cookies and stops its access token at once`() {
        val (_, identifier) = agent("signed-out")
        val signedIn = signIn(identifier, "cookies")

        val out = api.http.send("POST", "/v1/auth/logout", null, accessCookie(signedIn))

        assertThat(out.statusCode()).isEqualTo(204)
        assertThat(out.setCookies()).hasSize(2).allMatch { it.contains("=;") && it.contains("Max-Age=0") }
        assertThat(me(accessCookie(signedIn))).isEqualTo(401)
    }

    @Test
    fun `the session list names the current session, and closing another stops that one only`() {
        val (_, identifier) = agent("sessions")
        val first = signIn(identifier, "body").json()
        val second = signIn(identifier, "body").json()
        val secondBearer = "Authorization" to "Bearer ${second["accessToken"].asString()}"

        val listed =
            api.http
                .get("/v1/auth/sessions", secondBearer)
                .json()["items"]
                .values()
                .toList()
        val other = first["principal"]["session"].asString()
        val closed = api.http.send("DELETE", "/v1/auth/sessions/$other", null, secondBearer)

        assertThat(listed.map { it["id"].asString() to it["current"].asBoolean() })
            .containsExactlyInAnyOrder(other to false, second["principal"]["session"].asString() to true)
        assertThat(closed.statusCode()).isEqualTo(204)
        assertThat(me("Authorization" to "Bearer ${first["accessToken"].asString()}")).isEqualTo(401)
        assertThat(me(secondBearer)).isEqualTo(200)
    }

    @Test
    fun `closing a session that is not the caller's is 204 and changes nothing, and an id that is not canonical is 400 invalid_id`() {
        val (_, identifier) = agent("foreign-session")
        val bearer = "Authorization" to "Bearer ${signIn(identifier, "body").json()["accessToken"].asString()}"

        val foreign = api.http.send("DELETE", "/v1/auth/sessions/${UUID.randomUUID()}", null, bearer)
        val malformed = api.http.send("DELETE", "/v1/auth/sessions/not-a-uuid", null, bearer)

        assertThat(foreign.statusCode()).isEqualTo(204)
        assertThat(me(bearer)).isEqualTo(200)
        assertThat(malformed.statusCode() to malformed.code()).isEqualTo(400 to "invalid_id")
    }

    @Test
    fun `signing out everywhere keeps the caller unless it includes the current session`() {
        val (_, identifier) = agent("everywhere")
        val keeper = signIn(identifier, "body").json()
        repeat(2) { signIn(identifier, "body") }
        val bearer = "Authorization" to "Bearer ${keeper["accessToken"].asString()}"

        val others = api.http.send("POST", "/v1/auth/logout-all", """{"includingCurrent":false}""", bearer)
        assertThat(others.json()["closed"].asLong()).isEqualTo(2)
        assertThat(me(bearer)).isEqualTo(200)

        val all = api.http.send("POST", "/v1/auth/logout-all", """{"includingCurrent":true}""", bearer)
        assertThat(all.json()["closed"].asLong()).isEqualTo(1)
        assertThat(me(bearer)).isEqualTo(401)
    }

    @Test
    fun `changing the password closes the other sessions, keeps this one, and only the new password signs in`() {
        val (_, identifier) = agent("rotator")
        val kept = "Authorization" to "Bearer ${signIn(identifier, "body").json()["accessToken"].asString()}"
        val doomed = "Authorization" to "Bearer ${signIn(identifier, "body").json()["accessToken"].asString()}"

        val changed = api.http.send("POST", "/v1/auth/password", """{"current":"${Stand.PASSWORD}","next":"$NEXT_PASSWORD"}""", kept)

        assertThat(changed.statusCode()).describedAs(changed.body()).isEqualTo(204)
        assertThat(me(doomed)).isEqualTo(401)
        assertThat(me(kept)).isEqualTo(200)
        assertThat(signIn(identifier, "body").statusCode()).isEqualTo(401)
        assertThat(signIn(identifier, "body", NEXT_PASSWORD).statusCode()).isEqualTo(200)
    }

    @Test
    fun `a wrong current password is 401 bad_credentials and a short new one is 422 weak_password`() {
        val (_, identifier) = agent("weakling")
        val bearer = "Authorization" to "Bearer ${signIn(identifier, "body").json()["accessToken"].asString()}"

        val wrong = api.http.send("POST", "/v1/auth/password", """{"current":"not the password","next":"$NEXT_PASSWORD"}""", bearer)
        val weak = api.http.send("POST", "/v1/auth/password", """{"current":"${Stand.PASSWORD}","next":"short"}""", bearer)

        assertThat(wrong.statusCode() to wrong.code()).isEqualTo(401 to "bad_credentials")
        assertThat(weak.statusCode()).isEqualTo(422)
        assertThat(weak.json()["errors"][0]["code"].asString()).isEqualTo("weak_password")
    }

    @Test
    fun `failed sign-ins lock the identifier out, and the lockout refuses even the right password with a Retry-After`() {
        val (_, identifier) = agent("locked")
        repeat(3) { assertThat(signIn(identifier, "body", "not the password at all").statusCode()).isEqualTo(401) }

        val locked = signIn(identifier, "body")

        assertThat(locked.statusCode() to locked.code()).isEqualTo(429 to "too_many_attempts")
        assertThat(locked.headers().firstValue("Retry-After")).isPresent()
    }

    @Test
    fun `an agent does not sign up, the helpdesk mounts no registrar, so there is no such route`() {
        val answer =
            api.http.send(
                "POST",
                "/v1/auth/agent/register",
                Staff.credentials("new@helpdesk.example"),
                "Rain-Auth-Delivery" to "body",
            )

        assertThat(answer.statusCode()).isEqualTo(404)
    }

    /** [token] with its payload changed by [change] and signed again with HMAC-SHA256 under [key]. */
    private fun resigned(
        token: String,
        key: ByteArray,
        change: (String) -> String,
    ): String {
        val (header, payload, _) = token.split('.')
        val url = Base64.getUrlEncoder().withoutPadding()
        val changed = url.encodeToString(change(String(Base64.getUrlDecoder().decode(payload))).toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return "$header.$changed.${url.encodeToString(mac.doFinal("$header.$changed".toByteArray()))}"
    }

    private companion object {
        const val NEXT_PASSWORD = "a new and long enough password"
    }
}
