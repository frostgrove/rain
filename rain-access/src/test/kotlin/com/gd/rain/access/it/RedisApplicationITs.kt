package com.gd.rain.access.it

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.autoconfigure.RainAccessAutoConfiguration
import com.gd.rain.access.internal.revocation.RedisRevocationList
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.revocation.RevocationReplayTask
import com.gd.rain.access.internal.web.CredentialCookies
import com.gd.rain.access.support.AccessApplication
import com.gd.rain.access.support.DEFAULT_PASSWORD
import com.gd.rain.access.support.RedisFactories
import com.gd.rain.access.support.START
import com.gd.rain.access.support.accessProperties
import com.gd.rain.access.support.directory
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.observability.health.CheckState
import com.gd.rain.observability.health.HealthRegistry
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainApplication
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import com.gd.rain.test.RainRedis
import com.gd.rain.test.RedisPolicy
import com.gd.rain.test.RedisServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private val PARSER: JsonMapper = JsonMapper.builder().build()

private fun HttpResponse<String>.tree(): JsonNode = PARSER.readTree(body())

private const val REVOCATION_PREFIX = "it:revoked:"
private const val ATTEMPT_PREFIX = "it:attempts:"

/** rain-access with both stores on [redis], both of their health checks required, and every command bounded. */
private fun redisStoreProperties(
    redis: RedisServer,
    vararg overrides: String,
): Array<String> =
    accessProperties(
        "server.port=0",
        "spring.data.redis.host=${redis.host}",
        "spring.data.redis.port=${redis.port}",
        "spring.data.redis.timeout=1s",
        "spring.data.redis.connect-timeout=1s",
        "rain.access.revocation.store=redis",
        "rain.access.revocation.redis.key-prefix=$REVOCATION_PREFIX",
        "rain.access.revocation.redis.replay.interval=1m",
        "rain.access.attempts.store=redis",
        "rain.access.attempts.redis.key-prefix=$ATTEMPT_PREFIX",
        "rain.access.attempts.per-identifier=3",
        "rain.health.checks[access.revocation]=required",
        "rain.health.checks[access.attempts]=required",
        *overrides,
    ).filterNot { it.startsWith("rain.access.attempts.memory.") }.toTypedArray()

/** Starts [sources] reading [clock] as their only clock, so a test moves time for the application by hand. */
private fun startWithClock(
    clock: MutableClock,
    web: WebApplicationType,
    database: RainDatabase,
    properties: Array<String>,
    vararg sources: Class<*>,
): RainApplication =
    RainApplication.start(
        listOf(AccessApplication::class.java, *sources),
        web,
        properties.toList() + database.springProperties(),
        singletons = mapOf("clock" to clock),
    )

private fun templateOf(redis: RedisServer): Pair<LettuceConnectionFactory, StringRedisTemplate> {
    val factory = RedisFactories.of(redis)
    return factory to StringRedisTemplate(factory)
}

