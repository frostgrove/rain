package com.gd.rain.resilience

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How much work may start against a dependency that may not be answering.
 *
 * A breaker alone does not stop a queue: twelve workers polling a dead dependency would each spend an
 * attempt per poll. The gate keeps the rule that makes an outage cheap — **one probe per cooldown**,
 * where the cooldown is the breaker's open wait:
 *
 * - at most one probe of this process is in flight per breaker, even across cooldowns;
 * - a probe handed out at `t` is the only one until `t + openWait`, even when it was given back unused.
 *
 * [reserve] takes its decision from [BreakerRegistry.reserve], which decides and takes the permission
 * atomically; the gate never admits on a reading it made earlier. A permission the gate refuses is given
 * back to the breaker at once.
 */
public class AdmissionGate(
    private val breakers: BreakerRegistry,
) {
    private val slots: Map<BreakerName, ProbeSlot> = breakers.names().associateWith { ProbeSlot() }

    /** What the gate would answer now, without taking anything — for a dispatcher deciding what to claim, or a report. */
    public fun admission(name: BreakerName): AdmissionState {
        val ledger = breakers.ledger(name)
        val slot = slots.getValue(name)
        return when (ledger.breaker.state) {
            CircuitBreaker.State.CLOSED, CircuitBreaker.State.DISABLED, CircuitBreaker.State.METRICS_ONLY -> {
                AdmissionState.Unrestricted
            }

            CircuitBreaker.State.FORCED_OPEN -> {
                AdmissionState.ForcedOpen
            }

            CircuitBreaker.State.OPEN -> {
                val remaining = ledger.remainingOpenWait()
                when {
                    remaining == null -> AdmissionState.Held(ledger.openWait)
                    positive(remaining) -> AdmissionState.Held(remaining)
                    else -> probeView(ledger, slot)
                }
            }

            CircuitBreaker.State.HALF_OPEN -> {
                probeView(ledger, slot)
            }
        }
    }

    /**
     * Takes the right to make one call, or answers why not.
     *
     * A probe is admitted only when no other probe of this process is in flight and the last probe was
     * handed out at least one open wait ago; otherwise its permission is given back and the caller is
     * held until that wait has passed.
     */
    public fun reserve(name: BreakerName): Reservation {
        val reservation = breakers.reserve(name)
        if (reservation !is Reservation.Admitted || !reservation.probe) return reservation
        val slot = slots.getValue(name)
        val ledger = reservation.ledger
        if (!slot.inFlight.compareAndSet(false, true)) {
            reservation.close()
            return Reservation.Held(ledger.openWait)
        }
        val now = ledger.clock.instant()
        val next = slot.lastHandedOut?.plus(ledger.openWait)
        if (next != null && now.isBefore(next)) {
            slot.inFlight.set(false)
            reservation.close()
            return Reservation.Held(Duration.between(now, next))
        }
        slot.lastHandedOut = now
        reservation.onSettled { slot.inFlight.set(false) }
        return reservation
    }

    private fun probeView(
        ledger: BreakerLedger,
        slot: ProbeSlot,
    ): AdmissionState {
        if (slot.inFlight.get()) return AdmissionState.Held(ledger.openWait)
        val next = slot.lastHandedOut?.plus(ledger.openWait) ?: return AdmissionState.Probing
        val remaining = Duration.between(ledger.clock.instant(), next)
        return if (positive(remaining)) AdmissionState.Held(remaining) else AdmissionState.Probing
    }

    private fun positive(duration: Duration): Boolean = !duration.isZero && !duration.isNegative

    /** This process's probe of one breaker: whether one is in flight, and when the last one was handed out. */
    private class ProbeSlot {
        val inFlight = AtomicBoolean(false)

        /** Written only by the holder of [inFlight]. */
        @Volatile
        var lastHandedOut: Instant? = null
    }
}
