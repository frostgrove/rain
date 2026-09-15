package com.gd.rain.crud

import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
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

/** What [CrudStore.insert] did. */
public sealed interface InsertOutcome<out T> {
    /** The row was inserted and is in the scope; [item] is the row as stored. */
    public class Inserted<T>(
        public val item: T,
    ) : InsertOutcome<T>

    /** The row as stored would be outside the scope: nothing was inserted. */
    public data object OutsideScope : InsertOutcome<Nothing>
}

/** What [CrudStore.update] did. */
public sealed interface UpdateOutcome<out T> {
    /** The row was written and is still in the scope; [item] is the row as stored. */
    public class Updated<T>(
        public val item: T,
    ) : UpdateOutcome<T>

    /** No row with the identifier is in the scope: nothing was written. */
    public data object NotFound : UpdateOutcome<Nothing>

    /** The row is in the scope, but its version is not the one the write states: nothing was written. */
    public data object StaleVersion : UpdateOutcome<Nothing>

    /** The row as written would leave the scope: nothing was written. */
    public data object OutsideScope : UpdateOutcome<Nothing>
}

/** What [CrudStore.updateMany] did. */
public sealed interface BulkUpdateOutcome {
    /** [count] rows among the ids were in the scope and were written; every one of them is still in the scope. */
    public data class Updated(
        public val count: Long,
    ) : BulkUpdateOutcome

    /** At least one row as written would leave the scope: nothing was written. */
    public data object OutsideScope : BulkUpdateOutcome
}

/**
 * Storage for one resource. It enforces no permission — [CrudResource] does — but every operation is
 * confined to the [RowScope] it is given, writes included: a write reaches only rows in the scope, and the row it
 * leaves behind — inserted, updated or replaced — is in the scope too, or nothing is written.
 *
 * Write values are keyed by field name and carried as their kind's type ([WriteValues]). When the schema declares a
 * version ([ResourceSchema.version]) the store writes it: 1 on insert, one more on every update, and an update by
 * identifier states the version it expects.
 */
public interface CrudStore<T> {
    public val schema: ResourceSchema

    /**
     * The fields every row must carry for the store to turn it into an item, whatever the projection: a projection
     * that does not hold all of them cannot be read, so a query asking for one is refused before any statement runs.
     */
    public val itemFields: Set<SchemaField>

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

    /**
     * Inserts a row; the identifier is minted when [values] does not name one, and the version is 1 when the schema
     * declares one. The inserted row is checked against [scope] as stored, in the same transaction; outside it, the
     * insert is rolled back.
     */
    public fun insert(
        scope: RowScope,
        values: Map<String, Any?>,
    ): InsertOutcome<T>

    /**
     * Writes the non-empty [values] to the row [id] if it is in [scope] and, when the schema declares a version, its
     * version is [expectedVersion] (which is `null` exactly when the schema declares none). The written row is checked
     * against [scope] in the same transaction; outside it, the write is rolled back.
     */
    public fun update(
        id: UUID,
        scope: RowScope,
        values: Map<String, Any?>,
        expectedVersion: Long?,
    ): UpdateOutcome<T>

    /**
     * Writes the non-empty [values] to the rows among [ids] that are in [scope], without a version check; answers how
     * many. Every written row is checked against [scope] in the same transaction; when one is outside it, every write
     * is rolled back.
     */
    public fun updateMany(
        ids: Set<UUID>,
        scope: RowScope,
        values: Map<String, Any?>,
    ): BulkUpdateOutcome

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
