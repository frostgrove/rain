package com.gd.rain.tenancy

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Transport for an already-minted scope, with re-entrant same-tenant binding and transaction pinning.
 *
 * It has no `runAs(TenantRef)` overload. The only public entry accepts a [TenantScope] an authority
 * has already minted. A pin survives bind/unbind until the owning Spring transaction completes, so
 * `REQUIRES_NEW` cannot switch tenants while an outer unit is suspended on the same thread.
 */
public object TenantContext {
    private val bound: ThreadLocal<Bound?> = ThreadLocal.withInitial { null }
    private val transactionPins: ThreadLocal<MutableSet<ScopeIdentity>> = ThreadLocal.withInitial { linkedSetOf() }

    /** Binds [scope] and returns a lease that restores the previous transport state exactly once. */
    public fun bind(scope: TenantScope): TenantBinding {
        val identity = scope.identity()
        val current = bound.get()
        if (current != null) {
            check(current.identity == identity) { "tenant scope is pinned to another tenant" }
            current.depth += 1
            return TenantBinding(scope, nested = true)
        }
        val pins = transactionPins.get()
        check(pins.isEmpty() || pins.single() == identity) { "tenant scope is pinned to another transaction tenant" }
        pinTransaction(identity)
        bound.set(Bound(scope, identity, 1))
        return TenantBinding(scope, nested = false)
    }

    /** Returns the current opaque scope or refuses instead of manufacturing one from a tenant id. */
    public fun requireScope(): TenantScope = checkNotNull(bound.get()?.scope) { "no tenant scope is bound" }

    /** Internal boundary check used by central servlet and async entry points before they execute work. */
    internal fun requireUnbound() {
        check(bound.get() == null) { "tenant scope leaked into a central boundary" }
    }

    /** Captures only an already-minted scope for an explicit asynchronous handoff. */
    public fun capture(): TenantScopeCarrier = TenantScopeCarrier(requireScope())

    /**
     * Captures the ambient scope when one exists. This is intentionally internal: a central caller
     * must stay central rather than receiving a carrier it could later treat as an authority.
     */
    internal fun captureIfBound(): TenantScopeCarrier? = bound.get()?.scope?.let(::TenantScopeCarrier)

    /**
     * Runs one in-process handoff from a clean worker baseline and detects accidental thread-local
     * leakage. The decorator owns the final cleanup so a poisoned pooled worker cannot serve the
     * next task under a previous tenant.
     */
    internal fun <T> runAsync(
        carrier: TenantScopeCarrier?,
        block: () -> T,
    ): T {
        check(bound.get() == null) { "tenant scope leaked into async worker" }
        var primaryFailure: Throwable? = null
        try {
            return carrier?.call(block) ?: block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val leaked = bound.get()
            if (leaked != null) bound.remove()
            if (leaked != null) {
                val failure = IllegalStateException("tenant scope leaked from async task")
                primaryFailure?.addSuppressed(failure) ?: throw failure
            }
        }
    }

    private fun close(binding: TenantBinding) {
        val current = checkNotNull(bound.get()) { "tenant binding was already closed" }
        check(current.scope === binding.scope) { "tenant bindings close in stack order" }
        current.depth -= 1
        if (current.depth == 0) bound.remove()
    }

    private fun pinTransaction(identity: ScopeIdentity) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return
        val pins = transactionPins.get()
        if (!pins.add(identity)) return
        if (!TransactionSynchronizationManager.hasResource(PIN_RESOURCE)) {
            TransactionSynchronizationManager.bindResource(PIN_RESOURCE, identity)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        if (TransactionSynchronizationManager.hasResource(PIN_RESOURCE)) {
                            TransactionSynchronizationManager.unbindResource(PIN_RESOURCE)
                        }
                        transactionPins.get().remove(identity)
                        if (transactionPins.get().isEmpty()) transactionPins.remove()
                    }
                },
            )
        } else {
            check(TransactionSynchronizationManager.getResource(PIN_RESOURCE) == identity) {
                "tenant scope is pinned to another transaction tenant"
            }
        }
    }

    private data class Bound(
        val scope: TenantScope,
        val identity: ScopeIdentity,
        var depth: Int,
    )

    private val PIN_RESOURCE: Any = Any()

    /** A one-shot binding lease. Prefer Kotlin's `use` to ensure cleanup on failures. */
    public class TenantBinding internal constructor(
        internal val scope: TenantScope,
        private val nested: Boolean,
    ) : AutoCloseable {
        private var closed: Boolean = false

        /** Whether this binding reused an equivalent already-bound scope. */
        public val isNested: Boolean get() = nested

        override fun close() {
            check(!closed) { "tenant binding closes once" }
            closed = true
            TenantContext.close(this)
        }
    }
}

/** Explicit async carrier; it transports a scope but does not make a raw reference authoritative. */
public class TenantScopeCarrier internal constructor(
    private val scope: TenantScope,
) {
    public fun <T> call(block: () -> T): T = TenantContext.bind(scope).use { block() }

    public fun run(block: () -> Unit) {
        call(block)
    }
}

private data class ScopeIdentity(
    val ref: TenantRef,
    val epoch: TenantEpoch,
    val origin: String,
)

private fun TenantScope.identity(): ScopeIdentity = ScopeIdentity(ref, epoch, authorityOrigin)
