package com.gd.rain.sample

import com.gd.rain.jobs.RecurringWork
import com.gd.rain.observability.health.HealthCheck
import com.gd.rain.sample.access.HelpdeskRoles
import com.gd.rain.sample.stand.Awaits
import com.gd.rain.sample.stand.SampleProcess
import com.gd.rain.sample.stand.Staff
import com.gd.rain.sample.stand.Stand
import com.gd.rain.sample.stand.code
import com.gd.rain.sample.stand.json
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/** What a role gates: an `api` process serves and consumes nothing, a `worker` process consumes and serves only probes. */
@Tag("integration")
class RoleGatingE2E {
    @Test
    fun `an api process runs no scheduler and no recurring work, and a worker serves only the probes and runs the jobs`() {
        val database = RainPostgres.freshDatabase("role_gating")
        Stand.migrate(database)
        val jdbc = Stand.jdbc(database)

        fun recurringTasks(): Long =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT count(*) FROM rain_jobs.scheduled_tasks WHERE task_name = ?",
                    Long::class.java,
                    "ticket.escalation-sweep",
                ),
            )

        SampleProcess.api(database).use { api ->
            Staff.ensureRoles(api)
            Staff.enrol(api, SUPERVISOR, HelpdeskRoles.SUPERVISOR)
            val supervisor = Staff.bearer(api, SUPERVISOR)
            val ticket =
                api.http
                    .send(
                        "POST",
                        "/v1/tickets",
                        """{"title":"gated","body":"b","priority":1}""",
                        supervisor,
                    ).json()["id"]
                    .asString()
            val invocation =
                UUID.fromString(
                    api.http
                        .send("POST", "/v1/tickets/$ticket/summary", null, supervisor)
                        .json()["invocation"]
                        .asString(),
                )

            assertThat(
                api.context
                    .getBeansOfType(HealthCheck::class.java)
                    .values
                    .map { it.name },
            ).describedAs("no job worker, hence no jobs check")
                .doesNotContain("jobs")
                .contains("database", "realtime.listener", "breaker.summarizer", "access.revocation", "access.attempts")
            assertThat(api.context.getBeansOfType(RecurringWork::class.java)).isEmpty()
            assertThat(api.http.get("/live").statusCode()).describedAs("an api process answers its probes").isEqualTo(200)
            assertThat(api.http.get("/ready").statusCode()).isEqualTo(200)
            assertThat(recurringTasks()).describedAs("no scheduler registered recurring work").isZero()

            SampleProcess.worker(database).use { worker ->
                assertThat(worker.http.get("/v1/tickets", supervisor).let { it.statusCode() to it.code() }).isEqualTo(404 to "not_found")
                assertThat(
                    worker.http.send("POST", Staff.AGENT_LOGIN, Staff.credentials(SUPERVISOR), "Rain-Auth-Delivery" to "body").statusCode(),
                ).isEqualTo(404)
                assertThat(worker.http.get("/live").statusCode()).isEqualTo(200)
                assertThat(
                    worker.context.getBeansOfType(HealthCheck::class.java).values.map {
                        it.name
                    },
                ).contains("jobs").doesNotContain("realtime.listener")
                assertThat(
                    worker.context
                        .getBeansOfType(RecurringWork::class.java)
                        .values
                        .map { it.name },
                ).containsExactlyInAnyOrder("ticket.escalation-sweep", "access.session-retention", "access.revocation-replay")

                Awaits.until("the worker to run the summary the api process ordered") {
                    jdbc.queryForObject("SELECT state FROM rain_jobs.job_invocation WHERE id = ?", String::class.java, invocation) ==
                        "succeeded"
                }
                Awaits.until("the worker to register its recurring work") { recurringTasks() == 1L }
                Awaits.until("the worker to be ready") { worker.http.get("/ready").statusCode() == 200 }
            }
        }
    }

    private companion object {
        const val SUPERVISOR = "gating-supervisor@helpdesk.example"
    }
}
