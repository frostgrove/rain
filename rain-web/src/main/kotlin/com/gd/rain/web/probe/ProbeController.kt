package com.gd.rain.web.probe

import com.gd.rain.observability.health.CheckState
import com.gd.rain.observability.health.HealthRegistry
import com.gd.rain.observability.health.LivenessReport
import com.gd.rain.observability.health.ReadinessStatus
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.json.JsonMapper

/** A probe's answer: its HTTP status and its JSON body. */
public class ProbeAnswer(
    public val status: Int,
    public val body: ByteArray,
)

/**
 * Liveness and readiness over HTTP, at `rain.web.probes.live-path` and `rain.web.probes.ready-path`.
 *
 * `live` is `{"status":"live"}`, always 200, asking nobody. `ready` is
 * `{"status":"ready|degraded|not_ready|draining","failing":[codes]}` with 200 for ready and degraded
 * and 503 otherwise. The content type is set on the answer itself, so no `Accept` header can turn a
 * probe into a 406: an orchestrator, a load balancer and a browser read the same bytes.
 *
 * Routes are functional because their paths are configuration, not annotations. A readiness answer that
 * is not `ready` logs every failing check with its message, which the public body never carries.
 */
public class ProbeController(
    private val registry: HealthRegistry,
) {
    public fun live(): ProbeAnswer =
        ProbeAnswer(
            LivenessReport.HTTP_STATUS,
            json.writeValueAsBytes(
                linkedMapOf("status" to LivenessReport.STATUS),
            ),
        )

    public fun ready(): ProbeAnswer {
        val report = registry.ready()
        if (report.status == ReadinessStatus.NOT_READY || report.status == ReadinessStatus.DEGRADED) logFailing()
        val body = linkedMapOf<String, Any>("status" to report.status.wire, "failing" to report.failing)
        return ProbeAnswer(report.status.httpStatus, json.writeValueAsBytes(body))
    }

    public fun routes(
        livePath: String,
        readyPath: String,
    ): RouterFunction<ServerResponse> =
        RouterFunctions
            .route()
            .GET(livePath) { respond(live()) }
            .GET(readyPath) { respond(ready()) }
            .build()

    private fun respond(answer: ProbeAnswer): ServerResponse =
        ServerResponse
            .status(answer.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(answer.body)

    private fun logFailing() {
        registry.inspect().checks.filter { it.state == CheckState.FAILING }.forEach { check ->
            log
                .atWarn()
                .setMessage("a readiness check failed")
                .addKeyValue("check", check.name)
                .addKeyValue("importance", check.importance.wire)
                .addKeyValue("error", check.message)
                .log()
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ProbeController::class.java)
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
