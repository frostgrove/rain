package com.gd.rain.observability.health

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What this process answers liveness and readiness from.
 *
 * The registry, not Actuator, is the engine: the checks of one evaluation run concurrently on virtual
 * threads, each against an absolute budget counted from the start of the evaluation, and one
 * evaluation is shared per freshness window ([HealthCache]). The Actuator bridge publishes the same
 * readings, so there is one evaluation and two views of it.
 *
 * Every registration problem — a malformed or duplicate name, a malformed or duplicate code, a budget
 * that is not positive — is reported at once, and a registry with any of them is not built.
 *
 * Once [startDraining] is called (the application context began closing) [ready] answers `draining`
 * without asking any dependency.
 */
public class HealthRegistry(
    contributions: List<HealthContribution>,
    private val checkTimeout: Duration,
    freshness: Duration,
    private val clock: Clock,
) : HealthReadings,
    AutoCloseable {
    private val accepted: List<HealthContribution> = accept(contributions, checkTimeout, freshness)

    private val draining = AtomicBoolean(false)

    private val probes: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    private val cache = HealthCache(freshness, clock) { evaluate() }

    /** Whether the process is running. Constant; asks nobody. */
    public fun live(): LivenessReport = LivenessReport

    public fun ready(): ReadinessReport = if (draining.get()) DRAINING_REPORT else cache.get().report

    /** The detail of the current freshness window's evaluation — the same evaluation [ready] reads. */
    override fun inspect(): HealthDetail = cache.get().detail

    /** The accepted contributions, sorted by name. */
    public fun contributions(): List<HealthContribution> = accepted

    public val isDraining: Boolean get() = draining.get()

    /** From now on readiness is `draining`. Irreversible: a closing context does not reopen. */
    public fun startDraining() {
        draining.set(true)
    }

    override fun close() {
        probes.shutdownNow()
    }

    private fun evaluate(): Evaluation {
        val startedAt = System.nanoTime()
        val started =
            accepted.map { contribution ->
                if (contribution.importance == Importance.DISABLED) null else probes.submit<CheckDetail> { measured(contribution) }
            }
        val details =
            accepted.mapIndexed { position, contribution ->
                val probe = started[position]
                if (probe == null) disabled(contribution) else collect(contribution, probe, startedAt)
            }
        return evaluation(details)
    }

    /**
     * Waits for one probe until its absolute deadline: the budget is counted from the start of the
     * evaluation, not from when this check is waited for, because every probe started together.
     *
     * An interrupt of the evaluating thread does not cut the evaluation short: other callers share
     * this flight, and a reading made of interrupted waits would be served to all of them for a whole
     * freshness window. The wait continues within the same deadline and the interrupt is restored.
     */
    private fun collect(
        contribution: HealthContribution,
        probe: Future<CheckDetail>,
        startedAt: Long,
    ): CheckDetail {
        val budget = contribution.timeout ?: checkTimeout
        val deadline = startedAt + budget.toNanos()
        var interrupted = false
        try {
            while (true) {
                try {
                    return probe.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (_: TimeoutException) {
                    probe.cancel(true)
                    return failing(contribution, "the check did not answer within ${budget.toMillis()}ms", budget)
                } catch (failure: ExecutionException) {
                    // `measured` catches everything a probe throws, so this is the task itself failing.
                    return failing(contribution, describe(failure.cause ?: failure), Duration.ofNanos(System.nanoTime() - startedAt))
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Every throwable a probe raises is a failing check; a probe never takes the process down. */
    @Suppress("TooGenericExceptionCaught")
    private fun measured(contribution: HealthContribution): CheckDetail {
        val startedAt = System.nanoTime()
        return try {
            contribution.probe()
            detail(contribution, CheckState.PASSING, null, Duration.ofNanos(System.nanoTime() - startedAt))
        } catch (failure: Throwable) {
            failing(contribution, describe(failure), Duration.ofNanos(System.nanoTime() - startedAt))
        }
    }

    private fun failing(
        contribution: HealthContribution,
        message: String,
        took: Duration,
    ): CheckDetail = detail(contribution, CheckState.FAILING, truncate(message), took)

    private fun disabled(contribution: HealthContribution): CheckDetail = detail(contribution, CheckState.DISABLED, null, Duration.ZERO)

    private fun detail(
        contribution: HealthContribution,
        state: CheckState,
        message: String?,
        took: Duration,
    ): CheckDetail = CheckDetail(contribution.name, contribution.code, contribution.importance, state, message, took)

    private fun evaluation(details: List<CheckDetail>): Evaluation {
        val counted =
            details.filter {
                it.state == CheckState.FAILING && (it.importance == Importance.REQUIRED || it.importance == Importance.DEGRADING)
            }
        val status =
            when {
                counted.any { it.importance == Importance.REQUIRED } -> ReadinessStatus.NOT_READY
                counted.isNotEmpty() -> ReadinessStatus.DEGRADED
                else -> ReadinessStatus.READY
            }
        return Evaluation(
            report = ReadinessReport(status, counted.mapNotNull(CheckDetail::code).sorted()),
            detail = HealthDetail(status, clock.instant(), details),
        )
    }

    private data class Evaluation(
        val report: ReadinessReport,
        val detail: HealthDetail,
    )

    public companion object {
        /** A contribution name: lower case, digits, `.`, `_` and `-`, starting with a letter, at most 128 characters. */
        public const val NAME_PATTERN: String = "^[a-z][a-z0-9._-]{0,127}$"

        /** A public code: the same alphabet, at most 64 characters. */
        public const val CODE_PATTERN: String = "^[a-z][a-z0-9._-]{0,63}$"

        /** A failure message in the detail is cut at this many UTF-8 bytes, on a character boundary. */
        public const val MAX_MESSAGE_BYTES: Int = 256

        private val NAME = Regex(NAME_PATTERN)
        private val CODE = Regex(CODE_PATTERN)
        private val DRAINING_REPORT = ReadinessReport(ReadinessStatus.DRAINING, emptyList())

        /**
         * The contributions sorted by name, or a refusal naming every problem found. Nothing is dropped
         * or renamed to make a registration fit.
         */
        public fun accept(
            contributions: List<HealthContribution>,
            checkTimeout: Duration,
            freshness: Duration,
        ): List<HealthContribution> {
            val problems = mutableListOf<ConfigurationProblem>()
            if (!positive(checkTimeout)) {
                problems +=
                    ConfigurationProblem(HealthProperties.CHECK_TIMEOUT, ProblemCode.INVALID, "is $checkTimeout; it has to be positive")
            }
            if (!positive(freshness)) {
                problems += ConfigurationProblem(HealthProperties.FRESHNESS, ProblemCode.INVALID, "is $freshness; it has to be positive")
            }

            val names = mutableMapOf<String, HealthContribution>()
            val codes = mutableMapOf<String, String>()
            contributions.forEachIndexed { position, contribution ->
                val where = "health:" + contribution.name.ifEmpty { "#$position" }
                if (!NAME.matches(contribution.name)) {
                    problems +=
                        ConfigurationProblem(
                            where,
                            ProblemCode.INVALID,
                            "${contribution.javaClass.name} is named \"${contribution.name}\", which does not match $NAME_PATTERN",
                        )
                }
                names.put(contribution.name, contribution)?.let { first ->
                    problems +=
                        ConfigurationProblem(
                            where,
                            ProblemCode.CONTRADICTS,
                            "is contributed by ${first.javaClass.name} and ${contribution.javaClass.name}",
                        )
                }
                contribution.timeout?.let { timeout ->
                    if (!positive(timeout)) {
                        problems +=
                            ConfigurationProblem(where, ProblemCode.INVALID, "has a timeout of $timeout; it has to be positive")
                    }
                }
                contribution.code?.let { code ->
                    if (!CODE.matches(code)) {
                        problems +=
                            ConfigurationProblem(where, ProblemCode.INVALID, "publishes code \"$code\", which does not match $CODE_PATTERN")
                    }
                    codes.put(code, contribution.name)?.let { owner ->
                        problems +=
                            ConfigurationProblem(where, ProblemCode.CONTRADICTS, "publishes code \"$code\", which $owner already publishes")
                    }
                }
            }

            if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
            return contributions.sortedBy(HealthContribution::name)
        }

        /** [message] cut at [MAX_MESSAGE_BYTES] of UTF-8, on a character boundary. */
        public fun truncate(message: String): String {
            val bytes = message.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_MESSAGE_BYTES) return message
            var cut = MAX_MESSAGE_BYTES
            while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            return String(bytes, 0, cut, Charsets.UTF_8)
        }

        private fun positive(duration: Duration): Boolean = !duration.isZero && !duration.isNegative

        private fun describe(failure: Throwable): String = failure.message?.ifEmpty { null } ?: failure.javaClass.name
    }
}
