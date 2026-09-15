package com.gd.rain.crud.query

public enum class Direction {
    ASC,
    DESC,
    ;

    public fun inverted(): Direction = if (this == ASC) DESC else ASC
}

/** One term of an order: a resolved field and its direction. */
public data class Order(
    public val field: SchemaField,
    public val direction: Direction,
)

/**
 * The operators of dialect v1, spelled exactly as [wire]. There are no aliases and no case folding:
 * `EQ` is not `eq`.
 */
public enum class Operator(
    public val wire: String,
) {
    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IN("in"),
    NIN("nin"),
    CONTAINS("contains"),
    ICONTAINS("icontains"),
    STARTS_WITH("startswith"),
    ISTARTS_WITH("istartswith"),
    ENDS_WITH("endswith"),
    IENDS_WITH("iendswith"),
    IS_NULL("isnull"),
    ;

    /** `in` and `nin` take one value per repetition of their parameter; every other operator takes exactly one. */
    public val takesList: Boolean get() = this == IN || this == NIN

    /** The operators that match text patterns; they apply to `TEXT` fields only. */
    public val textual: Boolean get() = this in TEXTUAL

    /** The comparisons that need an ordered kind. */
    public val ordering: Boolean get() = this == GT || this == GTE || this == LT || this == LTE

    /**
     * Whether this operator means something on [field]. `isnull` applies to nullable fields only: on a
     * column that cannot hold NULL it would be a constant, which is a query nobody meant to write.
     */
    public fun appliesTo(field: SchemaField): Boolean =
        when {
            textual -> field.kind == FieldKind.TEXT
            ordering -> field.kind.ordered
            this == IS_NULL -> field.nullable
            else -> true
        }

    public companion object {
        private val TEXTUAL = setOf(CONTAINS, ICONTAINS, STARTS_WITH, ISTARTS_WITH, ENDS_WITH, IENDS_WITH)
        private val BY_WIRE: Map<String, Operator> = entries.associateBy(Operator::wire)

        /** The operator spelled exactly [wire], or `null`. */
        public fun ofWire(wire: String): Operator? = BY_WIRE[wire]
    }
}

/**
 * A condition over one resource's fields, with every value already carried as its kind's type.
 *
 * Comparisons follow SQL: a comparison never matches a NULL column value (`ne` and `nin` included); only
 * `isnull` matches NULL. Invariants are checked at construction, so a store never has to decide what an
 * incoherent predicate meant.
 */
public sealed interface Predicate {
    /** Every field this predicate reads. */
    public fun fields(): Sequence<SchemaField>

    public class Compare(
        public val field: SchemaField,
        public val operator: Operator,
        values: List<Any>,
    ) : Predicate {
        /** For `isnull`, one Boolean: `true` matches NULL, `false` matches a value. */
        public val values: List<Any> = values.toList()

        init {
            require(operator.appliesTo(field)) { "operator ${operator.wire} does not apply to ${field.kind} field ${field.name}" }
            when {
                operator == Operator.IS_NULL -> {
                    require(this.values.size == 1 && this.values[0] is Boolean) { "isnull on ${field.name} takes one Boolean" }
                }

                operator.takesList -> {
                    require(this.values.isNotEmpty()) { "${operator.wire} on ${field.name} takes at least one value" }
                }

                else -> {
                    require(this.values.size == 1) { "${operator.wire} on ${field.name} takes exactly one value" }
                }
            }
            if (operator != Operator.IS_NULL) {
                require(this.values.all(field.kind::accepts)) { "a value for ${field.name} is a ${field.kind.valueType.simpleName}" }
            }
        }

        override fun fields(): Sequence<SchemaField> = sequenceOf(field)

        override fun toString(): String = "${field.name} ${operator.wire} $values"
    }

    public class AllOf(
        of: List<Predicate>,
    ) : Predicate {
        public val of: List<Predicate> = of.toList()

        init {
            require(this.of.size >= 2) { "a conjunction joins at least two predicates" }
        }

        override fun fields(): Sequence<SchemaField> = of.asSequence().flatMap(Predicate::fields)

        override fun toString(): String = of.joinToString(" AND ", "(", ")")
    }

    public class AnyOf(
        of: List<Predicate>,
    ) : Predicate {
        public val of: List<Predicate> = of.toList()

        init {
            require(this.of.size >= 2) { "a disjunction joins at least two predicates" }
        }

        override fun fields(): Sequence<SchemaField> = of.asSequence().flatMap(Predicate::fields)

        override fun toString(): String = of.joinToString(" OR ", "(", ")")
    }

    /**
     * The rows strictly [Side.AFTER] or strictly [Side.BEFORE] the row whose values under [order] are
     * [values]. Every field of the order is non-nullable, so the comparison is never unknown.
     */
    public class Keyset(
        order: List<Order>,
        values: List<Any>,
        public val side: Side,
    ) : Predicate {
        public val order: List<Order> = order.toList()
        public val values: List<Any> = values.toList()

        init {
            require(this.order.isNotEmpty()) { "a keyset compares at least one field" }
            require(this.order.size == this.values.size) { "a keyset has one value per order term" }
            require(this.order.none { it.field.nullable }) { "a keyset never compares a nullable field" }
            require(
                this.order.indices.all {
                    this.order[it]
                        .field.kind
                        .accepts(this.values[it])
                },
            ) { "keyset values match their fields' kinds" }
        }

        /** Whether rows on this side of the key have a strictly greater value in term [index]. */
        public fun greaterAt(index: Int): Boolean = (order[index].direction == Direction.ASC) == (side == Side.AFTER)

        override fun fields(): Sequence<SchemaField> = order.asSequence().map(Order::field)

        override fun toString(): String = "keyset ${side.name.lowercase()} $values by $order"
    }

    public enum class Side { AFTER, BEFORE }

    public companion object {
        public fun eq(
            field: SchemaField,
            value: Any,
        ): Predicate = Compare(field, Operator.EQ, listOf(value))

        public fun isNull(field: SchemaField): Predicate = Compare(field, Operator.IS_NULL, listOf(true))

        public fun isNotNull(field: SchemaField): Predicate = Compare(field, Operator.IS_NULL, listOf(false))

        /** The conjunction of [of]: `null` for none, the predicate itself for one. */
        public fun allOf(of: List<Predicate>): Predicate? =
            when (of.size) {
                0 -> null
                1 -> of.single()
                else -> AllOf(of)
            }

        /** The disjunction of [of]: `null` for none, the predicate itself for one. */
        public fun anyOf(of: List<Predicate>): Predicate? =
            when (of.size) {
                0 -> null
                1 -> of.single()
                else -> AnyOf(of)
            }
    }
}