/** What a started application needs of a test: enrolment, sign-in in either delivery, and requests with a bearer. */
private class Client(
    application: RainApplication,
) {
    private val context = application.context
    val http = application.http

    fun enrolled(identifier: String): SubjectRef {
        val subject = context.directory().add(identifier)
        context.getBean(AccessProvisioning::class.java).enrolPassword(subject, identifier, DEFAULT_PASSWORD)
        return subject
    }

    fun signIn(
        identifier: String,
        password: String = DEFAULT_PASSWORD,
        delivery: String = "body",
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> =
        http.send(
            "POST",
            "/api/auth/agent/login",
            """{"identifier":"$identifier","password":"$password"}""",
            "Rain-Auth-Delivery" to delivery,
            *headers,
        )

    /** A body-delivered session: its bearer header and its id. */
    fun session(identifier: String): Pair<Pair<String, String>, String> {
        val answer = signIn(identifier)
        check(answer.statusCode() == 200) { answer.body() }
        return ("Authorization" to "Bearer ${answer.tree()["accessToken"].asString()}") to answer.tree()["principal"]["session"].asString()
    }

    fun me(vararg headers: Pair<String, String>): HttpResponse<String> = http.send("GET", "/api/auth/me", null, *headers)
}

/**
 * A real application with its revocation list and attempt counters on Redis: a closed session stops answering on the next
 * request, failures are counted where every replica sees them, readiness reads both servers, and a worker replays onto the
 * list what the database recorded.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStoresApplicationIT {
    private val redis = RainRedis.start(RedisPolicy.RETAINING)
    private val clock = MutableClock(START)
    private lateinit var database: RainDatabase
    private lateinit var application: RainApplication
    private lateinit var client: Client
    private lateinit var factory: LettuceConnectionFactory
    private lateinit var keys: StringRedisTemplate

    @BeforeAll
    fun start() {
        database = RainPostgres.freshDatabase("access_redis_stores")
        application = startWithClock(clock, WebApplicationType.SERVLET, database, redisStoreProperties(redis))
        client = Client(application)
        templateOf(redis).let { (opened, template) ->
            factory = opened
            keys = template
        }
    }

    @AfterAll
    fun stop() {
        if (::application.isInitialized) application.close()
        if (::factory.isInitialized) factory.destroy()
        redis.close()
    }

    /** Past the instant of every cutoff an earlier test wrote, and past readiness's freshness. */
    @BeforeEach
    fun moveOn() {
        clock.advance(Duration.ofSeconds(2))
    }

    @Test
    fun `a session closed by signing out is refused on the next request, and closing all but the current session refuses the rest`() {
        val subject = client.enrolled("revoked@example.test")
        val (firstBearer, first) = client.session("revoked@example.test")
        val (secondBearer, _) = client.session("revoked@example.test")
        val (thirdBearer, _) = client.session("revoked@example.test")

        assertThat(client.http.send("POST", "/api/auth/logout", null, firstBearer).statusCode()).isEqualTo(204)
        val afterSignOut = client.me(firstBearer)
        val keeping = client.http.send("POST", "/api/auth/logout-all", """{"includingCurrent":false}""", secondBearer)

        assertThat(afterSignOut.statusCode()).isEqualTo(401)
        assertThat(afterSignOut.tree()["detail"].asString()).isEqualTo("the session has been closed")
        assertThat(keeping.tree()["closed"].asLong()).isEqualTo(1)
        assertThat(client.me(thirdBearer).statusCode()).isEqualTo(401)
        assertThat(client.me(secondBearer).statusCode()).isEqualTo(200)
        val list = application.context.getBean(RevocationList::class.java) as RedisRevocationList
        assertThat(
            keys.getExpire(list.sessionKey(UUID.fromString(first)), TimeUnit.MILLISECONDS),
        ).isBetween(1L, Duration.ofMinutes(5).toMillis())
        assertThat(keys.hasKey(list.cutoffKey(subject))).isTrue()
    }

    @Test
    fun `failed sign-ins are counted in Redis under the attempt prefix, never by identifier, and the failure at the ceiling locks it`() {
        client.enrolled("counted@example.test")

        val failures = List(3) { client.signIn("counted@example.test", "not the password $it") }
        val locked = client.signIn("counted@example.test")

        assertThat(failures.map { it.statusCode() }).containsOnly(401)
        assertThat(locked.statusCode()).isEqualTo(429)
        assertThat(locked.tree()["code"].asString()).isEqualTo("too_many_attempts")
        assertThat(
            locked
                .headers()
                .firstValue("Retry-After")
                .orElseThrow()
                .toLong(),
        ).isBetween(1L, 900L)
        assertThat(keys.keys("$ATTEMPT_PREFIX*")).isNotEmpty().noneMatch { it.contains("counted@example.test") }
        val lockouts =
            JdbcTemplate(database.dataSource()).queryForObject(
                "SELECT count(*) FROM rain_audit.audit_log " +
                    "WHERE module = 'access' AND action = 'lockout-opened' AND detail ->> 'key_kind' = 'identifier'",
                Long::class.java,
            )
        assertThat(lockouts).isEqualTo(1)
    }

    @Test
    fun `a browser still holding the cookie of a session closed elsewhere signs in again, and its new cookie authenticates`() {
        client.enrolled("returning@example.test")
        val stale = client.signIn("returning@example.test", delivery = "cookies")
        val staleCookie = "${CredentialCookies.ACCESS}=${cookieValue(stale, CredentialCookies.ACCESS)}"
        val (elsewhere, _) = client.session("returning@example.test")

        client.http.send("POST", "/api/auth/logout-all", """{"includingCurrent":true}""", elsewhere)
        val refused = client.me("Cookie" to staleCookie)
        clock.advance(Duration.ofSeconds(1))
        val again = client.signIn("returning@example.test", delivery = "cookies", headers = arrayOf("Cookie" to staleCookie))

        assertThat(refused.statusCode()).isEqualTo(401)
        assertThat(again.statusCode()).describedAs(again.body()).isEqualTo(200)
        val fresh = "${CredentialCookies.ACCESS}=${cookieValue(again, CredentialCookies.ACCESS)}"
        assertThat(client.me("Cookie" to fresh).statusCode()).isEqualTo(200)
    }

    @Test
    fun `readiness is ready while the revocation list and the attempt counters answer`() {
        val ready = client.http.send("GET", "/ready")

        assertThat(ready.statusCode()).describedAs(ready.body()).isEqualTo(200)
        assertThat(ready.tree()["status"].asString()).isEqualTo("ready")
        val checks =
            application.context
                .getBean(HealthRegistry::class.java)
                .inspect()
                .checks
                .associateBy { it.name }
        listOf("access.revocation", "access.attempts").forEach {
            assertThat(checks.getValue(it).state).describedAs(it).isEqualTo(CheckState.PASSING)
        }
    }

    @Test
    fun `a worker replays onto the list a session the database closed but never announced, and the session stops answering`() {
        client.enrolled("replayed@example.test")
        val (bearer, session) = client.session("replayed@example.test")
        JdbcTemplate(database.dataSource()).update(
            "UPDATE rain_access.sessions SET revoked_at = ?, revoked_reason = 'signed-out' WHERE id = ?",
            Timestamp.from(clock.instant()),
            UUID.fromString(session),
        )
        assertThat(client.me(bearer).statusCode()).describedAs("the list was never told").isEqualTo(200)

        val worker =
            startWithClock(
                clock,
                WebApplicationType.NONE,
                database,
                redisStoreProperties(
                    redis,
                    "rain.runtime.roles=worker",
                    "rain.jobs.required-recurring=access.session-retention,access.revocation-replay",
                    "rain.health.checks.jobs=informational",
                    "spring.datasource.hikari.maximum-pool-size=20",
                ),
            )
        val report =
            worker.use {
                it.context
                    .getBeansOfType(RecurringWork::class.java)
                    .values
                    .filterIsInstance<RevocationReplayTask>()
                    .single()
                    .replayOnce()
            }

        assertThat(report.sessionsAnnounced).isGreaterThanOrEqualTo(1)
        assertThat(client.me(bearer).statusCode()).isEqualTo(401)
    }

    private fun cookieValue(
        response: HttpResponse<String>,
        name: String,
    ): String =
        response
            .headers()
            .allValues("set-cookie")
            .single { it.startsWith("$name=") }
            .substringAfter('=')
            .substringBefore(';')
}

