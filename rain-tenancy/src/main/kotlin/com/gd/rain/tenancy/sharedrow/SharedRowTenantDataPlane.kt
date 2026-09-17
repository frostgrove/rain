package com.gd.rain.tenancy.sharedrow

import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.persistence.tx.TransactionPlacement
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantGrantVerifier
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.TenantUnit
import com.gd.rain.tenancy.control.TenantReferenceDigest
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.Base64

/**
 * Shared-row implementation that owns one Spring transaction and installs tenant GUCs with
 * PostgreSQL `set_config(..., true)` before exposing its [TenantUnit]. It never changes a default
 * datasource, global DSL context, or pooled-connection setting.
 */
public class SharedRowTenantDataPlane(
    private val dsl: DSLContext,
    transactions: PlatformTransactionManager,
    private val authority: TenantAuthority,
    private val referenceDigest: TenantReferenceDigest,
    private val grants: TenantGrantVerifier,
    private val clock: Clock,
) : TenantDataPlane {
    private val transactions: PlatformTransactionManager = transactions
    private val reads: TransactionTemplate = TransactionTemplate(transactions).apply { isReadOnly = true }
    private val writes: TransactionTemplate = TransactionTemplate(transactions)

    init {
        require(dsl.configuration().dialect().family() == SQLDialect.POSTGRES) { "shared-row tenancy requires PostgreSQL" }
    }

    override fun <T> read(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T = within(scope, TenantOperation.READ, reads, block)

    override fun <T> write(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T = within(scope, TenantOperation.WRITE, writes, block)

    override fun <T> durable(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T = within(scope, TenantOperation.DURABLE, writes, block)

    override fun <T> admin(
        grant: TenantGrant,
        tenant: TenantRef,
        block: (TenantUnit) -> T,
    ): T = within(grants.verify(grant, tenant, TenantOperation.ADMIN), TenantOperation.ADMIN, writes, block)

    private fun <T> within(
        scope: TenantScope,
        operation: TenantOperation,
        template: TransactionTemplate,
        block: (TenantUnit) -> T,
    ): T {
        authority.current(scope, operation)
        val existing = TransactionSynchronizationManager.getResource(UNIT_RESOURCE) as UnitMarker?
        check(!TransactionSynchronizationManager.isActualTransactionActive() || existing != null) {
            "tenant data-plane work cannot join an unscoped transaction"
        }
        existing?.requireSame(scope)
        return checkNotNull(
            template.execute {
                authority.current(scope, operation)
                val marker =
                    existing ?: UnitMarker(scope).also {
                        TransactionSynchronizationManager.bindResource(UNIT_RESOURCE, it)
                        TransactionSynchronizationManager.registerSynchronization(
                            object : TransactionSynchronization {
                                override fun afterCompletion(status: Int) {
                                    if (TransactionSynchronizationManager.hasResource(UNIT_RESOURCE)) {
                                        TransactionSynchronizationManager.unbindResource(UNIT_RESOURCE)
                                    }
                                }
                            },
                        )
                    }
                marker.requireSame(scope)
                com.gd.rain.tenancy.TenantContext.bind(scope).use {
                    val placement = TransactionPlacement.inspect(dsl, transactions)
                    val transaction = placement.requireAuthority()
                    configure(scope, operation)
                    block(SharedRowUnit(scope, operation, dsl, placement.backing, transaction, transactions, clock))
                }
            },
        )
    }

    private fun configure(
        scope: TenantScope,
        operation: TenantOperation,
    ) {
        val digest = Base64.getEncoder().withoutPadding().encodeToString(referenceDigest.digest(scope.ref).copy())
        setLocal(REF_DIGEST_GUC, digest)
        setLocal(EPOCH_GUC, scope.epoch.value.toString())
        setLocal(OPERATION_GUC, operation.name.lowercase())
    }

    private fun setLocal(
        name: String,
        value: String,
    ) {
        dsl
            .select(DSL.function("set_config", SQLDataType.VARCHAR, DSL.inline(name), DSL.`val`(value), DSL.inline(true)))
            .fetchOne()
    }

    private class SharedRowUnit(
        override val scope: TenantScope,
        override val operation: TenantOperation,
        override val dsl: DSLContext,
        override val backing: com.gd.rain.persistence.tx.BackingIdentity,
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
    ) {
        fun requireSame(other: TenantScope) {
            check(scope.ref == other.ref && scope.epoch == other.epoch && scope.authorityOrigin == other.authorityOrigin) {
                "tenant scope is pinned to another tenant transaction"
            }
        }
    }

    public companion object {
        /** Base64 of the opaque deployment-HMAC reference digest; policies must decode it as bytea. */
        public const val REF_DIGEST_GUC: String = "rain.tenant_ref_digest"
        public const val EPOCH_GUC: String = "rain.tenant_epoch"
        public const val OPERATION_GUC: String = "rain.tenant_operation"

        private val UNIT_RESOURCE: Any = Any()
    }
}
