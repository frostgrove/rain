package com.gd.rain.jobs.internal.execution

import com.gd.rain.jobs.internal.ledger.AttemptLedger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What one renewal round did. */
internal data class RenewalReport(
    val renewed: Set<UUID>,
    val busy: Set<UUID>,
    val lost: Set<UUID>,
    /** Leases revoked because no answer came before their horizon (last renewal + ttl). */
    val expired: Set<UUID>,
    val failure: Throwable?,
)

/**
 * Renews every lease this process holds, in one statement per round, while their attempts run — including in the
 * middle of a long step, which no per-step heartbeat can do.
 *
 * - A lease the database says is gone (token replaced, invocation no longer running) is revoked at once: the
 *   attempt is interrupted and ends uncharged.
 * - A lease whose row another transaction has locked (a fenced effect in flight) is left for the next round.
 * - A round that got no answer at all (the connection failed) revokes only leases whose horizon — last successful
 *   renewal plus the ttl — has passed; one dropped packet does not revoke live work.
 */
internal class LeaseRenewer(
    private val ledger: AttemptLedger,
    private val clock: Clock,
    private val ttl: Duration,
) {
    private class Held(
        val control: AttemptControl,
        @Volatile var lastRenewed: Instant,
    )

    private val held = ConcurrentHashMap<UUID, Held>()

    @Volatile
    var lastRound: Instant? = null
        private set

    fun register(
        control: AttemptControl,
        claimedAt: Instant,
    ) {
        check(held.putIfAbsent(control.lease.invocation, Held(control, claimedAt)) == null) {
            "invocation ${control.lease.invocation} is already leased by this process"
        }
    }

    fun unregister(control: AttemptControl) {
        held.computeIfPresent(control.lease.invocation) { _, lease -> if (lease.control === control) null else lease }
    }

    fun activeLeases(): Int = held.size

    fun renewOnce(): RenewalReport {
        val round = held.values.toList()
        val now = clock.instant()
        lastRound = now
        if (round.isEmpty()) return RenewalReport(emptySet(), emptySet(), emptySet(), emptySet(), null)
        val answer =
            try {
                ledger.renew(round.map { it.control.lease }, now.plus(ttl))
            } catch (failure: RuntimeException) {
                log.warn("lease renewal got no answer for {} leases: {}", round.size, failure.toString())
                val expired = round.filterNot { now.isBefore(it.lastRenewed.plus(ttl)) }
                expired.forEach { revoke(it) }
                return RenewalReport(emptySet(), emptySet(), emptySet(), expired.map { it.control.lease.invocation }.toSet(), failure)
            }
        val lost = mutableSetOf<UUID>()
        round.forEach { lease ->
            val id = lease.control.lease.invocation
            if (id in answer.renewed) {
                lease.lastRenewed = now
            } else if (id !in answer.busy) {
                lost += id
                revoke(lease)
            }
        }
        return RenewalReport(answer.renewed, answer.busy, lost, emptySet(), null)
    }

    private fun revoke(lease: Held) {
        held.remove(lease.control.lease.invocation, lease)
        if (lease.control.revoke(
                Revocation.LEASE_LOST,
            )
        ) {
            log.info("lease on invocation {} is lost; its attempt is interrupted", lease.control.lease.invocation)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LeaseRenewer::class.java)
    }
}
