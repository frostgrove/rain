package com.gd.rain.sample.stand

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.Enrolment
import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.sample.SampleApplication
import com.gd.rain.sample.agent.AgentRegistry
import com.gd.rain.sample.agent.Agents
import com.gd.rain.sample.seed.RolesSeeder
import com.gd.rain.test.RainDatabase
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * What every started sample shares in one test JVM: rain-test's PostgreSQL (a fresh database per test) and one Redis
 * that never evicts, as the deployment's revocation list and attempt counters need. Each process states only what a
 * deployment states per process — its stage, its role or command, where its dependencies are, its secrets — on top of
 * the sample's own `application.yml`.
 */
object Stand {
    const val REDIS_IMAGE = "redis:8"
    const val REDIS_PORT = 6379
    const val PASSWORD = "correct horse battery staple"

    /** A test deployment's signing key: 32 fixed bytes, stated from a file, which the `test` stage accepts. */
    val SIGNING_KEY: String = "base64:" + Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })

    val JSON: JsonMapper = JsonMapper.builder().build()

    private val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT)
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
            .also { it.start() }
    }

    val redisHost: String get() = redis.host
    val redisPort: Int get() = redis.getMappedPort(REDIS_PORT)

    /**
     * The properties of one process against [database], as `--name=value` arguments, which outrank `application.yml`
     * the way a deployment's environment does. [process] adds or replaces entries.
     */
    fun arguments(
        database: RainDatabase,
        vararg process: String,
    ): Array<String> {
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
        return stated.map { (key, value) -> "--$key=$value" }.toTypedArray()
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
            val context =
                SpringApplicationBuilder(*(listOf(SampleApplication::class.java, CapturedOutput::class.java) + sources).toTypedArray())
                    .web(WebApplicationType.NONE)
                    .logStartupInfo(false)
                    .run(*arguments(database, "rain.runtime.command=$command", *process))
            val code = SpringApplication.exit(context)
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

/** One started process of the sample. */
class SampleProcess private constructor(
    val context: ConfigurableApplicationContext,
) : AutoCloseable {
    val port: Int get() = checkNotNull((context as WebServerApplicationContext).webServer).port

    val http: Http by lazy { Http(port) }

    fun <T : Any> bean(type: KClass<T>): T = context.getBean(type.java)

    override fun close() {
        context.close()
    }

    companion object {
        fun start(
            database: RainDatabase,
            vararg process: String,
            sources: List<Class<*>> = emptyList(),
        ): SampleProcess =
            SampleProcess(
                SpringApplicationBuilder(*(listOf(SampleApplication::class.java) + sources).toTypedArray())
                    .logStartupInfo(false)
                    .run(*Stand.arguments(database, *process)),
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

/** A minimal HTTP client over a started process, with no cookie jar: every header a test sends is written by the test. */
class Http(
    private val port: Int,
) {
    val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    fun send(
        method: String,
        path: String,
        body: String? = null,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(uri(path))
                .timeout(Duration.ofSeconds(60))
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null &&
            headers.none { it.first.equals("Content-Type", ignoreCase = true) }
        ) {
            request.header("Content-Type", "application/json")
        }
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> = send("GET", path, null, *headers)
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
