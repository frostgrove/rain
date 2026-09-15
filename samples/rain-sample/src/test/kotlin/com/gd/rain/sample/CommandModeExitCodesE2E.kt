package com.gd.rain.sample

import com.gd.rain.sample.stand.Awaits
import com.gd.rain.sample.stand.Stand
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The bootJar a deployment runs, as a subprocess of this test: each command states what it is in the environment,
 * exactly as the image's processes do, and answers with its exit code, its standard output and its standard error.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandModeExitCodesE2E {
    private val jar = File(checkNotNull(System.getProperty("sample.boot-jar")) { "the build passes the bootJar's path" })
    private val java =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElseThrow()
    private lateinit var database: RainDatabase

    private data class Exited(
        val code: Int,
        val out: String,
        val err: String,
    )

    @BeforeAll
    fun database() {
        database = RainPostgres.freshDatabase("command_exit_codes")
    }

    private fun environment(): Map<String, String> =
        mapOf(
            "RAIN_DEPLOYMENT_STAGE" to "test",
            "SPRING_DATASOURCE_URL" to database.url,
            "SPRING_DATASOURCE_USERNAME" to database.username,
            "SPRING_DATASOURCE_PASSWORD" to database.password,
            "SPRING_DATA_REDIS_HOST" to Stand.redisHost,
            "SPRING_DATA_REDIS_PORT" to Stand.redisPort.toString(),
            "RAIN_ACCESS_TOKEN_SIGNINGKEY" to Stand.SIGNING_KEY,
            "RAIN_ACCESS_HASHING_ARGON2_MEMORYKIB" to "1024",
            "RAIN_ACCESS_HASHING_ARGON2_ITERATIONS" to "1",
            "RAIN_ACCESS_HASHING_ARGON2_PARALLELISM" to "1",
            "SAMPLE_SEED_INITIALPASSWORD" to Stand.PASSWORD,
        )

    private fun run(
        environment: Map<String, String>,
        vararg arguments: String,
    ): Exited {
        val out = Files.createTempFile("rain-sample-out", ".txt").toFile()
        val err = Files.createTempFile("rain-sample-err", ".txt").toFile()
        val builder = ProcessBuilder(listOf(java, "-jar", jar.absolutePath) + arguments).redirectOutput(out).redirectError(err)
        builder.environment().keys.removeIf { it.startsWith("RAIN_") || it.startsWith("SPRING_") || it.startsWith("SAMPLE_") }
        builder.environment().putAll(environment)
        val process = builder.start()
        if (!process.waitFor(Awaits.BOUND_SECONDS * 2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("the process did not exit in time:\n${err.readText()}")
        }
        return Exited(process.exitValue(), out.readText(), err.readText()).also {
            out.delete()
            err.delete()
        }
    }

    private fun command(
        name: String,
        vararg arguments: String,
    ): Exited = run(environment() + ("RAIN_RUNTIME_COMMAND" to name), *arguments)

    @Test
    fun `config-check, migrate, seed and ticket-report exit 0 with their results on standard output`() {
        val checked = command("config-check")
        val migrated = command("migrate")
        val seeded = command("seed")
        val reported = command("ticket-report")

        assertThat(checked.code).describedAs(checked.err).isZero()
        assertThat(checked.out).isEqualTo("configuration: ok (stage=test)\n")
        assertThat(migrated.code).describedAs(migrated.err).isZero()
        assertThat(migrated.out.lines().filter(String::isNotEmpty)).containsExactly(
            "migrate: rain_access applied 1, now at 1",
            "migrate: rain_audit applied 1, now at 1",
            "migrate: rain_jobs applied 1, now at 1",
            "migrate: rain_llm applied 1, now at 1",
            "migrate: application applied 1, now at 1",
        )
        assertThat(seeded.code).describedAs(seeded.err).isZero()
        assertThat(seeded.out).isEmpty()
        assertThat(seeded.err).contains("seeded: helpdesk.roles", "seeded: helpdesk.agents", "seeding complete: ran=2")
        assertThat(reported.code).describedAs(reported.err).isZero()
        assertThat(reported.out).isEmpty()
        assertThat(reported.err).contains("ticket-report: 0 open tickets; last page")
    }

    @Test
    fun `an undeclared command exits non-zero naming the declared ones on standard error`() {
        val refused = command("no-such-command")

        assertThat(refused.code).isNotZero()
        assertThat(refused.out).isEmpty()
        assertThat(refused.err).contains("rain.runtime.command", "no-such-command", "ticket-report")
    }

    @Test
    fun `a refused configuration exits non-zero with every problem on standard error and nothing on standard output`() {
        val refused = run(environment() - "RAIN_DEPLOYMENT_STAGE" - "RAIN_ACCESS_TOKEN_SIGNINGKEY" + ("RAIN_RUNTIME_ROLES" to "api"))

        assertThat(refused.code).isNotZero()
        assertThat(refused.out).isEmpty()
        assertThat(
            refused.err,
        ).contains("the configuration has 2 problems:", "rain.deployment.stage [required]", "rain.access.token.signing-key")
    }
}
