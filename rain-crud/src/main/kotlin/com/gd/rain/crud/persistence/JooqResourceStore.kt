package com.gd.rain.crud.persistence

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.crud.CrudStore
import com.gd.rain.crud.KeyedRow
import com.gd.rain.crud.RowRead
import com.gd.rain.crud.RowScope
import com.gd.rain.crud.WriteValues
import com.gd.rain.crud.query.Direction
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Pagination
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.SortField
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A [CrudStore] over one application table, in typed jOOQ.
 *
 * The table is `DSL.name(schema, table)`, every column a typed `DSL.name(column)` field and every value
 * a typed bind, so no text a caller sent ever becomes SQL. Every statement is bounded by what the plan
 * states: a read has a `LIMIT`, a count reads through `LIMIT cap + 1`, and writes are addressed by
 * identifier within the scope.
 *
 * A keyset seek is a row-value comparison when every term shares a direction, which an index on the order's
 * columns serves as its index condition. With mixed directions it is the expanded comparison, led by a
 * non-strict bound on the first column so an index on that column still starts at the cursor.
 */
public class JooqResourceStore<T>(
    override val schema: ResourceSchema,
    private val dsl: DSLContext,
    private val ids: IdGenerator,
    private val reader: RowReader<T>,
) : CrudStore<T> {
    private val table: Table<Record> = DSL.table(DSL.name(schema.table.schema, schema.table.name))
    private val identifier: Field<Any> = column(schema.id)
    private val everyColumn: List<Field<Any>> = schema.fields.map(::column)

    override fun read(read: RowRead): List<KeyedRow<T>> {
        val projected = projected(read.projection).map(::column)
        return dsl.fetch(readQuery(read)).map { record ->
            val whole = Row(record)
            val keys =
                read.order.map { order ->
                    whole.valueOf(order.field)
                        ?: throw RowShapeException("column \"${order.field.column}\" is NULL, but the read is ordered by it")
                }
            KeyedRow(reader.read(Row(record.into(*projected.toTypedArray()))), keys)
        }
    }

    /** The statement [read] runs. */
    public fun readQuery(read: RowRead): Select<Record> {
        val selected = (projected(read.projection) + read.order.map(Order::field)).distinct().map(::column)
        val limited =
            dsl
                .select(selected)
                .from(table)
                .where(conditionOf(read.conditions))
                .orderBy(read.order.map(::sortOf))
                .limit(read.limit)
        return if (read.offset > 0) limited.offset(read.offset) else limited
    }

    override fun countUpTo(
        scope: RowScope,
        filter: Predicate?,
        cap: Long,
    ): Long = checkNotNull(dsl.fetchValue(countQuery(scope, filter, cap))) { "a count answered no row" }

    /** The statement [countUpTo] runs: `SELECT count(*) FROM (SELECT 1 … LIMIT cap + 1)`. */
    public fun countQuery(
        scope: RowScope,
        filter: Predicate?,
        cap: Long,
    ): Select<Record1<Long>> {
        require(cap in 1..Pagination.MAX_COUNT_CAP) { "a count cap is within 1..${Pagination.MAX_COUNT_CAP}, got $cap" }
        val capped =
            dsl
                .selectOne()
                .from(table)
                .where(conditionOf(listOfNotNull(scope.predicate, filter)))
                .limit(cap + 1)
                .asTable("capped")
        return dsl.select(DSL.count().cast(SQLDataType.BIGINT)).from(capped)
    }

    override fun find(
        id: UUID,
        scope: RowScope,
        projection: Projection,
    ): T? =
        dsl
            .select(projected(projection).map(::column))
            .from(table)
            .where(byId(id, scope))
            .fetchOne()
            ?.let { reader.read(Row(it)) }

    override fun insert(values: Map<String, Any?>): T {
        WriteValues.check(schema, values)
        val stated = if (values.containsKey(schema.id.name)) values else values + (schema.id.name to ids.next())
        requireNotNull(stated[schema.id.name]) { "an inserted ${schema.name} has an identifier" }
        val record =
            dsl
                .insertInto(table)
                .set(assignments(stated))
                .returningResult(everyColumn)
                .fetchOne()
        return reader.read(Row(checkNotNull(record) { "inserting into ${schema.table} returned no row" }))
    }

    override fun update(
        id: UUID,
        scope: RowScope,
        values: Map<String, Any?>,
    ): T? {
        require(values.isNotEmpty()) { "an update writes at least one field" }
        WriteValues.check(schema, values)
        return dsl
            .update(table)
            .set(assignments(values))
            .where(byId(id, scope))
            .returningResult(everyColumn)
            .fetchOne()
            ?.let { reader.read(Row(it)) }
    }

    override fun updateMany(
        ids: Set<UUID>,
        scope: RowScope,
        values: Map<String, Any?>,
    ): Long {
        require(values.isNotEmpty()) { "an update writes at least one field" }
        WriteValues.check(schema, values)
        if (ids.isEmpty()) return 0
        return dsl
            .update(table)
            .set(assignments(values))
            .where(byIds(ids, scope))
            .execute()
            .toLong()
    }

    override fun delete(
        id: UUID,
        scope: RowScope,
    ): Long =
        dsl
            .deleteFrom(table)
            .where(byId(id, scope))
            .execute()
            .toLong()

    override fun deleteMany(
        ids: Set<UUID>,
        scope: RowScope,
    ): Long {
        if (ids.isEmpty()) return 0
        return dsl
            .deleteFrom(table)
            .where(byIds(ids, scope))
            .execute()
            .toLong()
    }

    private fun projected(projection: Projection): List<SchemaField> =
        when (projection) {
            Projection.Full -> {
                schema.fields
            }

            is Projection.Only -> {
                require(projection.fields.all(schema::owns)) { "a projection of ${schema.name} names only its fields" }
                projection.fields.toList()
            }
        }

    private fun byId(
        id: UUID,
        scope: RowScope,
    ): Condition = identifier.eq(bind(id, FieldKind.UUID)).and(conditionOf(listOfNotNull(scope.predicate)))

    private fun byIds(
        ids: Set<UUID>,
        scope: RowScope,
    ): Condition = identifier.`in`(ids.map { bind(it, FieldKind.UUID) }).and(conditionOf(listOfNotNull(scope.predicate)))

    private fun assignments(values: Map<String, Any?>): Map<Field<Any>, Field<Any>> =
        values.entries.associate { (name, value) ->
            val field = checkNotNull(schema.field(name))
            column(field) to bindNullable(value, field.kind)
        }

    private fun conditionOf(predicates: List<Predicate>): Condition = DSL.and(predicates.map(::condition))

    private fun condition(predicate: Predicate): Condition =
        when (predicate) {
            is Predicate.Compare -> compare(predicate)
            is Predicate.AllOf -> DSL.and(predicate.of.map(::condition))
            is Predicate.AnyOf -> DSL.or(predicate.of.map(::condition))
            is Predicate.Keyset -> keyset(predicate)
        }

    private fun compare(predicate: Predicate.Compare): Condition {
        require(schema.owns(predicate.field)) { "a predicate over ${schema.name} reads only its fields, not ${predicate.field.name}" }
        val column = column(predicate.field)
        val kind = predicate.field.kind
        val values = predicate.values
        return when (predicate.operator) {
            Operator.EQ -> column.eq(bind(values.single(), kind))
            Operator.NE -> column.ne(bind(values.single(), kind))
            Operator.GT -> column.gt(bind(values.single(), kind))
            Operator.GTE -> column.ge(bind(values.single(), kind))
            Operator.LT -> column.lt(bind(values.single(), kind))
            Operator.LTE -> column.le(bind(values.single(), kind))
            Operator.IN -> column.`in`(values.map { bind(it, kind) })
            Operator.NIN -> column.notIn(values.map { bind(it, kind) })
            Operator.CONTAINS -> text(predicate.field).contains(values.single() as String)
            Operator.ICONTAINS -> text(predicate.field).containsIgnoreCase(values.single() as String)
            Operator.STARTS_WITH -> text(predicate.field).startsWith(values.single() as String)
            Operator.ISTARTS_WITH -> text(predicate.field).startsWithIgnoreCase(values.single() as String)
            Operator.ENDS_WITH -> text(predicate.field).endsWith(values.single() as String)
            Operator.IENDS_WITH -> text(predicate.field).endsWithIgnoreCase(values.single() as String)
            Operator.IS_NULL -> if (values.single() == true) column.isNull else column.isNotNull
        }
    }

    private fun keyset(seek: Predicate.Keyset): Condition {
        require(seek.order.all { schema.owns(it.field) }) { "a keyset over ${schema.name} reads only its fields" }
        val columns = seek.order.map { column(it.field) }
        val values = seek.order.indices.map { bind(seek.values[it], seek.order[it].field.kind) }
        if (seek.order.all { it.direction == seek.order.first().direction }) {
            val left = DSL.row(columns)
            val right = DSL.row(values)
            return if (seek.greaterAt(0)) left.gt(right) else left.lt(right)
        }
        val branches =
            seek.order.indices.map { index ->
                val strict = if (seek.greaterAt(index)) columns[index].gt(values[index]) else columns[index].lt(values[index])
                DSL.and((0 until index).map { columns[it].eq(values[it]) } + strict)
            }
        val leading = if (seek.greaterAt(0)) columns[0].ge(values[0]) else columns[0].le(values[0])
        return leading.and(DSL.or(branches))
    }

    private fun sortOf(order: Order): SortField<Any> =
        if (order.direction ==
            Direction.ASC
        ) {
            column(order.field).asc()
        } else {
            column(order.field).desc()
        }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun column(field: SchemaField): Field<Any> = DSL.field(DSL.name(field.column), dataType(field.kind)) as Field<Any>

        fun text(field: SchemaField): Field<String> = DSL.field(DSL.name(field.column), SQLDataType.VARCHAR)

        fun bind(
            value: Any,
            kind: FieldKind,
        ): Field<Any> = bindNullable(value, kind)

        @Suppress("UNCHECKED_CAST")
        fun bindNullable(
            value: Any?,
            kind: FieldKind,
        ): Field<Any> = DSL.value(sqlValue(value), dataType(kind) as DataType<Any>)

        /** A timestamp is carried as an `Instant` and bound as the `OffsetDateTime` a `timestamptz` takes, in UTC. */
        fun sqlValue(value: Any?): Any? = if (value is Instant) OffsetDateTime.ofInstant(value, ZoneOffset.UTC) else value

        fun dataType(kind: FieldKind): DataType<*> =
            when (kind) {
                FieldKind.TEXT -> SQLDataType.VARCHAR
                FieldKind.BOOLEAN -> SQLDataType.BOOLEAN
                FieldKind.INT -> SQLDataType.INTEGER
                FieldKind.LONG -> SQLDataType.BIGINT
                FieldKind.DECIMAL -> SQLDataType.NUMERIC
                FieldKind.UUID -> SQLDataType.UUID
                FieldKind.TIMESTAMP -> SQLDataType.TIMESTAMPWITHTIMEZONE
                FieldKind.DATE -> SQLDataType.LOCALDATE
            }
    }
}