/** When Redis stops answering, nothing that depends on it passes: readiness names both stores and each request is a 503 of its own. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisOutageIT {
    private val redis = RainRedis.start(RedisPolicy.RETAINING)
    private val clock = MutableClock(START)
    private lateinit var application: RainApplication

    @BeforeAll
    fun start() {
        application =
            startWithClock(
                clock,
                WebApplicationType.SERVLET,
                RainPostgres.freshDatabase("access_redis_outage"),
                redisStoreProperties(redis),
            )
    }

    @AfterAll
    fun stop() {
        if (::application.isInitialized) application.close()
    }

    @Test
    fun `readiness turns not ready naming both stores, a sign-in is 503 unavailable and a signed-in request 503 revocation_unavailable`() {
        val client = Client(application)
        client.enrolled("outage@example.test")
        val (bearer, _) = client.session("outage@example.test")
        assertThat(client.http.send("GET", "/ready").statusCode()).isEqualTo(200)

        redis.close()
        clock.advance(Duration.ofSeconds(2))

        val ready = client.http.send("GET", "/ready")
        val signIn = client.signIn("outage@example.test")
        val signInWithToken = client.signIn("outage@example.test", headers = arrayOf(bearer))
        val me = client.me(bearer)

        assertThat(ready.statusCode()).isEqualTo(503)
        assertThat(ready.tree()["status"].asString()).isEqualTo("not_ready")
        assertThat(ready.tree()["failing"].values().map { it.asString() }).containsExactlyInAnyOrder("access.attempts", "access.revocation")
        assertThat(signIn.statusCode() to signIn.tree()["code"].asString()).isEqualTo(503 to "unavailable")
        assertThat(signInWithToken.statusCode() to signInWithToken.tree()["code"].asString())
            .describedAs("a sign-in is refused for the store it needs, not for the token it does not")
            .isEqualTo(503 to "unavailable")
        assertThat(me.statusCode() to me.tree()["code"].asString()).isEqualTo(503 to "revocation_unavailable")
    }
}

/** The revocation list on a connection factory of its own, the attempt counters on the application's. */
@Configuration(proxyBeanMethods = false)
class DedicatedRevocationRedis {
    @Bean
    @Primary
    fun applicationRedis(environment: Environment): LettuceConnectionFactory =
        LettuceConnectionFactory(
            RedisStandaloneConfiguration(
                environment.getRequiredProperty("test.redis.application.host"),
                environment.getRequiredProperty("test.redis.application.port", Int::class.java),
            ),
        )

