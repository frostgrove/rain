package com.gd.rain.tenancy

import com.gd.rain.tenancy.cache.TenantCache
import com.gd.rain.tenancy.settings.TenantSettings

/** Immutable scoped runtime visible to magic integrations; it cannot replace process-global beans. */
public class TenantRuntime internal constructor(
    public val scope: TenantScope,
    private val activeUnit: TenantUnit?,
) {
    private var cache: TenantCache? = null
    private var settings: TenantSettings? = null

    /** The declared scoped cache installed by a tenancy runtime task. */
    public fun cache(): TenantCache = checkNotNull(cache) { "tenant cache is not installed in this runtime" }

    /** The immutable declared settings snapshot installed by a tenancy runtime task. */
    public fun settings(): TenantSettings = checkNotNull(settings) { "tenant settings are not installed in this runtime" }

    /**
     * The current tenant unit, when this runtime was entered from a data-plane callback.
     * Runtime-only work such as an explicit async handoff has no unit and cannot obtain one here.
     */
    public fun unit(): TenantUnit = checkNotNull(activeUnit) { "this tenant runtime has no data-plane unit" }

    internal fun installCache(value: TenantCache) {
        check(cache == null) { "tenant cache is already installed in this runtime" }
        cache = value
    }

    internal fun uninstallCache(value: TenantCache) {
        check(cache === value) { "tenant cache lease does not own this runtime" }
        cache = null
    }

    internal fun installSettings(value: TenantSettings) {
        check(settings == null) { "tenant settings are already installed in this runtime" }
        settings = value
    }

    internal fun uninstallSettings(value: TenantSettings) {
        check(settings === value) { "tenant settings lease does not own this runtime" }
        settings = null
    }

    /** Returns the runtime entered by [TenantRuntimeManager], if any. */
    public companion object {
        private val current: ThreadLocal<TenantRuntime?> = ThreadLocal.withInitial { null }

        public fun current(): TenantRuntime = checkNotNull(current.get()) { "no tenant runtime is active" }

        internal fun install(runtime: TenantRuntime?): TenantRuntime? {
            val previous = current.get()
            if (runtime == null) current.remove() else current.set(runtime)
            return previous
        }
    }
}

/** A paired extension point: every successful enter receives one reverse-order close. */
public interface TenantRuntimeTask {
    /** Stable id used in configuration, dependency ordering, metrics, and replacement validation. */
    public val id: String

    /** Task ids that must enter before this task. */
    public val after: Set<String> get() = emptySet()

    /** Task ids that must enter after this task. */
    public val before: Set<String> get() = emptySet()

    /** Enters using only an immutable scoped runtime, never a raw datasource or mutable singleton. */
    public fun enter(runtime: TenantRuntime): TenantRuntimeLease
}

/** A task's cleanup capability. It is closed in reverse successful-enter order. */
public fun interface TenantRuntimeLease : AutoCloseable {
    override fun close(): Unit
}

/**
 * Validates and runs magic tasks over the same already-minted authority used by explicit code.
 * Failure to enter a task prevents the application block from running and unwinds entered tasks.
 */
public class TenantRuntimeManager(
    private val authority: TenantAuthority,
    tasks: List<TenantRuntimeTask>,
) {
    private val ordered: List<TenantRuntimeTask> = order(tasks)

    /** Runs [block] under a validated scope and deterministic task graph. */
    public fun <T> with(
        scope: TenantScope,
        operation: TenantOperation,
        block: (TenantRuntime) -> T,
    ): T = enter(scope, operation, null, block)

    /**
     * Enters the same runtime graph from an already-open tenant unit. The unit proves its active
     * transaction before task code or the application handler can observe it.
     */
    public fun <T> with(
        unit: TenantUnit,
        block: (TenantRuntime) -> T,
    ): T {
        unit.requireCurrentTransaction()
        return enter(unit.scope, unit.operation, unit, block)
    }

    private fun <T> enter(
        scope: TenantScope,
        operation: TenantOperation,
        unit: TenantUnit?,
        block: (TenantRuntime) -> T,
    ): T {
        authority.current(scope, operation)
        TenantContext.bind(scope).use {
            val runtime = TenantRuntime(scope, unit)
            val previous = TenantRuntime.install(runtime)
            val leases = mutableListOf<TenantRuntimeLease>()
            var primaryFailure: Throwable? = null
            try {
                ordered.forEach { leases += it.enter(runtime) }
                return block(runtime)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                var closeFailure: Throwable? = null
                leases.asReversed().forEach { lease ->
                    try {
                        lease.close()
                    } catch (failure: Throwable) {
                        if (closeFailure == null) closeFailure = failure else closeFailure.addSuppressed(failure)
                    }
                }
                TenantRuntime.install(previous)
                closeFailure?.let { failure ->
                    primaryFailure?.addSuppressed(failure) ?: throw failure
                }
            }
        }
    }

    /** Stable ids in the order a runtime enters them. */
    public fun taskIds(): List<String> = ordered.map(TenantRuntimeTask::id)

    private fun order(tasks: List<TenantRuntimeTask>): List<TenantRuntimeTask> {
        require(tasks.map(TenantRuntimeTask::id).all { TASK_ID.matches(it) }) { "tenant runtime task id is not stable" }
        val byId = tasks.associateBy(TenantRuntimeTask::id)
        require(byId.size == tasks.size) { "tenant runtime task id is declared more than once" }
        val prerequisites = byId.mapValues { (_, task) -> task.after.toMutableSet() }
        byId.forEach { (id, task) ->
            task.before.forEach { later ->
                require(later in byId) { "tenant runtime task $id names unknown dependency $later" }
                prerequisites.getValue(later) += id
            }
        }
        prerequisites.forEach { (id, dependencies) ->
            require(dependencies.all { it in byId }) { "tenant runtime task $id names an unknown dependency" }
        }
        val remaining = prerequisites.mapValues { (_, dependencies) -> dependencies.toMutableSet() }.toMutableMap()
        val result = mutableListOf<TenantRuntimeTask>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filterValues(Set<String>::isEmpty).keys.sorted()
            require(ready.isNotEmpty()) { "tenant runtime task dependencies contain a cycle" }
            ready.forEach { id ->
                result += byId.getValue(id)
                remaining.remove(id)
            }
            remaining.values.forEach { it.removeAll(ready.toSet()) }
        }
        return result
    }

    private companion object {
        val TASK_ID: Regex = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    }
}
