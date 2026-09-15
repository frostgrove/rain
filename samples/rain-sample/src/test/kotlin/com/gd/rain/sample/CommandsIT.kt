package com.gd.rain.sample

import com.gd.rain.boot.seed.Seeder
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.sample.stand.CapturedOutput
import com.gd.rain.sample.stand.SampleProcess
import com.gd.rain.sample.stand.Staff
import com.gd.rain.sample.stand.Stand
import com.gd.rain.sample.stand.json
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A seeder that takes the name of one of the helpdesk's, which makes the seed set broken. */
class DuplicateSeeder {
    @Bean
    fun seederNamedLikeTheRoles(): Seeder =
        object : Seeder {
            override val name: String = "helpdesk.roles"
            override val order: Int = 1

            override fun seed() = error("a broken seed set runs no seeder")
        }
}

/**
 * What each one-shot command writes and with what exit code — the half of a command that is a contract with whoever runs
 * it. Ported from the Lease `OneShotRunnerIT`: the redline report is the helpdesk's `ticket-report`, `smoke-llm` runs
 * against the in-sample model, and the seed runner is rain-boot's `seed` over the helpdesk's seeders.
 */
@Tag("integration")
class OneShotRunnerIT {
    private companion object {
        const val ADMIN = "admin@helpdesk.example"
    }

    private fun openTicket(
        database: RainDatabase,
        title: String,
        createdAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        Stand.jdbc(database).update(
            "INSERT INTO tickets (id, title, body, status, priority, created_at, updated_at, version) VALUES (?, ?, 'b', 'open', 5, ?, ?, 1)",
            id,
            title,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
        )
        return id
    }

    @Test
    fun `the report writes one page of open tickets to standard output, and the count and the next page to standard error`() {
        val database = RainPostgres.freshDatabase("report_pages").also(Stand::migrate)
        val at = Instant.parse("2026-09-15T10:00:00Z")
        val ids = (0..2).map { openTicket(database, "ticket $it", at.plusSeconds(it.toLong())) }

        val first = Stand.command(database, "ticket-report", "sample.tickets.report.page-size=2")
        val next = first.err.substringAfter("--after=").trim()
        val second = Stand.command(database, "ticket-report", "sample.tickets.report.page-size=2", "after=$next")

        assertThat(first.code).isZero()
        assertThat(
            first.out.lines().filter(String::isNotEmpty).map {
                it.substringBefore('\t')
            },
        ).containsExactly(ids[0].toString(), ids[1].toString())
        assertThat(first.err).isEqualTo("ticket-report: 2 open tickets; next page: --after=${at.plusSeconds(1)}_${ids[1]}\n")
        assertThat(
            second.out
                .lines()
                .filter(String::isNotEmpty)
                .single(),
        ).isEqualTo("${ids[2]}\t5\t-\t${at.plusSeconds(2)}\tticket 2")
        assertThat(second.err).isEqualTo("ticket-report: 1 open tickets; last page\n")
    }

    @Test
    fun `an empty report still reports its count, and a cursor it cannot read is a usage error`() {
        val database = RainPostgres.freshDatabase("report_empty").also(Stand::migrate)

        val empty = Stand.command(database, "ticket-report")
        val unreadable = Stand.command(database, "ticket-report", "after=yesterday")

        assertThat(empty.code to empty.out).isEqualTo(0 to "")
        assertThat(empty.err).isEqualTo("ticket-report: 0 open tickets; last page\n")
        assertThat(unreadable.code).isEqualTo(2)
        assertThat(unreadable.err).isEqualTo("ticket-report: --after is <created-at>_<id>\n")
    }

    @Test
    fun `smoke-llm exits 0 when the in-sample model answers`() {
        val database = RainPostgres.freshDatabase("smoke_llm").also(Stand::migrate)

        val answered = Stand.command(database, "smoke-llm")

        assertThat(answered.code).describedAs(answered.err).isZero()
        assertThat(answered.out).startsWith("smoke-llm: model helpdesk-summarizer answered with ")
    }

    @Test
    fun `the seed command runs the helpdesk's seeders in their order`() {
        val database = RainPostgres.freshDatabase("seed_order").also(Stand::migrate)

        val seeded = Stand.command(database, "seed")

        assertThat(seeded.code).isZero()
        assertThat(seeded.err.lines().filter { it.startsWith("seed") })
            .containsExactly("seeding: registered=2", "seeded: helpdesk.roles", "seeded: helpdesk.agents", "seeding complete: ran=2")
    }

    @Test
    fun `a broken seed set is refused before any seeder writes anything`() {
        val database = RainPostgres.freshDatabase("seed_broken").also(Stand::migrate)

        val failure = catchThrowable { Stand.command(database, "seed", sources = listOf(DuplicateSeeder::class.java)) }

        val refusal = generateSequence(failure, Throwable::cause).filterIsInstance<ConfigurationProblemsException>().first()
        assertThat(refusal.problems.map { it.path }).contains("seeder:helpdesk.roles")
        val jdbc = Stand.jdbc(database)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agents WHERE identifier = ?", Long::class.java, ADMIN)).isZero()
    }
}

/**
 * The seed command against a real database, started through Spring as a deployment starts it: it creates what
 * `sample.seed` describes, a second run changes nothing, a run that stopped between an agent and its credential completes
 * on the next, and a password an agent changed is never overwritten. Ported from the Lease `SeedCommandIT`.
 */
@Tag("integration")
class SeedCommandIT {
    private val agents =
        listOf(
            "admin@helpdesk.example" to "administrator",
            "supervisor@helpdesk.example" to "supervisor",
            "responder@helpdesk.example" to "responder",
        )

