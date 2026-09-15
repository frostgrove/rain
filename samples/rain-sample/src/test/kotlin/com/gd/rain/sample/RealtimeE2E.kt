package com.gd.rain.sample

import com.gd.rain.sample.access.HelpdeskRoles
import com.gd.rain.sample.stand.Awaits
import com.gd.rain.sample.stand.EventStreamReader
import com.gd.rain.sample.stand.SampleProcess
import com.gd.rain.sample.stand.ServerEvent
import com.gd.rain.sample.stand.Staff
import com.gd.rain.sample.stand.Stand
import com.gd.rain.sample.stand.json
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Holds the closing of a process between readiness turning `draining` and the web server stopping: the highest phase
 * stops first, so while [release] is not counted down the server still answers, and a test reads what it answers.
 */
class HeldShutdown {
    @Bean
    fun shutdownHeldByTheTest(): SmartLifecycle =
        object : SmartLifecycle {
            @Volatile
            private var running = false

            override fun start() {
                running = true
            }

            override fun stop() {
                stopping.countDown()
                Awaits.latch(release, "the test to release the shutdown")
                running = false
            }

            override fun isRunning(): Boolean = running

            override fun getPhase(): Int = Int.MAX_VALUE
        }

    companion object {
        val stopping = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}

/** A ticket's changes as server-sent events, and readiness while the api process closes. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealtimeE2E {
    private lateinit var database: RainDatabase
    private lateinit var api: SampleProcess
    private lateinit var supervisor: Pair<String, String>
    private lateinit var assigned: String

    @BeforeAll
    fun start() {
        database = RainPostgres.freshDatabase("realtime")
        Stand.migrate(database)
        api = SampleProcess.api(database, "sample.tickets.events.stream-for=3s", "sample.tickets.events.poll-every=1s")
        Staff.ensureRoles(api)
        val id = Staff.enrol(api, SUPERVISOR, HelpdeskRoles.SUPERVISOR)
        supervisor = Staff.bearer(api, SUPERVISOR)
        assigned = id.toString()
    }

    @AfterAll
    fun stop() {
        if (::api.isInitialized) api.close()
    }

    @Test
    fun `a subscriber reads the ticket, then every change as it commits, then the end of the stream`() {
        val opened =
            api.http
                .send(
                    "POST",
                    "/v1/tickets",
                    """{"title":"watched","body":"b","priority":5,"assignee":"$assigned"}""",
                    supervisor,
                ).json()
        val id = opened["id"].asString()

        EventStreamReader.open(api.http, "/v1/tickets/$id/events", supervisor).use { stream ->
            val current = stream.next()
            assertThat(current.name).isEqualTo("ticket")
            assertThat(Stand.JSON.readTree(current.data)["version"].asLong()).isEqualTo(1)

            api.http.send("PATCH", "/v1/tickets/$id", """{"version":1,"priority":60}""", supervisor)
            api.http.send("POST", "/v1/tickets/$id/close", null, supervisor)

            assertThat(stream.next()).isEqualTo(ServerEvent("change", """{"id":"$id","version":2,"status":"open"}"""))
            assertThat(stream.next()).isEqualTo(ServerEvent("change", """{"id":"$id","version":3,"status":"closed"}"""))
            assertThat(stream.next()).isEqualTo(ServerEvent("end", """{"reason":"renew"}"""))
        }
    }

    @Test
    fun `a ticket outside the caller's reach has no stream`() {
        val refused = api.http.get("/v1/tickets/6f6e05b5-8f22-4a1a-9b3b-000000000009/events", supervisor)
        val anonymous = api.http.get("/v1/tickets/6f6e05b5-8f22-4a1a-9b3b-000000000009/events")

        assertThat(refused.statusCode()).isEqualTo(404)
        assertThat(anonymous.statusCode()).isEqualTo(401)
    }

    @Test
    fun `readiness answers draining while the api process closes, before its server stops`() {
        val closing = SampleProcess.api(database, sources = listOf(HeldShutdown::class.java))
        val closer = Thread.ofVirtual().unstarted { closing.close() }
        try {
            val ready = closing.http.get("/ready")
            assertThat(ready.body()).describedAs("before closing").isEqualTo("""{"status":"ready","failing":[]}""")

            closer.start()
            Awaits.latch(HeldShutdown.stopping, "the process to start closing")
            val draining = closing.http.get("/ready")

            assertThat(draining.statusCode()).isEqualTo(503)
            assertThat(draining.body()).isEqualTo("""{"status":"draining","failing":[]}""")
        } finally {
            HeldShutdown.release.countDown()
            if (closer.isAlive) closer.join(TimeUnit.SECONDS.toMillis(Awaits.BOUND_SECONDS)) else closing.close()
        }
        assertThat(closer.isAlive).describedAs("the process closed once released").isFalse()
    }

    private companion object {
        const val SUPERVISOR = "realtime-supervisor@helpdesk.example"
    }
}
