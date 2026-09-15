package com.gd.rain.jobs.internal.execution

import com.gd.rain.persistence.lock.AdvisoryLockStore
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock

/** A transaction opened inside an attempt could not be given its statement bound; it cannot commit. */
public class StatementBoundException(
    cause: Throwable,
) : IllegalStateException("the attempt's statement bound could not be set on this transaction; it is rolled back", cause)

/**
 * `SET LOCAL statement_timeout` on every transaction begun on an attempt thread, from the attempt's statement bound.
 * Outside an attempt it does nothing: a request's bound is its own.
 *
 * A failure to set the bound is never swallowed. It cannot be thrown from here — Spring calls `afterBegin` after the
 * connection is bound, outside the begin's own failure handling, so throwing would leak the connection — so the
 * failure is recorded on the attempt (whose outcome becomes a charged `statement_bound_failed`) and the transaction
 * is given a synchronization that refuses its commit.
 */
public class AttemptStatementTimeout internal constructor(
    private val statements: () -> AdvisoryLockStore,
    private val clock: Clock,
) : TransactionExecutionListener {
    override fun afterBegin(
        transaction: TransactionExecution,
        beginFailure: Throwable?,
    ) {
        if (beginFailure != null) return
        val control = AttemptScope.current() ?: return
        try {
            statements().statementTimeout(control.statementBound(clock))
        } catch (failure: RuntimeException) {
            control.recordBoundFailure(failure)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean): Unit = throw StatementBoundException(failure)
                },
            )
        }
    }
}
