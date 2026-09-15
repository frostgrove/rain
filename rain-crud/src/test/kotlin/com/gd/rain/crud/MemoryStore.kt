package com.gd.rain.crud

import com.gd.rain.crud.query.Direction
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.ResourceSchema
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [CrudStore] over maps in memory, for tests of the resource, the dialect and the web layer without a database.
 *
 * It evaluates the compiled predicates with SQL's semantics — a comparison never matches NULL, NULLs sort
 * last ascending and first descending, UUIDs order by their canonical text as PostgreSQL orders them — and
 * it runs [CrudStoreContract] alongside the jOOQ store. It scans every row: it is a test double, never storage.
 */
class MemoryStore(
    override val schema: ResourceSchema,
) : CrudStore<Map<String, Any?>> {
    private val rows = CopyOnWriteArrayList<MutableMap<String, Any?>>()

    /** The store operations called, in order. */
    val operations: MutableList<String> = CopyOnWriteArrayList()

    /** The reads asked for, in order. */
    val reads: MutableList<RowRead> = CopyOnWriteArrayList()

    fun add(vararg books: Map<String, Any?>): List<UUID> =
        books.map { book ->
            val id = book["id"] as? UUID ?: UUID.randomUUID()
            rows += schema.fields.associateTo(LinkedHashMap()) { it.name to book[it.name] }.also { it[schema.id.name] = id }
            id
        }

    fun stored(id: UUID): Map<String, Any?>? = rows.firstOrNull { it[schema.id.name] == id }?.toMap()

    override fun read(read: RowRead): List<KeyedRow<Map<String, Any?>>> {
        operations += "read"
        reads += read
        return rows
            .filter { row -> read.conditions.all { matches(it, row) } }
            .sortedWith(comparator(read.order))
            .asSequence()
            .drop(Math.toIntExact(read.offset))
            .take(read.limit)
            .map { row -> KeyedRow(project(row, read.projection), read.order.map { checkNotNull(row[it.field.name]) }) }
            .toList()
    }

    override fun countUpTo(
        scope: RowScope,
        filter: Predicate?,
        cap: Long,
    ): Long {
        operations += "count"
        val counted = rows.count { row -> listOfNotNull(scope.predicate, filter).all { matches(it, row) } }.toLong()
        return minOf(counted, cap + 1)
    }

    override fun find(
        id: UUID,
        scope: RowScope,
        projection: Projection,
    ): Map<String, Any?>? {
        operations += "find"
        return inScope(id, scope)?.let { project(it, projection) }
    }

    override fun insert(values: Map<String, Any?>): Map<String, Any?> {
        operations += "insert"
        WriteValues.check(schema, values)
        val id = add(values).single()
        return checkNotNull(stored(id))
    }

    override fun update(
        id: UUID,
        scope: RowScope,
        values: Map<String, Any?>,
    ): Map<String, Any?>? {
        operations += "update"
        require(values.isNotEmpty())
        WriteValues.check(schema, values)
        val row = inScope(id, scope) ?: return null
        row.putAll(values)
        return row.toMap()
    }

    override fun updateMany(
        ids: Set<UUID>,
        scope: RowScope,
        values: Map<String, Any?>,
    ): Long {
        operations += "updateMany"
        require(values.isNotEmpty())
        WriteValues.check(schema, values)
        val matched = ids.mapNotNull { inScope(it, scope) }
        matched.forEach { it.putAll(values) }
        return matched.size.toLong()
    }

    override fun delete(
        id: UUID,
        scope: RowScope,
    ): Long {
        operations += "delete"
        val row = inScope(id, scope) ?: return 0
        rows.remove(row)
        return 1
    }

    override fun deleteMany(
        ids: Set<UUID>,
        scope: RowScope,
    ): Long {
        operations += "deleteMany"
        val matched = ids.mapNotNull { inScope(it, scope) }
        rows.removeAll(matched.toSet())
        return matched.size.toLong()
    }

    private fun inScope(
        id: UUID,
        scope: RowScope,
    ): MutableMap<String, Any?>? = rows.firstOrNull { it[schema.id.name] == id && (scope.predicate?.let { p -> matches(p, it) } ?: true) }

    private fun project(
        row: Map<String, Any?>,
        projection: Projection,
    ): Map<String, Any?> =
        when (projection) {
            Projection.Full -> LinkedHashMap(row)
            is Projection.Only -> schema.fields.filter { it in projection.fields }.associateTo(LinkedHashMap()) { it.name to row[it.name] }
        }

    private fun comparator(order: List<Order>): Comparator<Map<String, Any?>> =
        Comparator { left, right ->
            order.forEach { term ->
                val compared = nullsLast(left[term.field.name], right[term.field.name])
                if (compared != 0) return@Comparator if (term.direction == Direction.ASC) compared else -compared
            }
            0
        }

    private fun matches(
        predicate: Predicate,
        row: Map<String, Any?>,
    ): Boolean =
        when (predicate) {
            is Predicate.AllOf -> predicate.of.all { matches(it, row) }
            is Predicate.AnyOf -> predicate.of.any { matches(it, row) }
            is Predicate.Keyset -> keyset(predicate, row)
            is Predicate.Compare -> compare(predicate, row[predicate.field.name])
        }

    private fun keyset(
        seek: Predicate.Keyset,
        row: Map<String, Any?>,
    ): Boolean {
        seek.order.indices.forEach { index ->
            val compared = valueOrder(checkNotNull(row[seek.order[index].field.name]), seek.values[index])
            if (compared != 0) return (compared > 0) == seek.greaterAt(index)
        }
        return false
    }

    private fun compare(
        predicate: Predicate.Compare,
        value: Any?,
    ): Boolean {
        if (predicate.operator == Operator.IS_NULL) return (value == null) == predicate.values.single()
        if (value == null) return false
        val first = predicate.values.first()
        return when (predicate.operator) {
            Operator.EQ -> valueOrder(value, first) == 0
            Operator.NE -> valueOrder(value, first) != 0
            Operator.GT -> valueOrder(value, first) > 0
            Operator.GTE -> valueOrder(value, first) >= 0
            Operator.LT -> valueOrder(value, first) < 0
            Operator.LTE -> valueOrder(value, first) <= 0
            Operator.IN -> predicate.values.any { valueOrder(value, it) == 0 }
            Operator.NIN -> predicate.values.none { valueOrder(value, it) == 0 }
            Operator.CONTAINS -> (value as String).contains(first as String)
            Operator.ICONTAINS -> (value as String).lowercase().contains((first as String).lowercase())
            Operator.STARTS_WITH -> (value as String).startsWith(first as String)
            Operator.ISTARTS_WITH -> (value as String).lowercase().startsWith((first as String).lowercase())
            Operator.ENDS_WITH -> (value as String).endsWith(first as String)
            Operator.IENDS_WITH -> (value as String).lowercase().endsWith((first as String).lowercase())
            Operator.IS_NULL -> error("handled above")
        }
    }

    private companion object {
        fun nullsLast(
            left: Any?,
            right: Any?,
        ): Int =
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                else -> valueOrder(left, right)
            }

        @Suppress("UNCHECKED_CAST")
        fun valueOrder(
            left: Any,
            right: Any,
        ): Int =
            when (left) {
                is UUID -> left.toString().compareTo(right.toString())
                is BigDecimal -> left.compareTo(right as BigDecimal)
                else -> (left as Comparable<Any>).compareTo(right)
            }
    }
}
