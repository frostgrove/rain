package com.gd.rain.tenancy.database

import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.persistence.tx.TransactionPlacement
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantGrantVerifier
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.TenantUnit
import org.jooq.DSLContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Database-per-tenant data plane. A unit borrows exactly one fenced directory lease and keeps it
 * until its transaction completes; nested work may reuse only that exact scope and operation.
 */
public class DatabaseTenantDataPlane(
    private val directory: TenantDatabaseDirectory,
    private val authority: TenantAuthority,
    private val grants: TenantGrantVerifier,
    private val clock: Clock,
) : TenantDataPlane {
    override fun <T> read(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T = within(scope, TenantOperation.READ, readOnly = true, block)

    override fun <T> write(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T = within(scope, TenantOperation.WRITE, readOnly = false, block)

    override fun <T> durable(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T = within(scope, TenantOperation.DURABLE, readOnly = false, block)

    override fun <T> admin(
        grant: TenantGrant,
        tenant: TenantRef,
        block: (TenantUnit) -> T,
    ): T = within(grants.verify(grant, tenant, TenantOperation.ADMIN), TenantOperation.ADMIN, readOnly = false, block)

    private fun <T> within(
        scope: TenantScope,
        operation: TenantOperation,
        readOnly: Boolean,
        block: (TenantUnit) -> T,
    ): T {
        authority.current(scope, operation)
        val nested = TransactionSynchronizationManager.getResource(UNIT_RESOURCE) as UnitMarker?
        check(!TransactionSynchronizationManager.isActualTransactionActive() || nested != null) {
            "tenant data-plane work cannot join an unscoped transaction"
        }
        if (nested != null) {
            nested.requireSame(scope, operation)
            return TenantContext.bind(scope).use { block(nested.unit) }
        }

        val lease = directory.borrow(scope)
        var leaseIsTransactionBound = false
        try {
            val transaction = TransactionTemplate(lease.transactionManager).apply { isReadOnly = readOnly }
            return checkNotNull(
                transaction.execute {
                    authority.current(scope, operation)
                    TenantContext.bind(scope).use {
                        val placement = TransactionPlacement.inspect(lease.dsl, lease.transactionManager)
                        val unit =
                            DatabaseTenantUnit(
                                scope,
                                operation,
                                lease.dsl,
                                placement.backing,
                                placement.requireAuthority(),
                                lease.transactionManager,
                                clock,
                            )
                        val marker = UnitMarker(scope, operation, unit)
                        TransactionSynchronizationManager.bindResource(UNIT_RESOURCE, marker)
                        TransactionSynchronizationManager.registerSynchronization(
                            object : TransactionSynchronization {
                                override fun afterCompletion(status: Int) {
                                    if (TransactionSynchronizationManager.hasResource(UNIT_RESOURCE)) {
                                        TransactionSynchronizationManager.unbindResource(UNIT_RESOURCE)
                                    }
                                    lease.close()
                                }
                            },
                        )
                        leaseIsTransactionBound = true
                        block(unit)
                    }
                },
            )
        } finally {
            if (!leaseIsTransactionBound) lease.close()
        }
    }

    private class DatabaseTenantUnit(
        override val scope: TenantScope,
        override val operation: TenantOperation,
        override val dsl: DSLContext,
        override val backing: BackingIdentity,
        override val transactions: TransactionAuthority,
        private val transactionManager: PlatformTransactionManager,
        override val clock: Clock,
    ) : TenantUnit {
        override fun requireCurrentTransaction(): TransactionAuthority {
            val current = TransactionPlacement.inspect(dsl, transactionManager).requireAuthority()
            check(current == transactions) { "tenant unit is no longer current" }
            return current
        }

        override fun afterCommit(hint: () -> Unit) {
            check(TransactionSynchronizationManager.isSynchronizationActive()) { "tenant after-commit hint requires an active transaction" }
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        hint()
                    }
                },
            )
        }
    }

    private class UnitMarker(
        private val scope: TenantScope,
        private val operation: TenantOperation,
        val unit: TenantUnit,
    ) {
        fun requireSame(
            other: TenantScope,
            otherOperation: TenantOperation,
        ) {
            check(scope.ref == other.ref && scope.epoch == other.epoch && scope.authorityOrigin == other.authorityOrigin) {
                "tenant scope is pinned to another tenant transaction"
            }
            check(operation == otherOperation) {
                "nested tenant data-plane work cannot widen its operation"
            }
        }
    }

    private companion object {
        val UNIT_RESOURCE: Any = Any()
    }
}
