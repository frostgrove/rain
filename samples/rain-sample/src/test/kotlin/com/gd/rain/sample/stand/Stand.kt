package com.gd.rain.sample.stand

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.Enrolment
import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.sample.SampleApplication
import com.gd.rain.sample.agent.AgentRegistry
import com.gd.rain.sample.agent.Agents
import com.gd.rain.sample.seed.RolesSeeder
import com.gd.rain.test.ApplicationHttp
import com.gd.rain.test.RainApplication
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainRedis
import com.gd.rain.test.RedisPolicy
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * What every started sample shares in one test JVM: rain-test's PostgreSQL (a fresh database per test) and rain-test's
 * Redis that never evicts, as the deployment's revocation list and attempt counters need. Each process states only what a
 * deployment states per process — its stage, its role or command, where its dependencies are, its secrets — on top of the
 * sample's own `application.yml`.
 */
object Stand {
    const val PASSWORD = "correct horse battery staple"

    /** A test deployment's signing key: 32 fixed bytes, stated from a file, which the `test` stage accepts. */
    val SIGNING_KEY: String = "base64:" + Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })

    val JSON: JsonMapper = JsonMapper.builder().build()

    private val redis get() = RainRedis.shared(RedisPolicy.RETAINING)

    val redisHost: String get() = redis.host
    val redisPort: Int get() = redis.port

    /**
     * The properties of one process against [database], as `name=value`; `RainApplication` states them as command-line
     * arguments, which outrank `application.yml` the way a deployment's environment does. [process] adds or replaces entries.
     */
    fun properties(
        database: RainDatabase,
        vararg process: String,
    ): List<String> {
        val stated =
            linkedMapOf(
                "rain.deployment.stage" to "test",
                "server.port" to "0",
                "spring.datasource.url" to database.url,
                "spring.datasource.username" to database.username,
                "spring.datasource.password" to database.password,
                // Several processes of one test share a PostgreSQL server; none holds idle connections it does not use.
                "spring.datasource.hikari.minimum-idle" to "1",
                "spring.data.redis.host" to redisHost,
                "spring.data.redis.port" to redisPort.toString(),
                "rain.access.token.signing-key" to SIGNING_KEY,
                // Argon2id at a cost a test suite can afford; the deployment's own cost is the module's declared default.
                "rain.access.hashing.argon2.memory-kib" to "1024",
                "rain.access.hashing.argon2.iterations" to "1",
                "rain.access.hashing.argon2.parallelism" to "1",
                "sample.seed.initial-password" to PASSWORD,
                // One Redis serves every test JVM's processes: each database keeps its own attempt counters and revocations.
                "rain.access.attempts.redis.key-prefix" to "${database.name()}:attempts",
                "rain.access.revocation.redis.key-prefix" to "${database.name()}:revocation",
            )
        process.forEach { entry ->
            val (key, value) = entry.split('=', limit = 2)
            stated[key] = value
        }
        return stated.map { (key, value) -> "$key=$value" }
    }

    /** Runs [command] to its end against [database], in this JVM, and answers its exit code and output. */
    fun command(
        database: RainDatabase,
        command: String,
        vararg process: String,
        sources: List<Class<*>> = emptyList(),
    ): CommandRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        CapturedOutput.current = CommandOutput(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        try {
            val application =
                RainApplication.start(
                    listOf(SampleApplication::class.java, CapturedOutput::class.java) + sources,
                    WebApplicationType.NONE,
                    properties(database, "rain.runtime.command=$command", *process),
                )
            val code = application.exit()
            return CommandRun(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } finally {
            CapturedOutput.current = null
        }
    }

    /** Migrates [database] with the `migrate` command, as a deployment's first step does. */
    fun migrate(database: RainDatabase) {
        val run = command(database, "migrate")
        assertThat(run.code).describedAs(run.err).isZero()
    }

    fun jdbc(database: RainDatabase): JdbcTemplate = JdbcTemplate(database.dataSource())
}

/** The database's name: the last segment of its URL. */
fun RainDatabase.name(): String = url.substringAfterLast('/')

data class CommandRun(
    val code: Int,
    val out: String,
    val err: String,
)

