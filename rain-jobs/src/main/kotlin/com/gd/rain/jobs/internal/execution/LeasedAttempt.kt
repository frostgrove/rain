package com.gd.rain.jobs.internal.execution

import com.gd.rain.core.lock.Guard
import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.AttemptMeta
import com.gd.rain.jobs.LeaseLostException
import com.gd.rain.jobs.internal.ledger.AttemptLedger
import com.gd.rain.persistence.lock.AdvisoryLockStore
import com.gd.rain.persistence.lock.AdvisoryLocks
import java.time.Clock
import java.time.Duration

/** Runs an effect only while an attempt owns its invocation. */
internal interface EffectFence {
    fun <T> fenced(
        control: AttemptControl,
        guards: List<Guard>,
        effect: () -> T,
    ): T
}

/**
 * A new transaction, its statements bounded by the attempt's statement bound, the advisory locks, then the fence
 * under a row lock, then the effect. Locks before the fence, so no reclaim slips in while the attempt waits for a lock.
 */
internal class FencedEffects(
    private val locks: AdvisoryLocks,
    private val statements: AdvisoryLockStore,
    private val ledger: AttemptLedger,
    private val clock: Clock,
) : EffectFence {
    override fun <T> fenced(
        control: AttemptControl,
        guards: List<Guard>,
        effect: () -> T,
    ): T {
        control.checkRunnable(clock)
        return locks.guarded(guards, prepare = { statements.statementTimeout(control.statementBound(clock)) }) {
            if (!ledger.fence(control.lease, clock.instant())) throw LeaseLostException(control.lease.invocation)
            effect()
        }
    }
}

internal class LeasedAttempt(
    override val meta: AttemptMeta,
    private val control: AttemptControl,
    private val fence: EffectFence,
    private val clock: Clock,
) : Attempt {
    override fun <T> step(
        budget: Duration,
        body: () -> T,
    ): T {
        require(budget.isPositive) { "a step budget is positive, got $budget" }
        control.checkRunnable(clock)
        val enclosing = control.stepBudget
        control.stepBudget = if (enclosing != null && enclosing < budget) enclosing else budget
        try {
            return body()
        } finally {
            control.stepBudget = enclosing
        }
    }

    override fun <T> fenced(
        guards: List<Guard>,
        effect: () -> T,
    ): T = fence.fenced(control, guards, effect)
}
