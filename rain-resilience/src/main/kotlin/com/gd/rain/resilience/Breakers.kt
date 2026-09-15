package com.gd.rain.resilience

import com.gd.rain.observability.health.Importance
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The name of a breaker: the key of its `resilience4j.circuitbreaker.instances.<name>` configuration and
 * of its instance in the container's `CircuitBreakerRegistry`.
 */
@JvmInline
public value class BreakerName(
    public val value: String,
) {
    init {
        require(FORMAT.matches(value)) { "breaker name \"$value\" does not match $PATTERN" }
    }

    override fun toString(): String = value

    public companion object {
        /** Lower case, digits, `_`, `.` and `-`, starting with a letter, at most 64 characters. */
        public const val PATTERN: String = "^[a-z][a-z0-9_.-]{0,63}$"

        private val FORMAT = Regex(PATTERN)

        public fun isWellFormed(value: String): Boolean = FORMAT.matches(value)
    }
}

/**
 * A breaker this application uses, declared as a bean.
 *
 * The declaration is the composition root's statement of how much the dependency matters: [importance]
 * and [healthCode] decide what readiness reports while the breaker withholds calls. How the breaker
 * trips is stated in `resilience4j.circuitbreaker.instances.<name>`, and a declaration without that
 * entry refuses start-up.
 */
public data class BreakerDeclaration(
    public val name: BreakerName,
    /** The public readiness code while the breaker withholds calls; null keeps it out of `failing`. */
    public val healthCode: String?,
    public val importance: Importance,
)

/**
 * What a breaker says about itself.
 *
 * The state lives in this process's memory: two processes trip independently and neither knows about
 * the other's failures.
 */
public data class BreakerState(
    public val name: BreakerName,
    public val state: CircuitBreaker.State,
    /** When the breaker last left a passing state; a failed probe extends the episode. Null while passing. */
    public val since: Instant?,
    /** The last failure recorded through [BreakerRegistry.failed] since the breaker last closed. */
    public val reason: String?,
) {
    /** Whether calls are being withheld: open, half-open or forced open. */
    public val withholding: Boolean get() = state !in PASSING

    public companion object {
        /** The states in which every call is permitted. */
        public val PASSING: Set<CircuitBreaker.State> =
            setOf(CircuitBreaker.State.CLOSED, CircuitBreaker.State.DISABLED, CircuitBreaker.State.METRICS_ONLY)
    }
}

/** The outcome of asking for the right to make one call. */
public sealed interface Reservation {
    /**
     * The permission to make one call, taken from the breaker when this reservation was made.
     *
     * Report the call's outcome with [BreakerRegistry.succeeded] or [BreakerRegistry.failed], and close
     * the reservation when the attempt ends (`use { }`). Closing a reservation whose call has no outcome
     * — the work was abandoned before the dependency was asked — gives a probe's permission back and
     * records nothing against the dependency. An outcome is recorded at most once; an outcome after
     * close is refused.
     */
    public class Admitted internal constructor(
        internal val ledger: BreakerLedger,
        /** Taken while the breaker was not passing: the call is a probe of whether the dependency is back. */
        internal val probe: Boolean,
        internal val startedAt: Long,
    ) : Reservation,
        AutoCloseable {
        private val phase = AtomicInteger(HELD)
        private val settled = CopyOnWriteArrayList<() -> Unit>()

        public val breaker: BreakerName get() = ledger.name

        /** Marks the outcome recorded; false when one already was or the reservation is closed. */
        internal fun report(): Boolean {
            if (!phase.compareAndSet(HELD, REPORTED)) return false
            settled.forEach { it() }
            return true
        }

        /** Runs [hook] once, when the reservation leaves the held phase by an outcome or by close. */
        internal fun onSettled(hook: () -> Unit) {
            settled += hook
        }

        override fun close() {
            if (phase.getAndSet(DONE) != HELD) return
            if (probe) ledger.release()
            settled.forEach { it() }
        }

        private companion object {
            const val HELD = 0
            const val REPORTED = 1
            const val DONE = 2
        }
    }

    /** The breaker withholds calls; [retryAfter] is when admission can next change without an outcome. */
    public data class Held(
        public val retryAfter: Duration,
    ) : Reservation

    /** The breaker was forced open; no instant at which it admits calls again is scheduled. */
    public data object ForcedOpen : Reservation
}

/** How much work may start against a dependency, read without taking anything. */
public sealed interface AdmissionState {
    /** Every call is permitted; the caller's own capacity is the only limit. */
    public data object Unrestricted : AdmissionState

    /** One probe may be reserved now, to find out whether the dependency is back. */
    public data object Probing : AdmissionState

    /** Nothing may start until [retryAfter] has passed. */
    public data class Held(
        public val retryAfter: Duration,
    ) : AdmissionState

    /** Forced open; no instant is scheduled. */
    public data object ForcedOpen : AdmissionState
}
