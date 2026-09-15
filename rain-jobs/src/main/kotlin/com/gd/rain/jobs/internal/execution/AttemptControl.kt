package com.gd.rain.jobs.internal.execution

import com.gd.rain.jobs.AttemptInterruptedException
import com.gd.rain.jobs.AttemptTimeoutException
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.LeaseLostException
import com.gd.rain.jobs.internal.ledger.LeaseRef
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Why an attempt was stopped from outside its body. The first revocation wins and decides the outcome. */
internal enum class Revocation {
    /** The renewer learned the lease is gone, or could not renew it before its horizon passed. */
    LEASE_LOST,

    /** The scheduler thread waiting for the attempt was interrupted: the process is stopping. */
    SHUTDOWN,

    /** The attempt's budget ran out while its body was still running. */
    TIMEOUT,
}

/** How an attempt body ended, as its own thread recorded it. */
internal sealed interface BodyResult {
    data object Returned : BodyResult

    data class Threw(
        val failure: Throwable,
    ) : BodyResult

    data class PayloadUnreadable(
        val failure: Throwable,
    ) : BodyResult
}

/**
 * The shared state of one attempt between the scheduler thread that waits for it, the attempt's own thread, and
 * the lease renewer. Every transition is synchronized, so a revocation and the attempt thread binding cannot miss
 * each other.
 */
internal class AttemptControl(
    val lease: LeaseRef,
    val profile: JobProfile,
    val deadline: Instant,
    private val wedged: AtomicInteger,
) {
    @Volatile
    var revocation: Revocation? = null
        private set

    @Volatile
    var result: BodyResult? = null
        private set

    @Volatile
    var boundFailure: Throwable? = null
        private set

    /** The explicit step budget in force on the attempt thread, or null outside a step. */
    @Volatile
    var stepBudget: Duration? = null

    private var thread: Thread? = null
    private var exited = false
    private var abandoned = false

    @Synchronized
    fun bind(attemptThread: Thread) {
        thread = attemptThread
        if (revocation != null) attemptThread.interrupt()
    }

    /** Records [reason] unless another revocation came first, and interrupts the attempt thread. */
    @Synchronized
    fun revoke(reason: Revocation): Boolean {
        if (revocation != null) return false
        revocation = reason
        thread?.interrupt()
        return true
    }

    /** The scheduler thread stops waiting; until the body exits, the attempt counts as wedged. */
    @Synchronized
    fun abandon() {
        if (exited || abandoned) return
        abandoned = true
        wedged.incrementAndGet()
    }

    @Synchronized
    fun finish(outcome: BodyResult) {
        if (result == null) result = outcome
    }

    @Synchronized
    fun exited() {
        exited = true
        thread = null
        if (abandoned) wedged.decrementAndGet()
    }

    fun recordBoundFailure(failure: Throwable) {
        if (boundFailure == null) boundFailure = failure
    }

    /** Throws instead of letting more work start once the attempt is revoked or out of budget. */
    fun checkRunnable(clock: Clock) {
        when (revocation) {
            Revocation.LEASE_LOST -> throw LeaseLostException(lease.invocation)
            Revocation.SHUTDOWN -> throw AttemptInterruptedException(lease.invocation)
            Revocation.TIMEOUT -> throw AttemptTimeoutException(profile.attemptTimeout)
            null -> Unit
        }
        if (!clock.instant().isBefore(deadline)) throw AttemptTimeoutException(profile.attemptTimeout)
    }

    /**
     * The bound every statement of a transaction begun now carries: the step budget (or the profile's step timeout
     * outside a step) capped by what is left of the attempt. PostgreSQL reads a `statement_timeout` of zero as "no
     * limit", so an exhausted remainder is one millisecond, never zero.
     */
    fun statementBound(clock: Clock): Duration {
        val remaining = Duration.between(clock.instant(), deadline)
        val cap = stepBudget ?: profile.stepTimeout
        val bound = if (remaining < cap) remaining else cap
        return if (bound < SMALLEST_BOUND) SMALLEST_BOUND else bound
    }

    internal companion object {
        val SMALLEST_BOUND: Duration = Duration.ofMillis(1)
    }
}

/** The attempt the current thread is running, visible to code several frames down (a transaction listener). */
internal object AttemptScope {
    private val current = ThreadLocal<AttemptControl>()

    fun current(): AttemptControl? = current.get()

    fun <T> within(
        control: AttemptControl,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(control)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
