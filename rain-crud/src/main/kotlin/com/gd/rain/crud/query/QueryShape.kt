package com.gd.rain.crud.query

/** One filter of a query shape: a field, by its wire name, and the operator a request applies to it. */
public data class ShapeFilter(
    public val field: String,
    public val operator: Operator,
) : Comparable<ShapeFilter> {
    init {
        require(SchemaField.NAME.matches(field)) { "filter field \"$field\" does not match ${SchemaField.NAME.pattern}" }
    }

    /** By field, then by the operator's wire spelling: the order a request's filters are conjoined in. */
    override fun compareTo(other: ShapeFilter): Int = compareValuesBy(this, other, { it.field }, { it.operator.wire })

    override fun toString(): String = "filter[$field][${operator.wire}]"
}

/**
 * A list query a resource answers, stated exactly: the [filters] a request gives — each a field and the
 * operator applied to it — and the one [sort] its pages follow.
 *
 * Matching rules, all exact:
 * - A list request is served by the declared shape whose filters are exactly the request's
 *   `filter[<field>][<op>]` parameters and whose sort is exactly the request's `sort` (a request without
 *   `sort` asks for [SortKey.NONE]). Cursor and offset pages both follow that sort, with the identifier as
 *   tie-break. There is no nearest shape: any other request is `400 not_offered`, naming what was asked.
 * - A count (`count=capped` on a list, or the count route) is served when a declared shape has exactly its
 *   filters, whatever that shape's sort: a count has no order, so every such shape states the same count.
 * - Two shapes accept the same request only when they are the same shape, which a resource refuses to
 *   declare twice.
 *
 * A shape does not name the row scope; every statement carries it. Declaring a shape is declaring that an
 * index serves it under the scopes the application uses, and `CrudPlanProof` (rain-crud's test fixtures)
 * proves that against the application's database.
 */
public class QueryShape(
    filters: Set<ShapeFilter>,
    public val sort: SortKey,
) {
    /** The filters in [ShapeFilter] order. */
    public val filters: Set<ShapeFilter> = filters.toSortedSet()

    /**
     * The filter a request of this shape compiles to: each filter compared with its [values], conjoined in
     * [ShapeFilter] order; `null` for a shape without filters.
     */
    public fun filter(
        schema: ResourceSchema,
        values: Map<ShapeFilter, List<Any>>,
    ): Predicate? {
        require(values.keys == filters) { "values are given for exactly the filters of $this, not for ${values.keys.sorted()}" }
        return Predicate.allOf(
            filters.map { filter ->
                val field = requireNotNull(schema.field(filter.field)) { "resource ${schema.name} has no field ${filter.field}" }
                Predicate.Compare(field, filter.operator, values.getValue(filter))
            },
        )
    }

    override fun equals(other: Any?): Boolean = other is QueryShape && other.filters == filters && other.sort == sort

    override fun hashCode(): Int = 31 * filters.hashCode() + sort.hashCode()

    override fun toString(): String = "filters ${filters.joinToString(", ", "[", "]")}, sort $sort"

    public companion object {
        /** `QueryShape.of(SortKey.parse("-createdAt"), "shelf" to Operator.EQ)`; each filter is named once. */
        public fun of(
            sort: SortKey,
            vararg filters: Pair<String, Operator>,
        ): QueryShape {
            val stated = filters.map { (field, operator) -> ShapeFilter(field, operator) }
            val repeated = stated.groupBy { it }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "a shape names each filter once, but names ${repeated.sorted()} more than once" }
            return QueryShape(stated.toSet(), sort)
        }
    }
}
