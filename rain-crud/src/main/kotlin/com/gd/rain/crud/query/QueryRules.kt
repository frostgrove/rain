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
 * - Cursor pages are served for exactly the sorts in [cursorSorts] (declare the ones an index backs);
 *   none of their fields may be nullable.
 * - [countCap], when declared, lets a query ask for a count bounded by it; without it nothing is counted.
 */
public class Pagination(
    public val defaultLimit: Int,
    public val maxLimit: Int,
    public val maxOffset: Long,
    cursorSorts: Set<SortKey>,
    public val countCap: Long?,
) {
    public val cursorSorts: Set<SortKey> = cursorSorts.toSet()

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
    public val maxSearchFields: Int = 8,
    public val maxSearchLength: Int = 200,
    public val maxBulkIds: Int = 500,
) {
    init {
        mapOf(
            "maxFilterTerms" to maxFilterTerms,
            "maxInValues" to maxInValues,
            "maxSortTerms" to maxSortTerms,
            "maxFields" to maxFields,
            "maxIncludes" to maxIncludes,
            "maxSearchFields" to maxSearchFields,
            "maxSearchLength" to maxSearchLength,
            "maxBulkIds" to maxBulkIds,
        ).forEach { (name, value) -> require(value >= 1) { "$name is at least 1, got $value" } }
    }
}

/**
 * What queries a resource answers: the four allow-lists, the text fields `search` looks in (empty: the
 * resource offers no search), how it pages and the bounds of a query.
 *
 * rain-crud bounds the rows a query returns and counts; what a statement examines to find them is decided
 * by the application's indexes. Grant a field for filtering, sorting or search only where an index serves
 * it — a b-tree for comparisons and orders, a trigram index for the substring operators and `search` —
 * and declare cursor sorts in the column order of an index, identifier last.
 */
public class QueryRules(
    public val filterable: FieldGrant,
    public val sortable: FieldGrant,
    public val selectable: FieldGrant,
    public val includable: FieldGrant,
    searchFields: List<String>,
    public val pagination: Pagination,
    public val limits: QueryLimits = QueryLimits(),
) {
    public val searchFields: List<String> = searchFields.toList()

    /**
     * Every way these rules disagree with [schema] and the resource's [relations], all at once: a grant
     * naming a field the schema does not declare, search fields that are not declared text fields or are
     * more than `maxSearchFields`, and cursor sorts over undeclared, nullable or unsortable fields.
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

        mapOf("filterable" to filterable, "sortable" to sortable, "selectable" to selectable).forEach { (member, grant) ->
            if (grant is FieldGrant.Only) {
                grant.names
                    .filter { schema.field(it) == null }
                    .sorted()
                    .forEach { problem(member, "grants $it, which is not a field") }
            }
        }
        if (includable is FieldGrant.Only) {
            includable.names
                .filterNot(
                    relations::contains,
                ).sorted()
                .forEach { problem("includable", "grants $it, which is not a relation") }
        }

        if (searchFields.size > limits.maxSearchFields) {
            problem("searchFields", "declares ${searchFields.size} fields, more than maxSearchFields (${limits.maxSearchFields})")
        }
        searchFields.groupBy { it }.filterValues { it.size > 1 }.keys.sorted().forEach {
            problem(
                "searchFields",
                "names $it more than once",
            )
        }
        searchFields.distinct().forEach { name ->
            val field = schema.field(name)
            when {
                field == null -> problem("searchFields", "names $name, which is not a field")
                field.kind != FieldKind.TEXT -> problem("searchFields", "names $name, which is a ${field.kind} field, not TEXT")
            }
        }

        pagination.cursorSorts.sortedBy(SortKey::toString).forEach { key ->
            if (key.terms.size > limits.maxSortTerms) {
                problem("cursorSorts", "sort $key has more than maxSortTerms (${limits.maxSortTerms}) terms")
            }
            key.terms.forEach { term ->
                val field = schema.field(term.field)
                when {
                    field == null -> problem("cursorSorts", "sort $key names ${term.field}, which is not a field")
                    field.nullable -> problem("cursorSorts", "sort $key names ${term.field}, which is nullable; a cursor cannot page by it")
                    !sortable.grants(term.field) -> problem("cursorSorts", "sort $key names ${term.field}, which sortable does not grant")
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
