package com.gd.rain.crud.query

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode

/** One requested sort term, by wire name: `title` ascending, `-title` descending. */
public data class SortTerm(
    public val field: String,
    public val direction: Direction,
) {
    init {
        require(SchemaField.NAME.matches(field)) { "sort field \"$field\" does not match ${SchemaField.NAME.pattern}" }
    }

    override fun toString(): String = if (direction == Direction.DESC) "-$field" else field
}

/**
 * A requested sort, as the `sort` parameter spells it: `-createdAt,title`. [NONE] is the request that
 * names no sort; its effective order is the identifier ascending.
 */
public class SortKey private constructor(
    public val terms: List<SortTerm>,
) {
    override fun equals(other: Any?): Boolean = other is SortKey && other.terms == terms

    override fun hashCode(): Int = terms.hashCode()

    override fun toString(): String = if (terms.isEmpty()) "(none)" else terms.joinToString(",")

    public companion object {
        public val NONE: SortKey = SortKey(emptyList())

        public fun of(terms: List<SortTerm>): SortKey {
            val repeated = terms.groupBy(SortTerm::field).filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "a sort names each field once, but names ${repeated.sorted()} more than once" }
            return SortKey(terms.toList())
        }

        /** Reads the `sort` grammar; an empty text is refused (say [NONE]). */
        public fun parse(text: String): SortKey =
            when (val parsed = QueryGrammar.sort(text)) {
                is QueryGrammar.Outcome.Read -> of(parsed.value)
                is QueryGrammar.Outcome.Malformed -> throw IllegalArgumentException("sort \"$text\" ${parsed.reason}")
            }
    }
}

/**
 * How a resource pages.
 *
 * - [defaultLimit] serves a request without `limit`; a `limit` above [maxLimit] is refused, never clamped.
 * - Offset pages are served while `offset + limit ≤ maxOffset`.
 * - Cursor and offset pages follow the order of the query shape a request matches ([QueryShape]).
 * - [countCap], when declared, lets a query ask for a count bounded by it; without it nothing is counted.
 */
public class Pagination(
    public val defaultLimit: Int,
    public val maxLimit: Int,
    public val maxOffset: Long,
    public val countCap: Long?,
) {
    init {
        require(maxLimit in 1 until Int.MAX_VALUE) { "maxLimit is within 1..${Int.MAX_VALUE - 1}, got $maxLimit" }
        require(defaultLimit in 1..maxLimit) { "defaultLimit is within 1..maxLimit ($maxLimit), got $defaultLimit" }
        require(maxOffset >= 0) { "maxOffset is not negative, got $maxOffset" }
        require(countCap == null || countCap in 1..MAX_COUNT_CAP) { "countCap is within 1..$MAX_COUNT_CAP, got $countCap" }
    }

    public companion object {
        /** A count reads at most `countCap + 1` rows, which has to be a `Long`. */
        public const val MAX_COUNT_CAP: Long = Long.MAX_VALUE - 1
    }
}

/**
 * The bounds of one query. These are declared tuning numbers — each one bounds the work a single request
 * can ask for — and every one of them is at least 1.
 */
public data class QueryLimits(
    public val maxFilterTerms: Int = 16,
    public val maxInValues: Int = 100,
    public val maxSortTerms: Int = 4,
    public val maxFields: Int = 64,
    public val maxIncludes: Int = 8,
    public val maxBulkIds: Int = 500,
) {
    init {
        mapOf(
            "maxFilterTerms" to maxFilterTerms,
            "maxInValues" to maxInValues,
            "maxSortTerms" to maxSortTerms,
            "maxFields" to maxFields,
            "maxIncludes" to maxIncludes,
            "maxBulkIds" to maxBulkIds,
        ).forEach { (name, value) -> require(value >= 1) { "$name is at least 1, got $value" } }
    }
}

/**
 * What queries a resource answers: the [shapes] of its lists and counts, the fields `fields` may select, the
 * relations `include` may name, how it pages and the bounds of one query.
 *
 * The pagination bounds the rows a statement returns and counts. The rows it examines are bounded by the
 * shapes: a resource answers exactly the declared shapes, each of which the application backs with an
 * index, and `CrudPlanProof` (rain-crud's test fixtures) proves every declared shape bounded under the
 * scopes the application states. Looking a request's shape up costs the same however many shapes a resource
 * declares.
 */
