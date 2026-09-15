package com.gd.rain.sample

import com.gd.rain.sample.access.HelpdeskRoles
import com.gd.rain.sample.stand.Awaits
import com.gd.rain.sample.stand.SampleProcess
import com.gd.rain.sample.stand.Staff
import com.gd.rain.sample.stand.Stand
import com.gd.rain.sample.stand.json
import com.gd.rain.sample.summary.SummaryPacing
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/** A drafting step a test holds open: [entered] counts down when the step starts, and the step returns when [finish] does. */
class HeldPacing {
    @Bean
    @Primary
    fun draftingStepHeldByTheTest(): SummaryPacing =
        SummaryPacing { ticket ->
            started += ticket
            entered.countDown()
            Awaits.latch(finish, "the test to let the drafting step finish")
        }

    companion object {
        val started = CopyOnWriteArrayList<UUID>()

        @Volatile
        var entered = CountDownLatch(1)

        @Volatile
        var finish = CountDownLatch(1)

        fun reset() {
            started.clear()
            entered = CountDownLatch(1)
            finish = CountDownLatch(1)
        }
    }
}

/** A drafting step that only records which ticket it drafted, in the order the worker took them. */
class RecordedPacing {
    @Bean
    @Primary
    fun draftingStepRecordedByTheTest(): SummaryPacing = SummaryPacing { ticket -> drafted += ticket }

    companion object {
        val drafted = CopyOnWriteArrayList<UUID>()
    }
}

/**
 * `ticket.summarize` across real processes: an api process orders summaries, a worker process drafts them with the
 * in-sample model and stores them in the job's fence. The job's rows are read from `rain_jobs.job_invocation` by id.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SummaryJobsE2E {
    private lateinit var database: RainDatabase
    private lateinit var api: SampleProcess
    private lateinit var supervisor: Pair<String, String>
    private lateinit var supervisorId: UUID

    @BeforeAll
    fun start() {
        database = RainPostgres.freshDatabase("summary_jobs")
        Stand.migrate(database)
        api = SampleProcess.api(database)
        Staff.ensureRoles(api)
        supervisorId = Staff.enrol(api, SUPERVISOR, HelpdeskRoles.SUPERVISOR)
        supervisor = Staff.bearer(api, SUPERVISOR)
    }

    @AfterAll
    fun stop() {
        if (::api.isInitialized) api.close()
    }

    private fun ticket(
        title: String,
        priority: Int,
    ): String =
        api.http
            .send(
                "POST",
                "/v1/tickets",
                """{"title":"$title","body":"Printer on floor 3 jams on every duplex job.","priority":$priority}""",
                supervisor,
            ).json()["id"]
            .asString()

    private fun order(ticket: String): UUID {
        val ordered = api.http.send("POST", "/v1/tickets/$ticket/summary", null, supervisor)
        assertThat(ordered.statusCode()).describedAs(ordered.body()).isEqualTo(202)
        return UUID.fromString(ordered.json()["invocation"].asString())
    }

    private fun invocation(id: UUID): Map<String, Any?> =
        Stand.jdbc(database).queryForMap(
            "SELECT state, attempts, retry_spent, lease_token, lease_expires_at, subject_key FROM rain_jobs.job_invocation WHERE id = ?",
            id,
        )

    private fun summaryOf(ticket: String): String? =
        Stand.jdbc(database).queryForObject("SELECT summary FROM tickets WHERE id = ?::uuid", String::class.java, ticket)

    @Test
    fun `a drafting step longer than the lease keeps its lease, and its summary is stored`() {
        HeldPacing.reset()
        val ticket = ticket("long step", 10)
        val invocation = order(ticket)
        SampleProcess
            .worker(database, "rain.jobs.lease.ttl=3s", "rain.jobs.lease.renew-interval=1s", sources = listOf(HeldPacing::class.java))
            .use { worker ->
                Awaits.latch(HeldPacing.entered, "the drafting step to start")
                val leased = invocation(invocation)
                val firstExpiry = (leased["lease_expires_at"] as Timestamp).toInstant()

                Awaits.until("the step to outlive its first lease while the lease is renewed") {
                    val now = Stand.jdbc(database).queryForObject("SELECT now()", Timestamp::class.java)!!.toInstant()
                    val current = invocation(invocation)
                    now.isAfter(firstExpiry) && (current["lease_expires_at"] as Timestamp).toInstant().isAfter(now)
                }
                val renewed = invocation(invocation)
                assertThat(renewed["state"]).isEqualTo("running")
                assertThat(renewed["lease_token"]).describedAs("the same attempt holds it").isEqualTo(leased["lease_token"])

                HeldPacing.finish.countDown()
                Awaits.until("the summary to be stored") { invocation(invocation)["state"] == "succeeded" }
                assertThat(worker.http.get("/ready").statusCode()).isEqualTo(200)
            }

        val done = invocation(invocation)
        assertThat(done["attempts"]).isEqualTo(1)
        assertThat(done["subject_key"]).isEqualTo("ticket:$ticket")
        assertThat(summaryOf(ticket)).startsWith("Summary: long step")
    }

    @Test
    fun `a worker that stops mid-step releases the attempt uncharged, and the next worker completes it`() {
        HeldPacing.reset()
        val ticket = ticket("restart", 10)
        val invocation = order(ticket)
        val first = SampleProcess.worker(database, sources = listOf(HeldPacing::class.java))
        Awaits.latch(HeldPacing.entered, "the drafting step to start on the first worker")

        first.close()
        val released = invocation(invocation)

        SampleProcess.worker(database).use {
            Awaits.until("the second worker to complete the summary") { invocation(invocation)["state"] == "succeeded" }
        }
        val done = invocation(invocation)
        assertThat(released["state"]).isEqualTo("queued")
        assertThat(released["retry_spent"]).isEqualTo(0)
        assertThat(done["attempts"]).isEqualTo(2)
        assertThat(done["retry_spent"]).describedAs("stopping a worker charges no retry").isEqualTo(0)
        assertThat(summaryOf(ticket)).startsWith("Summary: restart")
    }

    @Test
    fun `summaries ordered while no worker runs are drafted highest priority first`() {
        RecordedPacing.drafted.clear()
        val byPriority = listOf(10, 90, 50, 5).associateWith { ticket("priority $it", it) }
        val invocations = byPriority.values.map(::order)

        SampleProcess.worker(database, "rain.jobs.workers.ticket.summarize=1", sources = listOf(RecordedPacing::class.java)).use {
            Awaits.until("all four summaries") { invocations.all { invocation(it)["state"] == "succeeded" } }
        }

        assertThat(
            RecordedPacing.drafted.map(UUID::toString),
        ).containsExactly(byPriority[90], byPriority[50], byPriority[10], byPriority[5])
    }

    @Test
    fun `deleting a ticket cancels its summary, and ordering it twice keeps one order`() {
        val ticket = ticket("deleted", 10)
        val invocation = order(ticket)
        val again = api.http.send("POST", "/v1/tickets/$ticket/summary", null, supervisor).json()

        val deleted = api.http.send("DELETE", "/v1/tickets/$ticket", null, supervisor)

        assertThat(again["outcome"].asString()).isEqualTo("deduplicated")
        assertThat(again["invocation"].asString()).isEqualTo(invocation.toString())
        assertThat(deleted.statusCode()).isEqualTo(200)
        assertThat(invocation(invocation)["state"]).isEqualTo("cancelled")
    }

    private companion object {
        const val SUPERVISOR = "jobs-supervisor@helpdesk.example"
    }
}
