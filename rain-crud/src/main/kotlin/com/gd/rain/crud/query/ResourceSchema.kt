package com.gd.rain.crud.query

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What a column holds, which decides how a wire value is read, which operators apply, and the one
 * Kotlin type a value of that kind is carried as everywhere in rain-crud.
 */
public enum class FieldKind(
    /** Whether `gt`/`gte`/`lt`/`lte` and ordering apply. */
    public val ordered: Boolean,
    /** The type every value of this kind is carried as. */
    public val valueType: Class<*>,
) {
    TEXT(true, String::class.java),
    BOOLEAN(false, Boolean::class.javaObjectType),
    INT(true, Int::class.javaObjectType),
    LONG(true, Long::class.javaObjectType),
    DECIMAL(true, BigDecimal::class.java),
    UUID(false, java.util.UUID::class.java),

    /** `timestamptz`, carried as an [Instant]. */
    TIMESTAMP(true, Instant::class.java),

    /** `date`, carried as a [LocalDate]. */
    DATE(true, LocalDate::class.java),
    ;

    public fun accepts(value: Any): Boolean = valueType.isInstance(value)
}

/**
 * One field of a resource: the [name] the wire uses (matched exactly), the [column] statements use, its
 * [kind], and whether the column may hold NULL. Nothing is inferred: nullability is stated.
 */
public data class SchemaField(
    public val name: String,
    public val column: String,
    public val kind: FieldKind,
    public val nullable: Boolean,
) {
    init {
        require(NAME.matches(name)) { "field name \"$name\" does not match ${NAME.pattern}" }
        require(COLUMN.matches(column)) { "column \"$column\" of field $name does not match ${COLUMN.pattern}" }
    }

    public companion object {
        /** Wire names cannot contain the dialect's structure (`[`, `]`, `,`, a leading `-`). */
        public val NAME: Regex = Regex("^[A-Za-z][A-Za-z0-9_]{0,62}$")
        public val COLUMN: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")
    }
}

/** A table qualified by the schema the application owns it in; both parts are stated. */
public data class TableName(
    public val schema: String,
    public val name: String,
) {
    init {
        require(SchemaField.COLUMN.matches(schema)) { "schema \"$schema\" does not match ${SchemaField.COLUMN.pattern}" }
        require(SchemaField.COLUMN.matches(name)) { "table \"$name\" does not match ${SchemaField.COLUMN.pattern}" }
    }

    override fun toString(): String = "$schema.$name"
}

/**
 * A resource's shape: its table, its identifier, its other fields and, when it has one, its version. The identifier
 * is a non-null UUID column, which is what makes it a total tie-break for every order.
 *
 * [version] is `null` for a resource without optimistic versioning, or one of [fields]: a non-nullable `LONG` field
 * the store writes itself — 1 on insert, one more on every update — and that a write by identifier states as the
 * version it read.
 *
 * Lookups are by exact name through a map, so resolving a field costs the same however many fields a
 * resource declares.
 */
public class ResourceSchema(
    public val name: String,
    public val table: TableName,
    public val id: SchemaField,
    fields: List<SchemaField>,
    public val version: SchemaField?,
) {
    /** The identifier first, then the other fields in declaration order. */
    public val fields: List<SchemaField> = listOf(id) + fields

    private val byName: Map<String, SchemaField> = this.fields.associateBy(SchemaField::name)

    init {
        require(SchemaField.NAME.matches(name)) { "resource name \"$name\" does not match ${SchemaField.NAME.pattern}" }
        require(id.kind == FieldKind.UUID && !id.nullable) { "the identifier of $name is a non-nullable UUID field" }
        val names =
            this.fields
                .groupBy { it.name }
                .filterValues { it.size > 1 }
                .keys
        require(names.isEmpty()) { "resource $name declares the field names ${names.sorted()} more than once" }
        val columns =
            this.fields
                .groupBy { it.column }
                .filterValues { it.size > 1 }
                .keys
        require(columns.isEmpty()) { "resource $name maps the columns ${columns.sorted()} more than once" }
        if (version != null) {
            require(
                version != id && byName[version.name] == version,
            ) { "the version of $name is one of its fields other than the identifier" }
            require(version.kind == FieldKind.LONG && !version.nullable) { "the version of $name is a non-nullable LONG field" }
        }
    }

    /** The field named exactly [name], or `null`. */
    public fun field(name: String): SchemaField? = byName[name]

    /** Whether [field] is this schema's own declaration of that field. */
    public fun owns(field: SchemaField): Boolean = byName[field.name] == field

    /** The version a store writes into a row it inserts. */
    public val initialVersion: Long get() = INITIAL_VERSION

    override fun toString(): String = "ResourceSchema($name over $table)"

    public companion object {
        /** The version of an inserted row, when the schema declares one. */
        public const val INITIAL_VERSION: Long = 1

        /** A UUID field's value in canonical 8-4-4-4-12 form, as the wire and ids use it. */
        public val CANONICAL_UUID: Regex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Reads a canonical UUID, or `null` for any other spelling. */
        public fun canonicalUuid(raw: String): UUID? = if (CANONICAL_UUID.matches(raw)) UUID.fromString(raw) else null
    }
}
