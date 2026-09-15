package com.gd.rain.jobs.internal.execution

import java.util.concurrent.atomic.AtomicInteger

/**
 * The per-definition ceiling inside a profile's pool. db-scheduler has one pool per scheduler and no per-task cap,
 * so this restores the declared ceiling. It never waits: a refusal reschedules the execution and the pooled thread
 * returns to the pool. A slot is left by the attempt thread when its body exits, not when the scheduler thread stops
 * waiting, so a wedged body keeps its definition's slot and the pool cannot run more bodies than the ceilings allow.
 */
internal class DefinitionGate(
    private val ceilings: Map<String, Int>,
) {
    private val inFlight: Map<String, AtomicInteger> = ceilings.keys.associateWith { AtomicInteger() }

    init {
        ceilings.forEach { (definition, ceiling) -> require(ceiling >= 1) { "the ceiling of $definition is $ceiling; it is at least 1" } }
    }

    fun ceilingOf(definition: String): Int =
        ceilings[definition] ?: throw IllegalArgumentException("no ceiling is declared for \"$definition\"")

    fun tryEnter(definition: String): Boolean {
        val ceiling = ceilingOf(definition)
        val counter = counterOf(definition)
        while (true) {
            val held = counter.get()
            if (held >= ceiling) return false
            if (counter.compareAndSet(held, held + 1)) return true
        }
    }

    fun leave(definition: String) {
        val counter = counterOf(definition)
        while (true) {
            val held = counter.get()
            check(held > 0) { "definition $definition left its gate more often than it entered" }
            if (counter.compareAndSet(held, held - 1)) return
        }
    }

    fun inFlightOf(definition: String): Int = counterOf(definition).get()

    private fun counterOf(definition: String): AtomicInteger =
        inFlight[definition] ?: throw IllegalArgumentException("no ceiling is declared for \"$definition\"")
}
