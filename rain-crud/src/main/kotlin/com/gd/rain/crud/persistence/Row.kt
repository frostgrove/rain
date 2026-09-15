package com.gd.rain.crud.persistence

import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
import org.jooq.Field
import org.jooq.Record
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** A row does not have the shape a reader asked for: a column it does not carry, a NULL where a value is required, or another type. */
public class RowShapeException(
    message: String,
) : IllegalStateException(message)

/**
 * One row, read by exact column label.
 *
 * A column the statement did not select is not a NULL: asking for it throws [RowShapeException], and so
 * does asking for a column spelled in another case. Each accessor answers `null` only for a SQL NULL; its
 * `required*` twin throws on NULL. Values are not converted: a column holds the accessor's type or the
 * read throws.
 */
public class Row internal constructor(
    private val record: Record,
) {
    /** The labels this row carries, in selection order. */
    public val columns: List<String> get() = record.fields().map(Field<*>::getName)

    public fun has(column: String): Boolean = fieldOf(column) != null

    public fun string(column: String): String? = typed(column, String::class.java)

    public fun requiredString(column: String): String = required(column, string(column))

    public fun boolean(column: String): Boolean? = typed(column, Boolean::class.javaObjectType)

    public fun requiredBoolean(column: String): Boolean = required(column, boolean(column))

    public fun int(column: String): Int? = typed(column, Int::class.javaObjectType)

    public fun requiredInt(column: String): Int = required(column, int(column))

    public fun long(column: String): Long? = typed(column, Long::class.javaObjectType)

    public fun requiredLong(column: String): Long = required(column, long(column))

    public fun decimal(column: String): BigDecimal? = typed(column, BigDecimal::class.java)

    public fun requiredDecimal(column: String): BigDecimal = required(column, decimal(column))

    public fun uuid(column: String): UUID? = typed(column, UUID::class.java)

    public fun requiredUuid(column: String): UUID = required(column, uuid(column))

    /** A `timestamptz` column, read as an `OffsetDateTime` and answered as the instant it names. */
    public fun instant(column: String): Instant? = typed(column, OffsetDateTime::class.java)?.toInstant()

    public fun requiredInstant(column: String): Instant = required(column, instant(column))

    public fun date(column: String): LocalDate? = typed(column, LocalDate::class.java)

    public fun requiredDate(column: String): LocalDate = required(column, date(column))

    /** The value of [field]'s column as its kind's type, or `null` for a SQL NULL. */
    public fun valueOf(field: SchemaField): Any? =
        when (field.kind) {
            FieldKind.TEXT -> string(field.column)
            FieldKind.BOOLEAN -> boolean(field.column)
            FieldKind.INT -> int(field.column)
            FieldKind.LONG -> long(field.column)
            FieldKind.DECIMAL -> decimal(field.column)
            FieldKind.UUID -> uuid(field.column)
            FieldKind.TIMESTAMP -> instant(field.column)
            FieldKind.DATE -> date(field.column)
        }

    private fun <V : Any> typed(
        column: String,
        type: Class<V>,
    ): V? {
        val field = fieldOf(column) ?: throw RowShapeException("the row has no column \"$column\"; it has $columns")
        val value = record.get(field) ?: return null
        if (!type.isInstance(value)) throw RowShapeException("column \"$column\" holds a ${value.javaClass.name}, not a ${type.name}")
        return type.cast(value)
    }

    private fun <V : Any> required(
        column: String,
        value: V?,
    ): V = value ?: throw RowShapeException("column \"$column\" is NULL where a value is required")

    private fun fieldOf(column: String): Field<*>? = record.fields().firstOrNull { it.name == column }
}

/**
 * Turns a [Row] into a resource's item, and declares the fields it reads.
 *
 * [reads] is every field [read] asks the row for. A store answers a projection only when it holds all of them, so a
 * query whose `fields` would leave one out is refused as `400 bad_query` when it is compiled, never discovered as a
 * [RowShapeException] while a row is read.
 */
public interface RowReader<T> {
    /** The fields [read] reads from every row. */
    public val reads: Set<SchemaField>

    public fun read(row: Row): T

    public companion object {
        /** A reader of the stated [reads] fields; [read] asks the row for no other column. */
        public fun <T> of(
            reads: Set<SchemaField>,
            read: (Row) -> T,
        ): RowReader<T> {
            val declared = reads.toSet()
            return object : RowReader<T> {
                override val reads: Set<SchemaField> = declared

                override fun read(row: Row): T = read(row)
            }
        }

        /**
         * Items as maps from field name to value, holding exactly the fields the row carries — so a query
         * that names `fields` answers those fields and the identifier, and nothing else. It reads the identifier,
         * which every projection holds, and asks for no other field the row does not carry.
         */
        public fun fields(schema: ResourceSchema): RowReader<Map<String, Any?>> =
            of(setOf(schema.id)) { row ->
                schema.fields.filter { row.has(it.column) }.associateTo(LinkedHashMap()) { it.name to row.valueOf(it) }
            }
    }
}
