package com.gd.rain.observability.health

import java.time.Duration
import java.time.Instant

/**
 * How much a failing check matters.
 *
 * The composition root decides it, never the checker: the same ping is required where every request
 * needs the dependency and degrading where the dependency only makes answers better, and only the
 * application being assembled knows which.
 */
public enum class Importance(
    public val wire: String,
) {
    /** No request can be served without it: failing makes the process not ready (503). */
    REQUIRED("required"),

    /** The process is worse than it was and still worth traffic (200, `degraded`). */
    DEGRADING("degrading"),

    /** Reported in the detail, never counted in readiness. */
    INFORMATIONAL("informational"),

    /** Registered and never probed. */
    DISABLED("disabled"),
    ;

    public companion object {
        public val wireNames: List<String> = entries.map(Importance::wire)
    }
}

/** What one probe of one check found. */
public enum class CheckState(
    public val wire: String,
) {
    PASSING("passing"),
    FAILING("failing"),
    DISABLED("disabled"),
}

/**
 * The readiness contract: the status a load balancer reads and the HTTP status it is served with.
 *
 * `ready` and `degraded` keep the process in the rotation (200); `not_ready` and `draining` take it
 * out (503). `draining` is reported from the moment the application context starts closing, without
 * asking any dependency.
 */
public enum class ReadinessStatus(
    public val wire: String,
    public val httpStatus: Int,
) {
    READY("ready", 200),
    DEGRADED("degraded", 200),
    NOT_READY("not_ready", 503),
    DRAINING("draining", 503),
}

/**
 * The public readiness answer: a status and the sorted public codes of the failing REQUIRED and
 * DEGRADING checks. Nothing else reaches an unauthenticated caller — failure messages stay in
 * [HealthDetail], which is logged and never served.
 */
public data class ReadinessReport(
    public val status: ReadinessStatus,
    public val failing: List<String>,
) {
    init {
        require(failing.zipWithNext().all { (first, second) -> first < second }) { "failing codes are sorted and distinct, got $failing" }
        require(status != ReadinessStatus.READY || failing.isEmpty()) { "a ready report names no failing check" }
        require(status != ReadinessStatus.DRAINING || failing.isEmpty()) { "a draining report asks nobody, so it names no failing check" }
    }
}

/** The liveness answer: the process is running. It is constant, always 200, and asks nobody. */
public data object LivenessReport {
    public const val STATUS: String = "live"
    public const val HTTP_STATUS: Int = 200

    public val status: String get() = STATUS
}

/** One check's reading inside one evaluation. */
public data class CheckDetail(
    public val name: String,
    public val code: String?,
    public val importance: Importance,
    public val state: CheckState,
    public val message: String?,
    public val took: Duration,
)

/**
 * The private view of one evaluation: every check with its state and message. It is logged, never
 * served.
 *
 * Readings are indexed by name once, when the evaluation is built, so a reader that asks about one
 * check — an Actuator indicator per contribution — costs a map lookup and never a pass over [checks].
 */
public class HealthDetail(
    public val status: ReadinessStatus,
    public val observedAt: Instant,
    public val checks: List<CheckDetail>,
) {
    private val byName: Map<String, CheckDetail> = checks.associateBy(CheckDetail::name)

    init {
        require(byName.size == checks.size) { "an evaluation holds one reading per check name" }
    }

    /** The reading of the check named [name], or null when this evaluation holds no such check. */
    public fun reading(name: String): CheckDetail? = byName[name]
}

/**
 * One thing this process asks a dependency, and how much the answer matters.
 *
 * [probe] signals a failure by throwing; the message reaches the detail and the log, the [code]
 * reaches the public report.
 */
public interface HealthContribution {
    /** Unique, matching [HealthRegistry.NAME_PATTERN]; the name Actuator publishes the reading under. */
    public val name: String

    /** The public code, matching [HealthRegistry.CODE_PATTERN]; null keeps the check out of `failing` while it still moves the status. */
    public val code: String?

    public val importance: Importance

    /** This check's budget; null means the registry's `rain.health.check-timeout`. */
    public val timeout: Duration?

    public fun probe()
}

/** Where the latest evaluation is read from. */
public fun interface HealthReadings {
    public fun inspect(): HealthDetail
}
