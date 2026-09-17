package com.gd.rain.event

import kotlin.reflect.KClass

/** Codec for one fact revision. It has no reflection fallback and always returns bounded bytes. */
public interface FactCodec<E : Any> {
    public fun encode(value: E): EventBytes

    public fun decode(payload: EventBytes): E
}

/**
 * Explicit wire declaration for an event fact.
 *
 * [type] rather than [kotlinType] names the persisted fact. [readableRevisions] may include old
 * revisions only when [read] can decode or upcast each one into the current Kotlin value.
 */
public interface FactSpec<E : Any> {
    public val type: String
    public val kotlinType: KClass<E>
    public val readableRevisions: Set<Int>
    public val writeRevision: Int

    public fun write(value: E): EventBytes

    public fun read(
        revision: Int,
        payload: EventBytes,
    ): E
}

/** Explicit aggregate declaration, including the only fold function that may rebuild its state. */
public interface AggregateSpec<S : Any, ID : Any> {
    public val family: String
    public val facts: Set<FactSpec<out Any>>

    public fun streamKey(id: ID): String

    public fun initial(id: ID): S

    public fun fold(
        state: S,
        fact: Any,
    ): S
}

/** Startup-validated aggregate and fact catalogue. */
public class EventCatalogue(
    aggregates: Set<AggregateSpec<out Any, out Any>>,
) {
    public val aggregates: Set<AggregateSpec<out Any, out Any>> = aggregates.toSet()

    private val families: Map<String, AggregateSpec<out Any, out Any>>
    private val facts: Map<String, FactSpec<out Any>>

    init {
        require(this.aggregates.isNotEmpty()) { "an event catalogue declares at least one aggregate" }
        this.aggregates.forEach { aggregate ->
            require(StreamRef.NAME.matches(aggregate.family)) { "aggregate family is not a stable event name" }
            require(aggregate.facts.isNotEmpty()) { "aggregate ${aggregate.family} declares no facts" }
        }
        families = this.aggregates.associateBy { it.family }
        require(families.size == this.aggregates.size) { "an event catalogue declares an aggregate family more than once" }
        val declarations = this.aggregates.flatMap { aggregate -> aggregate.facts.map { aggregate.family to it } }
        facts = declarations.associate { (family, fact) -> "$family:${fact.type}" to fact }
        require(facts.size == declarations.size) { "an aggregate declares a fact type more than once" }
        declarations.forEach { (_, fact) -> validate(fact) }
    }

    /** Looks up a declared aggregate or refuses an undeclared wire family. */
    public fun aggregate(family: String): AggregateSpec<out Any, out Any> =
        checkNotNull(families[family]) { "aggregate family is not declared" }

    /** Looks up a declared fact or refuses an undeclared wire type. */
    public fun fact(
        family: String,
        type: String,
    ): FactSpec<out Any> = checkNotNull(facts["$family:$type"]) { "fact type is not declared" }

    private fun validate(fact: FactSpec<out Any>) {
        require(StreamRef.NAME.matches(fact.type)) { "fact type is not a stable event name" }
        require(fact.readableRevisions.isNotEmpty()) { "fact ${fact.type} has no readable revisions" }
        require(fact.readableRevisions.all { it >= 1 }) { "fact ${fact.type} has a non-positive readable revision" }
        require(fact.writeRevision in fact.readableRevisions) { "fact ${fact.type} writes a revision it cannot read" }
    }
}