    @Bean(RainAccessAutoConfiguration.REVOCATION_QUALIFIER)
    fun rainRevocation(environment: Environment): LettuceConnectionFactory =
        LettuceConnectionFactory(
            RedisStandaloneConfiguration(
                environment.getRequiredProperty("test.redis.revocation.host"),
                environment.getRequiredProperty("test.redis.revocation.port", Int::class.java),
            ),
        )
}

/**
 * A deployment that gives the revocation list a Redis of its own names it `rainRevocation`; that server — a managed one
 * that will not say what it evicts — is accepted only on the deployment's attestation.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DedicatedRevocationRedisIT {
    private val applicationServer = RainRedis.start(RedisPolicy.RETAINING)
    private val revocationServer = RainRedis.start(RedisPolicy.SILENT)

    @AfterAll
    fun stop() {
        applicationServer.close()
        revocationServer.close()
    }

    private fun properties(vararg overrides: String): Array<String> =
        redisStoreProperties(
            applicationServer,
            "test.redis.application.host=${applicationServer.host}",
            "test.redis.application.port=${applicationServer.port}",
            "test.redis.revocation.host=${revocationServer.host}",
            "test.redis.revocation.port=${revocationServer.port}",
            *overrides,
        )

    @Test
    fun `the revocation list is written to the factory qualified rainRevocation and the attempt counters to the application's`() {
        val clock = MutableClock(START)
        val started =
            startWithClock(
                clock,
                WebApplicationType.SERVLET,
                RainPostgres.freshDatabase("access_dedicated_revocation"),
                properties("rain.access.revocation.redis.eviction-policy-attested=noeviction"),
                DedicatedRevocationRedis::class.java,
            )
        val (applicationFactory, applicationKeys) = templateOf(applicationServer)
        val (revocationFactory, revocationKeys) = templateOf(revocationServer)
        try {
            val client = Client(started)
            client.enrolled("dedicated@example.test")
            val (bearer, _) = client.session("dedicated@example.test")

            client.http.send("POST", "/api/auth/logout", null, bearer)
            client.signIn("dedicated@example.test", "not the password")

            assertThat(client.me(bearer).statusCode()).isEqualTo(401)
            assertThat(revocationKeys.keys("$REVOCATION_PREFIX*")).isNotEmpty()
            assertThat(revocationKeys.keys("$ATTEMPT_PREFIX*")).isEmpty()
            assertThat(applicationKeys.keys("$ATTEMPT_PREFIX*")).isNotEmpty()
            assertThat(applicationKeys.keys("$REVOCATION_PREFIX*")).isEmpty()
        } finally {
            started.close()
            applicationFactory.destroy()
            revocationFactory.destroy()
        }
    }

    @Test
    fun `a revocation server that will not say what it evicts refuses the start until the deployment attests noeviction`() {
        val failure =
            runCatching {
                startWithClock(
                    MutableClock(START),
                    WebApplicationType.SERVLET,
                    RainPostgres.freshDatabase("access_dedicated_unattested"),
                    properties(),
                    DedicatedRevocationRedis::class.java,
                ).close()
            }.exceptionOrNull()

        val refusal = generateSequence(failure, Throwable::cause).filterIsInstance<ConfigurationProblemsException>().firstOrNull()
        assertThat(refusal).describedAs("refused by configuration validation: $failure").isNotNull()
        assertThat(requireNotNull(refusal).problems.map { it.path to it.code })
            .contains("rain.access.revocation.redis.eviction-policy-attested" to ProblemCode.REQUIRED)
    }
}