    private fun seed(database: RainDatabase) {
        val run = Stand.command(database, "seed")
        assertThat(run.code).describedAs(run.err).isZero()
    }

    private fun agentId(
        database: RainDatabase,
        identifier: String,
    ): UUID = Stand.jdbc(database).queryForObject("SELECT id FROM agents WHERE identifier = ?", UUID::class.java, identifier)!!

    /** Every row the seed can write, read row by row through its key: the whole of what "nothing changed" is about. */
    private fun snapshot(database: RainDatabase): List<Map<String, Any?>> {
        val jdbc = Stand.jdbc(database)
        return listOf("supervisor", "responder", "administrator").map {
            jdbc.queryForMap("SELECT id, name, is_system, grants_every_permission, created_at FROM rain_access.roles WHERE slug = ?", it)
        } +
            agents.flatMap { (identifier, role) ->
                val id = agentId(database, identifier)
                listOf(
                    jdbc.queryForMap("SELECT id, active, created_at FROM agents WHERE id = ?", id),
                    jdbc.queryForMap("SELECT display_name, version FROM agent_profiles WHERE agent_id = ?", id),
                    jdbc.queryForMap(
                        "SELECT id, identifier, secret_hash, version, updated_at FROM rain_access.credentials " +
                            "WHERE subject_type = 'agent' AND subject_id = ? AND provider = 'password'",
                        id,
                    ),
                    jdbc.queryForMap(
                        "SELECT r.granted_at FROM rain_access.subject_roles r JOIN rain_access.roles o ON o.id = r.role_id " +
                            "WHERE r.subject_type = 'agent' AND r.subject_id = ? AND o.slug = ?",
                        id,
                        role,
                    ),
                )
            }
    }

    @Test
    fun `a seed run creates the roles and one enrolled agent per role, and a second run changes nothing at all`() {
        val database = RainPostgres.freshDatabase("seed_creates").also(Stand::migrate)

        seed(database)
        val first = snapshot(database)
        seed(database)

        assertThat(snapshot(database)).isEqualTo(first)
        assertThat(first[2]).containsEntry("is_system", true).containsEntry("grants_every_permission", true)
        assertThat(first[0]).containsEntry("is_system", false)
        SampleProcess.api(database).use { api -> agents.forEach { (identifier, _) -> Staff.bearer(api, identifier) } }
    }

    @Test
    fun `a run that stopped between an agent and its credential completes on the next run`() {
        val database = RainPostgres.freshDatabase("seed_resumes").also(Stand::migrate)
        seed(database)
        val admin = agentId(database, "admin@helpdesk.example")
        Stand.jdbc(database).update("DELETE FROM rain_access.credentials WHERE subject_type = 'agent' AND subject_id = ?", admin)

        seed(database)

        assertThat(agentId(database, "admin@helpdesk.example")).describedAs("the agent itself was not created again").isEqualTo(admin)
        SampleProcess.api(database).use { api -> Staff.bearer(api, "admin@helpdesk.example") }
    }

    @Test
    fun `a password an agent changed is never overwritten by a later run`() {
        val database = RainPostgres.freshDatabase("seed_keeps_password").also(Stand::migrate)
        seed(database)
        SampleProcess.api(database).use { api ->
            val bearer = Staff.bearer(api, "responder@helpdesk.example")
            val changed = api.http.send("POST", "/v1/auth/password", """{"current":"${Stand.PASSWORD}","next":"$CHOSEN"}""", bearer)
            assertThat(changed.statusCode()).isEqualTo(204)
        }

        seed(database)

        SampleProcess.api(database).use { api ->
            assertThat(
                api.http
                    .send(
                        "POST",
                        Staff.AGENT_LOGIN,
                        Staff.credentials("responder@helpdesk.example"),
                        "Rain-Auth-Delivery" to "body",
                    ).statusCode(),
            ).isEqualTo(401)
            val signedIn =
                api.http.send(
                    "POST",
                    Staff.AGENT_LOGIN,
                    Staff.credentials("responder@helpdesk.example", CHOSEN),
                    "Rain-Auth-Delivery" to "body",
                )
            assertThat(signedIn.statusCode()).isEqualTo(200)
            assertThat(signedIn.json()["principal"]["profile"]["displayName"].asString()).isEqualTo("Rae Responder")
        }
    }

    @Test
    fun `only the seed command holds the helpdesk's seeders`() {
        val database = RainPostgres.freshDatabase("seed_selection").also(Stand::migrate)
        CapturedOutput.current =
            com.gd.rain.boot.command
                .CommandOutput(PrintStream(ByteArrayOutputStream(), true), PrintStream(ByteArrayOutputStream(), true))
        val seeding =
            try {
                SpringApplicationBuilder(SampleApplication::class.java, CapturedOutput::class.java)
                    .web(WebApplicationType.NONE)
                    .logStartupInfo(false)
                    .run(*Stand.arguments(database, "rain.runtime.command=seed"))
            } finally {
                CapturedOutput.current = null
            }
        val seeders = seeding.getBeansOfType(Seeder::class.java).values.map { it.name }
        SpringApplication.exit(seeding)

        assertThat(seeders).containsExactlyInAnyOrder("helpdesk.roles", "helpdesk.agents")
        SampleProcess.api(database).use { api -> assertThat(api.context.getBeansOfType(Seeder::class.java)).isEmpty() }
    }

    private companion object {
        const val CHOSEN = "a password the responder chose"
    }
}
