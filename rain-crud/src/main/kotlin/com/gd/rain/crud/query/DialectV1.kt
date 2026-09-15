package com.gd.rain.crud.query

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import com.gd.rain.crud.error.RainCrudErrorCodes

/** One `filter[<field>][<op>]` parameter and every value it was given, in order. */
public class FilterParameter(
    public val field: String,
    public val operator: String,
    values: List<String>,
) {
    public val values: List<String> = values.toList()
}

/** A request's dialect v1 parameters: each scalar with its one value, and the filters in parameter-name order. */
public class QueryParameters(
    scalars: Map<String, String>,
    filters: List<FilterParameter>,
) {
    public val scalars: Map<String, String> = scalars.toMap()
    public val filters: List<FilterParameter> = filters.toList()

    public companion object {
        public val NONE: QueryParameters = QueryParameters(emptyMap(), emptyList())
    }
}

/**
 * Query dialect v1 — the query parameters every rain-crud resource reads.
 *
 * The parameters are exactly `limit`, `offset`, `cursor`, `sort`, `fields`, `include`, `count` and
 * `filter[<field>][<op>]`. Names are compared exactly: there are no aliases, no near-miss matching and
 * no case folding. Any other parameter is refused with `400 unknown_parameter`, one violation naming each.
 * A scalar is given at most once; a filter parameter is repeated only to give a list operator several
 * values. Values are checked when the query is compiled against a resource ([QueryCompiler]).
 */
public object DialectV1 {
    public const val VERSION: Int = 1

    public const val LIMIT: String = "limit"
    public const val OFFSET: String = "offset"
    public const val CURSOR: String = "cursor"
    public const val SORT: String = "sort"
    public const val FIELDS: String = "fields"
    public const val INCLUDE: String = "include"
    public const val COUNT: String = "count"
    public const val FILTER: String = "filter"

    /** The one value of `count`: a count bounded by the resource's declared cap. */
    public const val COUNT_CAPPED: String = "capped"

    public val SCALAR_PARAMETERS: Set<String> = setOf(LIMIT, OFFSET, CURSOR, SORT, FIELDS, INCLUDE, COUNT)

    private val FILTER_PARAMETER = Regex("^filter\\[([^\\[\\]]+)]\\[([^\\[\\]]+)]$")

    public fun parse(parameters: Map<String, List<String>>): QueryParameters {
        val names = parameters.keys.sorted()
        val unknown = names.filter { it !in SCALAR_PARAMETERS && !FILTER_PARAMETER.matches(it) }
        if (unknown.isNotEmpty()) throw QueryFaults.unknownParameters(unknown)

        val violations = mutableListOf<Violation>()
        val scalars = linkedMapOf<String, String>()
        val filters = mutableListOf<FilterParameter>()
        names.forEach { name ->
            val values = parameters.getValue(name)
            val filter = FILTER_PARAMETER.matchEntire(name)
            when {
                filter != null && values.isNotEmpty() -> {
                    filters += FilterParameter(filter.groupValues[1], filter.groupValues[2], values)
                }

                filter != null -> {
                    violations +=
                        Violation.at(
                            path(FILTER, filter.groupValues[1], filter.groupValues[2]),
                            RainErrorCodes.BAD_QUERY,
                            "is given no value",
                        )
                }

                values.size == 1 -> {
                    scalars[name] = values.single()
                }

                else -> {
                    violations +=
                        Violation.at(path(name), RainErrorCodes.BAD_QUERY, "is given ${values.size} times; it is given at most once")
                }
            }
        }
        if (violations.isNotEmpty()) throw QueryFaults.badQuery(violations)
        return QueryParameters(scalars, filters)
    }
}

/** The refusals of the dialect, in the order they are decided: parameter names, then the query, then its shape, then the cursor. */
internal object QueryFaults {
    fun unknownParameters(names: List<String>): Fault =
        Fault(
            FaultKind.BAD_REQUEST,
            RainErrorCodes.UNKNOWN_PARAMETER,
            violations = names.map { Violation.at(path(it), RainErrorCodes.UNKNOWN_PARAMETER, "is not a parameter of query dialect v1") },
        )

    fun badQuery(violations: List<Violation>): Fault = Fault(FaultKind.BAD_REQUEST, RainErrorCodes.BAD_QUERY, violations = violations)

    fun notOffered(message: String): Fault = Fault(FaultKind.BAD_REQUEST, RainCrudErrorCodes.NOT_OFFERED, message)

    fun invalidCursor(reason: String): Fault =
        Fault(
            FaultKind.BAD_REQUEST,
            RainCrudErrorCodes.INVALID_CURSOR,
            violations = listOf(Violation.at(path(DialectV1.CURSOR), RainCrudErrorCodes.INVALID_CURSOR, "the cursor $reason")),
        )
}
