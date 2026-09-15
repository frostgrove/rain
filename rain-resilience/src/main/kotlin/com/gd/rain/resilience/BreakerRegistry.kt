package com.gd.rain.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * The declared breakers of this process, over the application context's Resilience4j
 * [CircuitBreakerRegistry].
 *
 * **Resilience4j owns** the state machine, the sliding window, the permits and the open wait. A breaker
 * is looked up with `find` and never created here: a declared breaker without an explicit
 * `resilience4j.circuitbreaker.instances.<name>` entry has no instance, so it can never fall back to the
 * library's default configuration ([BreakerConfigurationCheck] refuses start-up instead).
 *
 * **This class owns** the reservation: [reserve] decides admission and takes the permission in one call
 * to the breaker's atomic `tryAcquirePermission`, so no reading of the state can be acted on after it
 * went stale. It also records what Resilience4j has no word for: when the episode began and the last
 * failure's reason.
 *
 * **Time** for a breaker comes from its own Resilience4j configuration's clock — the clock its open wait
 * is measured on — so what this class reports never disagrees with what the breaker decides.
 */
public class BreakerRegistry(
    circuitBreakers: CircuitBreakerRegistry,
    declarations: List<BreakerDeclaration>,
) {
    private val declared: Map<BreakerName, List<BreakerDeclaration>> = declarations.groupBy(BreakerDeclaration::name)

    private val ledgers: Map<BreakerName, BreakerLedger> =
        declared
            .filterValues { it.size == 1 }
            .keys
            .mapNotNull { name -> circuitBreakers.find(name.value).orElse(null)?.let { name to BreakerLedger(name, it) } }
            .toMap()

    /** Every declared breaker, ordered by name. */
    public fun names(): List<BreakerName> = declared.keys.sortedBy(BreakerName::value)

    public fun state(name: BreakerName): BreakerState = ledger(name).state()

    /** Decides admission and takes the permission atomically. */
    public fun reserve(name: BreakerName): Reservation = ledger(name).reserve()

    /** The dependency answered the call [permit] admitted. */
    public fun succeeded(permit: Reservation.Admitted) {
        owned(permit).succeeded(permit)
    }

    /**
     * The call [permit] admitted failed **in the dependency itself**. A refusal the dependency issued
     * deliberately is an answer, and is reported with [succeeded]; work abandoned before the call is not
     * reported at all.
     */
    public fun failed(
        permit: Reservation.Admitted,
        cause: Throwable,
    ) {
        owned(permit).failed(permit, cause)
    }

    /** The breaker's open wait: how long it withholds calls after it opens. */
    public fun openWait(name: BreakerName): Duration = ledger(name).openWait

    internal fun ledger(name: BreakerName): BreakerLedger {
        ledgers[name]?.let { return it }
        val declarations = declared[name] ?: throw IllegalArgumentException("no breaker \"$name\" is declared; declared: ${names()}")
        check(declarations.size == 1) { "breaker \"$name\" is declared ${declarations.size} times; start-up refuses that" }
        throw IllegalStateException(
            "breaker \"$name\" has no resilience4j.circuitbreaker.instances.$name configuration; start-up refuses that",
        )
    }

    private fun owned(permit: Reservation.Admitted): BreakerLedger {
        val ledger = ledger(permit.breaker)
        require(ledger === permit.ledger) { "the reservation for \"${permit.breaker}\" was not made by this registry" }
        return ledger
    }
}

/** One breaker's bookkeeping next to its Resilience4j instance. Every field is atomic; no lock is held while the breaker runs. */
internal class BreakerLedger(
    val name: BreakerName,
    val breaker: CircuitBreaker,
) {
    val clock: Clock = breaker.circuitBreakerConfig.clock

    /**
     * The open wait. [BreakerConfigurationCheck] refuses exponential and randomized waits for a declared
     * breaker, so the interval function is constant and its first value is every value.
     */
    val openWait: Duration = Duration.ofMillis(breaker.circuitBreakerConfig.waitIntervalFunctionInOpenState.apply(1))

    private val since = AtomicReference<Instant?>(null)
    private val reason = AtomicReference<String?>(null)

    /** When the breaker last transitioned to OPEN, as published; null while it is in any other state. */
    private val openedAt = AtomicReference<Instant?>(null)

    init {
        breaker.eventPublisher.onStateTransition { event -> transitioned(event.stateTransition.toState) }
        breaker.eventPublisher.onReset { transitioned(breaker.state) }
    }

    fun state(): BreakerState = BreakerState(name, breaker.state, since.get(), reason.get())

    fun reserve(): Reservation {
        if (breaker.tryAcquirePermission()) {
            // Read after the permission was taken: a permit taken while passing is an ordinary call; any
            // other reading makes it a probe, which is the conservative side for the one-probe rule.
            val probe = breaker.state !in BreakerState.PASSING
            return Reservation.Admitted(this, probe, breaker.currentTimestamp)
        }
        return refusal()
    }

    /**
     * Why a permission was refused, and when to come back: the rest of the open wait when the breaker
     * reads open and its opening was published with time left; one full open wait otherwise (a probe is
     * in flight, or the opening is not known) — an answer that never sends the caller back before the
     * breaker could admit it.
     */
    fun refusal(): Reservation =
        when (breaker.state) {
            CircuitBreaker.State.FORCED_OPEN -> Reservation.ForcedOpen
            else -> Reservation.Held(remainingOpenWait()?.takeIf(::positive) ?: openWait)
        }

    /** The rest of the open wait from the published opening; null when the breaker is not known to be open. */
    fun remainingOpenWait(): Duration? {
        val opened = openedAt.get() ?: return null
        if (breaker.state != CircuitBreaker.State.OPEN) return null
        return Duration.between(clock.instant(), opened.plus(openWait))
    }

    fun succeeded(permit: Reservation.Admitted) {
        check(permit.report()) { "an outcome for this \"$name\" reservation was already recorded, or it was closed" }
        breaker.onSuccess(breaker.currentTimestamp - permit.startedAt, breaker.timestampUnit)
    }

    fun failed(
        permit: Reservation.Admitted,
        cause: Throwable,
    ) {
        check(permit.report()) { "an outcome for this \"$name\" reservation was already recorded, or it was closed" }
        reason.set(cause.message?.takeIf(String::isNotBlank) ?: cause.javaClass.name)
        breaker.onError(breaker.currentTimestamp - permit.startedAt, breaker.timestampUnit, cause)
    }

    fun release() {
        breaker.releasePermission()
    }

    private fun transitioned(to: CircuitBreaker.State) {
        val now = clock.instant()
        openedAt.set(if (to == CircuitBreaker.State.OPEN) now else null)
        if (to in BreakerState.PASSING) {
            since.set(null)
            reason.set(null)
        } else {
            since.compareAndSet(null, now)
        }
    }

    private fun positive(duration: Duration): Boolean = !duration.isZero && !duration.isNegative
}
