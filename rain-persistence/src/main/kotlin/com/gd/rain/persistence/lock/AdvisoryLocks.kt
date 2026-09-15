package com.gd.rain.persistence.lock

import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.Guard
import com.gd.rain.core.lock.LockKey
import com.gd.rain.core.lock.Sharing
import com.gd.rain.persistence.tx.TransactionRetry
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/** The statements an advisory lock is made of. Every method requires an active transaction. */
public interface AdvisoryLockStore {
    /** `SET LOCAL lock_timeout`: how long the next lock waits before failing with `55P03`. */
    public fun lockTimeout(timeout: Duration)

    /** `SET LOCAL statement_timeout`: the bound on every following statement of this transaction. */
    public fun statementTimeout(timeout: Duration)

    public fun exclusive(key: LockKey)

    public fun shared(key: LockKey)
}

/** PostgreSQL built-ins through jOOQ; the flag that makes `set_config` transaction-local is inlined, never bound. */
public class JooqAdvisoryLockStore(
    private val dsl: DSLContext,
) : AdvisoryLockStore {
    override fun lockTimeout(timeout: Duration): Unit = setLocal("lock_timeout", timeout)

    override fun statementTimeout(timeout: Duration): Unit = setLocal("statement_timeout", timeout)

    override fun exclusive(key: LockKey) {
        dsl.select(DSL.function("pg_advisory_xact_lock", Any::class.java, DSL.value(key.value))).fetch()
    }

    override fun shared(key: LockKey) {
        dsl.select(DSL.function("pg_advisory_xact_lock_shared", Any::class.java, DSL.value(key.value))).fetch()
    }

    private fun setLocal(
        setting: String,
        timeout: Duration,
    ) {
        dsl
            .select(
                DSL.function(
                    "set_config",
                    String::class.java,
                    DSL.inline(setting),
                    DSL.value(timeout.toMillis().toString()),
                    DSL.inline(true),
                ),
            ).fetch()
    }
}

/**
 * Transaction-scoped PostgreSQL advisory locks.
 *
 * [take] joins the caller's transaction and refuses outside one — a lock released at commit that was
 * taken with no transaction is released before the caller can use it. [guarded] opens a transaction of
 * its own around the locks and refuses to run inside another: the outer transaction already holds a
 * pooled connection, and a second one requested while it waits is a deadlock the pool cannot resolve.
 */
public class AdvisoryLocks(
    transactions: PlatformTransactionManager,
    private val store: AdvisoryLockStore,
    private val retry: TransactionRetry,
    private val timeout: Duration,
) {
    private val template =
        TransactionTemplate(transactions).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    public fun take(guard: Guard) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { NO_TRANSACTION }
        store.lockTimeout(timeout)
        when (guard) {
            is Exclusively -> store.exclusive(guard.key)
            is Sharing -> store.shared(guard.key)
        }
    }

    /** Takes the guards in the order given; callers that take the same pair take it in the same order. */
    public fun take(guards: List<Guard>) {
        guards.forEach(::take)
    }

    /**
     * Runs [block] in a new transaction holding every guard, retried as a whole on deadlock, lock
     * timeout or serialization failure. [prepare] runs inside that transaction before the locks, e.g. to
     * bound its statements.
     */
    public fun <T> guarded(
        guards: List<Guard>,
        prepare: () -> Unit = {},
        block: () -> T,
    ): T {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw NestedTransactionNotAllowed(
                "guarded() opens the transaction that holds its locks and cannot run inside another; use take() in the transaction you are in",
            )
        }
        return retry.run {
            template.execute {
                prepare()
                take(guards)
                block()
            }
        }
    }

    private companion object {
        const val NO_TRANSACTION = "an advisory lock outside a transaction is released before the caller can use it"
    }
}

public class NestedTransactionNotAllowed(
    message: String,
) : IllegalStateException(message)
