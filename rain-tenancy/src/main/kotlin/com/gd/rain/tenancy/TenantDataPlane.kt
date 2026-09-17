package com.gd.rain.tenancy

import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionAuthority
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant

/** A scoped, already-bound tenant transaction. It deliberately exposes no datasource or connection. */
public interface TenantUnit {
    public val scope: TenantScope
    public val operation: TenantOperation
    public val dsl: DSLContext
    public val backing: BackingIdentity
    public val transactions: TransactionAuthority
    public val clock: Clock

    /** Re-proves this exact unit's active transaction without exposing a datasource or connection. */
    public fun requireCurrentTransaction(): TransactionAuthority

    /** Hint-only work after commit; durable effects use rain-jobs or TenantOutbox instead. */
    public fun afterCommit(hint: () -> Unit): Unit
}

/** Explicit tenant data-plane boundary. Normal Spring `@Transactional` never implicitly becomes one. */
public interface TenantDataPlane {
    public fun <T> read(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T

    public fun <T> write(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T

    public fun <T> durable(
        scope: TenantScope,
        block: (TenantUnit) -> T,
    ): T

    public fun <T> admin(
        grant: TenantGrant,
        tenant: TenantRef,
        block: (TenantUnit) -> T,
    ): T
}

/** Verifies a bounded cross-tenant grant and returns the already-admitted scope it authorizes. */
public fun interface TenantGrantVerifier {
    public fun verify(
        grant: TenantGrant,
        tenant: TenantRef,
        operation: TenantOperation,
    ): TenantScope
}

/** A purpose- and time-bounded cross-tenant grant; issuance remains a control-plane responsibility. */
public class TenantGrant internal constructor(
    public val issuer: String,
    public val purpose: String,
    public val operations: Set<TenantOperation>,
    public val expiresAt: Instant,
    cohort: Set<TenantRef>,
    binding: ByteArray,
) {
    internal val cohort: Set<TenantRef> = cohort.toSet()
    internal val binding: ByteArray = binding.copyOf()

    init {
        require(ISSUER.matches(issuer)) { "tenant grant issuer is not stable" }
        require(purpose.toByteArray(Charsets.UTF_8).size in 1..256) { "tenant grant purpose is out of bounds" }
        require(purpose.none { it.isISOControl() }) { "tenant grant purpose contains a control character" }
        require(operations.isNotEmpty()) { "tenant grant declares an operation" }
        require(cohort.isNotEmpty() && cohort.size <= MAX_COHORT) { "tenant grant cohort is out of bounds" }
        require(this.binding.size == BINDING_BYTES) { "tenant grant binding has an unknown format" }
    }

    override fun toString(): String = "tenant-grant[redacted]"

    private companion object {
        const val MAX_COHORT: Int = 10_000
        const val BINDING_BYTES: Int = 32
        val ISSUER: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}