/** Hands a command the streams a test reads, in place of rain-boot's `CommandOutput.SYSTEM` (a replaceable bean). */
class CapturedOutput {
    @Bean
    fun commandOutput(): CommandOutput = checkNotNull(current) { "a captured command output is set by Stand.command" }

    companion object {
        @Volatile
        var current: CommandOutput? = null
    }
}

/** One started process of the sample, served like a deployment's: a servlet application in its role. */
class SampleProcess private constructor(
    private val application: RainApplication,
) : AutoCloseable {
    val context: ConfigurableApplicationContext get() = application.context

    val port: Int get() = application.port

    val http: ApplicationHttp get() = application.http

    fun <T : Any> bean(type: KClass<T>): T = application.bean(type)

    override fun close() {
        application.close()
    }

    companion object {
        fun start(
            database: RainDatabase,
            vararg process: String,
            sources: List<Class<*>> = emptyList(),
        ): SampleProcess =
            SampleProcess(
                RainApplication.start(
                    listOf(SampleApplication::class.java) + sources,
                    WebApplicationType.SERVLET,
                    Stand.properties(database, *process),
                ),
            )

        fun api(
            database: RainDatabase,
            vararg process: String,
            sources: List<Class<*>> = emptyList(),
        ): SampleProcess = start(database, "rain.runtime.roles=api", *process, sources = sources)

        fun worker(
            database: RainDatabase,
            vararg process: String,
            sources: List<Class<*>> = emptyList(),
        ): SampleProcess = start(database, "rain.runtime.roles=worker", *process, sources = sources)
    }
}

fun HttpResponse<String>.json(): JsonNode = Stand.JSON.readTree(body())

fun HttpResponse<String>.setCookies(): List<String> = headers().allValues("set-cookie")

fun HttpResponse<String>.cookie(name: String): String =
    setCookies()
        .single {
            it.startsWith("$name=")
        }.substringAfter('=')
        .substringBefore(';')

/** The problem code of a refusal. */
fun HttpResponse<String>.code(): String = json()["code"].asString()

/** Agents of a started process, enrolled the way the seed command enrols them. */
object Staff {
    const val AGENT_LOGIN = "/v1/auth/agent/login"

    fun ensureRoles(process: SampleProcess) {
        RolesSeeder(process.bean(AccessProvisioning::class)).seed()
    }

    fun enrol(
        process: SampleProcess,
        identifier: String,
        vararg roles: String,
    ): UUID {
        val id = process.bean(AgentRegistry::class).ensure(identifier, "Agent ${identifier.substringBefore('@')}")
        val provisioning = process.bean(AccessProvisioning::class)
        roles.forEach { provisioning.grantRole(Agents.ref(id), it) }
        assertThat(
            provisioning.enrolPassword(Agents.ref(id), identifier, Stand.PASSWORD),
        ).isIn(Enrolment.Enrolled, Enrolment.AlreadyEnrolled)
        return id
    }

    fun credentials(
        identifier: String,
        password: String = Stand.PASSWORD,
    ): String = """{"identifier":"$identifier","password":"$password"}"""

    /** Signs in with credentials delivered in the body; answers the `Authorization` header its access token makes. */
    fun bearer(
        process: SampleProcess,
        identifier: String,
    ): Pair<String, String> {
        val answer = process.http.send("POST", AGENT_LOGIN, credentials(identifier), "Rain-Auth-Delivery" to "body")
        assertThat(answer.statusCode()).describedAs(answer.body()).isEqualTo(200)
        return "Authorization" to "Bearer ${answer.json()["accessToken"].asString()}"
    }
}

/** Bounded waits: every wait ends by a deadline and fails the test when it passes. */
object Awaits {
    const val BOUND_SECONDS: Long = 60

    fun latch(
        latch: CountDownLatch,
        what: String,
    ) {
        check(latch.await(BOUND_SECONDS, TimeUnit.SECONDS)) { "timed out waiting for $what" }
    }

    /** Re-evaluates [condition] until it holds; each pause between probes is itself a bounded wait on a latch nobody counts down. */
    fun until(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
        val pause = CountDownLatch(1)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            pause.await(PROBE_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private const val PROBE_MILLIS = 20L
}