public class QueryRules(
    shapes: List<QueryShape>,
    public val selectable: FieldGrant,
    public val includable: FieldGrant,
    public val pagination: Pagination,
    public val limits: QueryLimits = QueryLimits(),
) {
    public val shapes: List<QueryShape> = shapes.toList()

    private val byRequest: Map<QueryShape, QueryShape> = this.shapes.associateBy { it }
    private val countable: Set<Set<ShapeFilter>> = this.shapes.mapTo(HashSet()) { it.filters }

    /** The declared shape that is exactly [filters] sorted by [sort], or `null`. */
    public fun shapeOf(
        filters: Set<ShapeFilter>,
        sort: SortKey,
    ): QueryShape? = byRequest[QueryShape(filters, sort)]

    /** Whether a declared shape has exactly [filters], which is what a count needs. */
    public fun countsBy(filters: Set<ShapeFilter>): Boolean = filters in countable

    /**
     * Every way these rules disagree with [schema] and the resource's [relations], all at once: a grant
     * naming a field or relation that is not declared, a shape declared twice, and a shape that filters by an
     * undeclared field, applies an operator its field's kind does not take, has more filters or sort terms
     * than the limits allow, or sorts by an undeclared or nullable field or by one [selectable] does not grant.
     *
     * A sort field must be selectable because a cursor carries the boundary row's value of every sort field in
     * the clear: what a caller may select is then all a cursor can show it.
     */
    public fun problems(
        schema: ResourceSchema,
        relations: Set<String>,
    ): List<ConfigurationProblem> {
        val problems = mutableListOf<ConfigurationProblem>()

        fun problem(
            member: String,
            message: String,
        ) {
            problems += ConfigurationProblem("crud:${schema.name}.$member", ProblemCode.INVALID, message)
        }

        if (selectable is FieldGrant.Only) {
            selectable.names
                .filter { schema.field(it) == null }
                .sorted()
                .forEach { problem("selectable", "grants $it, which is not a field") }
        }
        if (includable is FieldGrant.Only) {
            includable.names
                .filterNot(relations::contains)
                .sorted()
                .forEach { problem("includable", "grants $it, which is not a relation") }
        }

        shapes.groupBy { it }.filterValues { it.size > 1 }.forEach { (shape, declared) ->
            problems +=
                ConfigurationProblem(
                    "crud:${schema.name}.shapes",
                    ProblemCode.CONTRADICTS,
                    "declares the shape $shape ${declared.size} times",
                )
        }
        shapes.distinct().forEach { shape ->
            if (shape.filters.size > limits.maxFilterTerms) {
                problem("shapes", "shape $shape has ${shape.filters.size} filters, more than maxFilterTerms (${limits.maxFilterTerms})")
            }
            shape.filters.forEach { filter ->
                val field = schema.field(filter.field)
                val refusal = field?.let(filter.operator::refusalFor)
                when {
                    field == null -> problem("shapes", "shape $shape filters by ${filter.field}, which is not a field")
                    refusal != null -> problem("shapes", "shape $shape cannot filter: $refusal")
                }
            }
            if (shape.sort.terms.size > limits.maxSortTerms) {
                problem("shapes", "shape $shape sorts by more than maxSortTerms (${limits.maxSortTerms}) terms")
            }
            shape.sort.terms.forEach { term ->
                val field = schema.field(term.field)
                when {
                    field == null -> {
                        problem("shapes", "shape $shape sorts by ${term.field}, which is not a field")
                    }

                    field.nullable -> {
                        problem(
                            "shapes",
                            "shape $shape sorts by ${term.field}, which is nullable; a cursor cannot page by it",
                        )
                    }

                    !selectable.grants(term.field) -> {
                        problem(
                            "shapes",
                            "shape $shape sorts by ${term.field}, which selectable does not grant; a cursor carries the value of " +
                                "every sort field",
                        )
                    }
                }
            }
        }
        return problems
    }
}

/** The small grammars of dialect v1's list parameters, shared by declarations and requests. */
internal object QueryGrammar {
    sealed interface Outcome<out T> {
        data class Read<T>(
            val value: T,
        ) : Outcome<T>

        data class Malformed(
            val reason: String,
        ) : Outcome<Nothing>
    }

    /** `name,name,…`: at least one name, each matching the field-name rule, none twice. */
    fun names(text: String): Outcome<List<String>> {
        val names = text.split(',')
        return when {
            names.any { !SchemaField.NAME.matches(it) } -> Outcome.Malformed("is not a comma-separated list of names")
            names.size != names.toSet().size -> Outcome.Malformed("names an entry more than once")
            else -> Outcome.Read(names)
        }
    }

    /** `term,term,…` where a term is `name` (ascending) or `-name` (descending). */
    fun sort(text: String): Outcome<List<SortTerm>> {
        val terms =
            text.split(',').map { entry ->
                val descending = entry.startsWith('-')
                val name = if (descending) entry.substring(1) else entry
                if (!SchemaField.NAME.matches(name)) return Outcome.Malformed("is not a comma-separated list of name or -name terms")
                SortTerm(name, if (descending) Direction.DESC else Direction.ASC)
            }
        if (terms.map(SortTerm::field).toSet().size != terms.size) return Outcome.Malformed("names a field more than once")
        return Outcome.Read(terms)
    }
}
