package com.gd.rain.crud

import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.ResourceSchema
import java.util.UUID

/**
 * The rows an operation is confined to. Every store method takes one, so a read or a write that forgot
 * the policy's scope cannot be written: an unscoped operation says [Everything].
 */
public sealed interface RowScope {
    public val predicate: Predicate?

    public data object Everything : RowScope {
        override val predicate: Predicate? = null
    }

    public class Matching(
        override val predicate: Predicate,
    ) : RowScope
}

/**
 * One bounded read: the rows in [scope] matching [filter] and, for a keyset page, [seek]; in [order];
 * skipping [offset]; at most [limit] of them.
 */
public class RowRead(
    public val scope: RowScope,
    public val filter: Predicate?,
    public val seek: Predicate.Keyset?,
    order: List<Order>,
    public val limit: Int,
    public val offset: Long,
    public val projection: Projection,
) {
    public val order: List<Order> = order.toList()

    init {
        require(this.order.isNotEmpty()) { "a read states its order" }
        require(limit >= 1) { "a read asks for at least one row, got $limit" }
        require(offset >= 0) { "an offset is not negative, got $offset" }
        require(seek == null || offset == 0L) { "a keyset read skips nothing" }
        require(seek == null || seek.order.map(Order::field) == this.order.map(Order::field)) {
            "a keyset compares the fields the read is ordered by"
        }
    }

    /** Scope, filter and seek: every condition of the read. */
    public val conditions: List<Predicate> get() = listOfNotNull(scope.predicate, filter, seek)
}

/** A row read by [CrudStore.read] and the values of the read's order fields, from which a cursor is minted. */
public class KeyedRow<T>(
    public val item: T,
    keys: List<Any>,
) {
    public val keys: List<Any> = keys.toList()
}

/**
 * Storage for one resource. It enforces no permission — [CrudResource] does — but every operation is
 * confined to the [RowScope] it is given, writes included.
 *
 * Write values are keyed by field name and carried as their kind's type ([WriteValues]).
 */
public interface CrudStore<T> {
    public val schema: ResourceSchema

    /** At most `read.limit` rows, in `read.order`. */
    public fun read(read: RowRead): List<KeyedRow<T>>

    /** The number of rows in [scope] matching [filter], reading at most `cap + 1` of them: `min(count, cap + 1)`. */
    public fun countUpTo(
        scope: RowScope,
        filter: Predicate?,
        cap: Long,
    ): Long

    public fun find(
        id: UUID,
        scope: RowScope,
        projection: Projection,
    ): T?

    /** Inserts a row; the identifier is minted when [values] does not name one. */
    public fun insert(values: Map<String, Any?>): T

    /** Writes the non-empty [values] to the row [id] if it is in [scope]; `null` when no such row is. */
    public fun update(
        id: UUID,
        scope: RowScope,
        values: Map<String, Any?>,
    ): T?

    /** Writes the non-empty [values] to the rows among [ids] that are in [scope]; answers how many. */
    public fun updateMany(
        ids: Set<UUID>,
        scope: RowScope,
        values: Map<String, Any?>,
    ): Long

    public fun delete(
        id: UUID,
        scope: RowScope,
    ): Long

    public fun deleteMany(
        ids: Set<UUID>,
        scope: RowScope,
    ): Long
}

/** The shape write values have: a declared field name and a value of that field's kind, or null. */
public object WriteValues {
    public fun check(
        schema: ResourceSchema,
        values: Map<String, Any?>,
    ) {
        values.forEach { (name, value) ->
            val field = requireNotNull(schema.field(name)) { "resource ${schema.name} has no field $name" }
            require(value == null || field.kind.accepts(value)) {
                "a value for ${schema.name}.$name is a ${field.kind.valueType.simpleName}, not a ${value?.javaClass?.simpleName}"
            }
        }
    }
}
