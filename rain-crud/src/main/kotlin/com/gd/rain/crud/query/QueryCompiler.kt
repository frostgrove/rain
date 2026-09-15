package com.gd.rain.crud.query

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.PathStep
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import com.gd.rain.crud.error.RainCrudErrorCodes
import com.gd.rain.crud.query.DialectV1.COUNT
import com.gd.rain.crud.query.DialectV1.COUNT_CAPPED
import com.gd.rain.crud.query.DialectV1.CURSOR
import com.gd.rain.crud.query.DialectV1.FIELDS
import com.gd.rain.crud.query.DialectV1.FILTER
import com.gd.rain.crud.query.DialectV1.INCLUDE
import com.gd.rain.crud.query.DialectV1.LIMIT
import com.gd.rain.crud.query.DialectV1.OFFSET
import com.gd.rain.crud.query.DialectV1.SORT

/** Which columns a read returns: every field, or the identifier plus the named ones. */
public sealed interface Projection {
    public data object Full : Projection

    public class Only(
        fields: Set<SchemaField>,
    ) : Projection {
        public val fields: Set<SchemaField> = LinkedHashSet(fields)

        init {
            require(this.fields.isNotEmpty()) { "a projection names at least one field" }
        }
    }
}

/** Which slice of the ordered rows a list page is. */
public sealed interface Window {
    public val limit: Int

    /** A keyset page: the first one when [position] is `null`. */
    public class Cursor(
        override val limit: Int,
        public val position: CursorPosition?,
    ) : Window

    public class Offset(
        override val limit: Int,
        public val offset: Long,
    ) : Window
}

/**
 * A compiled list query: the declared [shape] it matched, its [filter], and [order] — the effective order,
 * the shape's sort with the identifier as tie-break.
 */
public class ListPlan(
    public val shape: QueryShape,
    public val filter: Predicate?,
    order: List<Order>,
    public val projection: Projection,
    includes: List<String>,
    public val window: Window,
    public val counted: Boolean,
) {
    public val order: List<Order> = order.toList()
    public val includes: List<String> = includes.toList()
}

public class CountPlan(
    public val filter: Predicate?,
)

public class ItemPlan(
    public val projection: Projection,
    includes: List<String>,
) {
    public val includes: List<String> = includes.toList()
}

/**
 * Compiles dialect v1 parameters against one resource's schema and [QueryRules].
 *
 * Every problem of a query is collected and refused together as `400 bad_query`, each violation pointing
 * at the parameter it is about (`/limit`, `/filter/<field>/<op>`, `/filter/<field>/in/2`). Once the query
 * is sound, its shape is looked up and a query no declared shape is refused on its own as `400 not_offered`;
 * only then is a cursor decoded, refused on its own as `400 invalid_cursor`.
 *
 * Rules, all deterministic:
 * - `limit` absent serves `defaultLimit`; a limit outside `1..maxLimit` is refused.
 * - `offset` selects offset mode and cannot be combined with `cursor`; `offset + limit` above `maxOffset`
 *   (computed in `Long`) is refused. Without `offset` the page is a cursor page.
 * - A filter names a declared field and an operator of the dialect that applies to the field's kind; its
 *   values are read by [WireValues].
 * - The filters and the sort are exactly a declared [QueryShape] ([QueryRules.shapeOf]); a count's filters
 *   are exactly the filters of a declared shape ([QueryRules.countsBy]).
 * - The effective order is the shape's sort followed by the identifier in the last term's direction
 *   (ascending when the sort is [SortKey.NONE]), unless the sort already names the identifier.
 * - `fields` always returns the identifier too, and may name it without it being selectable.
 */
public class QueryCompiler(
    private val schema: ResourceSchema,
    private val rules: QueryRules,
    private val relations: Set<String>,
) {
    init {
        val problems = rules.problems(schema, relations)
        if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
    }

    public fun list(parameters: QueryParameters): ListPlan {
        val compilation = Compilation(parameters)
        val limit = compilation.limit()
        val values = compilation.filters()
        val sort = compilation.sort()
        val projection = compilation.projection()
        val includes = compilation.includes()
        val counted = compilation.counted()
        val cursor = parameters.scalars[CURSOR]
        val offset = parameters.scalars[OFFSET]?.let { compilation.offset(it, limit, cursorGiven = cursor != null) }
        compilation.finish()

        val asked = QueryShape(values.keys, checkNotNull(sort))
        val shape = rules.shapeOf(asked.filters, asked.sort) ?: throw QueryFaults.notOffered("no query shape of this resource is $asked")
        val order = effectiveOrder(shape.sort)
        val window =
            if (offset != null) {
                Window.Offset(checkNotNull(limit), offset)
            } else {
                Window.Cursor(checkNotNull(limit), cursor?.let { position(it, order) })
            }
        return ListPlan(shape, shape.filter(schema, values), order, checkNotNull(projection), checkNotNull(includes), window, counted)
    }

    public fun count(parameters: QueryParameters): CountPlan {
        val compilation = Compilation(parameters)
        compilation.meaningless(listOf(LIMIT, OFFSET, CURSOR, SORT, FIELDS, INCLUDE), "has no meaning when counting")
        if (rules.pagination.countCap == null) {
            compilation.refuse(emptyList(), RainCrudErrorCodes.NOT_OFFERED, "this resource offers no count")
        }
        parameters.scalars[COUNT]?.takeIf { it != COUNT_CAPPED }?.let {
            compilation.refuse(path(COUNT), RainErrorCodes.INVALID_FORMAT, "the only count of query dialect v1 is capped")
        }
        val values = compilation.filters()
        compilation.finish()
        val asked = QueryShape(values.keys, SortKey.NONE)
        if (!rules.countsBy(asked.filters)) {
            throw QueryFaults.notOffered("no query shape of this resource has the filters ${asked.filters.joinToString(", ", "[", "]")}")
        }
        return CountPlan(asked.filter(schema, values))
    }

    public fun item(parameters: QueryParameters): ItemPlan {
        val compilation = Compilation(parameters)
        compilation.meaningless(listOf(LIMIT, OFFSET, CURSOR, SORT, COUNT), "has no meaning for one item")
        parameters.filters.forEach {
            compilation.refuse(path(FILTER, it.field, it.operator), RainErrorCodes.BAD_QUERY, "has no meaning for one item")
        }
        val projection = compilation.projection()
        val includes = compilation.includes()
        compilation.finish()
        return ItemPlan(checkNotNull(projection), checkNotNull(includes))
    }

    /** The [sort], then the identifier in the last term's direction — ascending when the sort names nothing. */
    public fun effectiveOrder(sort: SortKey): List<Order> {
        val terms =
            sort.terms.map {
                Order(
                    checkNotNull(schema.field(it.field)) { "${schema.name} has no field ${it.field}" },
                    it.direction,
                )
            }
        if (terms.any { it.field == schema.id }) return terms
        val tieBreak = if (terms.isEmpty()) Direction.ASC else terms.last().direction
        return terms + Order(schema.id, tieBreak)
    }

    private fun position(
        token: String,
        order: List<Order>,
    ): CursorPosition =
        when (val decoded = CursorCodec.decode(token, order)) {
            is CursorDecoding.Decoded -> decoded.position
            is CursorDecoding.Refused -> throw QueryFaults.invalidCursor(decoded.reason)
        }

    private inner class Compilation(
        private val parameters: QueryParameters,
    ) {
        private val violations = mutableListOf<Violation>()
        private val limits = rules.limits

        fun refuse(
            at: List<PathStep>,
            code: ErrorCode,
            message: String,
        ) {
            violations += Violation.at(at, code, message)
        }

        fun finish() {
            if (violations.isNotEmpty()) throw QueryFaults.badQuery(violations)
        }

        fun meaningless(
            names: List<String>,
            message: String,
        ) {
            names.filter(parameters.scalars::containsKey).forEach { refuse(path(it), RainErrorCodes.BAD_QUERY, message) }
        }

        fun limit(): Int? {
            val text = parameters.scalars[LIMIT] ?: return rules.pagination.defaultLimit
            if (!NON_NEGATIVE.matches(text)) return refused(path(LIMIT), RainErrorCodes.INVALID_FORMAT, "is not a non-negative integer")
            val value = text.toIntOrNull()
            return when {
                value == null || value > rules.pagination.maxLimit -> {
                    refused(path(LIMIT), RainErrorCodes.OUT_OF_RANGE, "is above this resource's maximum of ${rules.pagination.maxLimit}")
                }

                value < 1 -> {
                    refused(path(LIMIT), RainErrorCodes.OUT_OF_RANGE, "is at least 1")
                }

                else -> {
                    value
                }
            }
        }

        fun offset(
            text: String,
            limit: Int?,
            cursorGiven: Boolean,
        ): Long? {
            if (cursorGiven) return refused(path(OFFSET), RainErrorCodes.BAD_QUERY, "cannot be combined with cursor")
            if (!NON_NEGATIVE.matches(text)) return refused(path(OFFSET), RainErrorCodes.INVALID_FORMAT, "is not a non-negative integer")
            val maxOffset = rules.pagination.maxOffset
            val value = text.toLongOrNull()
            // Both sides are Long: maxOffset - limit cannot overflow, and an offset past Long's range never parses.
            val deepest = if (limit == null) maxOffset else maxOffset - limit
            if (value == null || value > deepest) {
                return refused(path(OFFSET), RainErrorCodes.OUT_OF_RANGE, "offset + limit is above this resource's maximum of $maxOffset")
            }
            return value
        }

        /** Each well-formed filter and the values it was given, read as its field's kind. */
        fun filters(): Map<ShapeFilter, List<Any>> {
            if (parameters.filters.size > limits.maxFilterTerms) {
                refuse(path(FILTER), RainErrorCodes.OUT_OF_RANGE, "at most ${limits.maxFilterTerms} filter parameters are allowed")
                return emptyMap()
            }
            val read = LinkedHashMap<ShapeFilter, List<Any>>()
            parameters.filters.forEach { parameter -> term(parameter)?.let { (filter, values) -> read[filter] = values } }
            return read
        }

        private fun term(parameter: FilterParameter): Pair<ShapeFilter, List<Any>>? {
            val at = path(FILTER, parameter.field, parameter.operator)
            val field = schema.field(parameter.field) ?: return refused(at, RainErrorCodes.UNKNOWN_FIELD, "names no field of this resource")
            val operator =
                Operator.ofWire(parameter.operator)
                    ?: return refused(at, RainCrudErrorCodes.UNKNOWN_OPERATOR, "is not an operator of query dialect v1")
            operator.refusalFor(field)?.let { return refused(at, RainErrorCodes.BAD_QUERY, it) }
            val raws = parameter.values
            if (operator.takesList && raws.size > limits.maxInValues) {
                return refused(at, RainErrorCodes.OUT_OF_RANGE, "takes at most ${limits.maxInValues} values")
            }
            if (!operator.takesList && raws.size != 1) return refused(at, RainErrorCodes.BAD_QUERY, "takes exactly one value")
            val kind = if (operator == Operator.IS_NULL) FieldKind.BOOLEAN else field.kind
            val values =
                raws.mapIndexedNotNull { index, raw ->
                    when (val read = WireValues.read(raw, kind)) {
                        is WireValue.Read -> {
                            read.value
                        }

                        is WireValue.Refused -> {
                            refused(
                                if (operator.takesList) at + PathStep.Index(index) else at,
                                RainErrorCodes.INVALID_FORMAT,
                                "the value ${read.reason}",
                            )
                        }
                    }
                }
            return if (values.size == raws.size) ShapeFilter(field.name, operator) to values else null
        }

        fun sort(): SortKey? {
            val text = parameters.scalars[SORT] ?: return SortKey.NONE
            val terms =
                when (val parsed = QueryGrammar.sort(text)) {
                    is QueryGrammar.Outcome.Malformed -> return refused(path(SORT), RainErrorCodes.BAD_QUERY, parsed.reason)
                    is QueryGrammar.Outcome.Read -> parsed.value
                }
            if (terms.size > limits.maxSortTerms) {
                return refused(path(SORT), RainErrorCodes.OUT_OF_RANGE, "has more than ${limits.maxSortTerms} terms")
            }
            val unknown = terms.filter { schema.field(it.field) == null }
            unknown.forEach { refuse(path(SORT), RainErrorCodes.UNKNOWN_FIELD, "names ${it.field}, which is not a field of this resource") }
            return if (unknown.isEmpty()) SortKey.of(terms) else null
        }

        fun projection(): Projection? {
            val text = parameters.scalars[FIELDS] ?: return Projection.Full
            val names =
                when (val parsed = QueryGrammar.names(text)) {
                    is QueryGrammar.Outcome.Malformed -> return refused(path(FIELDS), RainErrorCodes.BAD_QUERY, parsed.reason)
                    is QueryGrammar.Outcome.Read -> parsed.value
                }
            if (names.size > limits.maxFields) {
                return refused(path(FIELDS), RainErrorCodes.OUT_OF_RANGE, "names more than ${limits.maxFields} fields")
            }
            val fields =
                names.mapNotNull { name ->
                    val field = schema.field(name)
                    when {
                        field == null -> {
                            refused(path(FIELDS), RainErrorCodes.UNKNOWN_FIELD, "names $name, which is not a field of this resource")
                        }

                        field != schema.id && !rules.selectable.grants(name) -> {
                            refused(path(FIELDS), RainCrudErrorCodes.FIELD_NOT_GRANTED, "$name is not selectable")
                        }

                        else -> {
                            field
                        }
                    }
                }
            return if (fields.size == names.size) Projection.Only(linkedSetOf(schema.id) + fields) else null
        }

        fun includes(): List<String>? {
            val text = parameters.scalars[INCLUDE] ?: return emptyList()
            val names =
                when (val parsed = QueryGrammar.names(text)) {
                    is QueryGrammar.Outcome.Malformed -> return refused(path(INCLUDE), RainErrorCodes.BAD_QUERY, parsed.reason)
                    is QueryGrammar.Outcome.Read -> parsed.value
                }
            if (names.size > limits.maxIncludes) {
                return refused(path(INCLUDE), RainErrorCodes.OUT_OF_RANGE, "names more than ${limits.maxIncludes} relations")
            }
            val granted =
                names.filter { name ->
                    when {
                        name !in relations -> {
                            refused(path(INCLUDE), RainErrorCodes.UNKNOWN_FIELD, "names $name, which is not a relation of this resource")
                                ?: false
                        }

                        !rules.includable.grants(name) -> {
                            refused(path(INCLUDE), RainCrudErrorCodes.FIELD_NOT_GRANTED, "$name is not includable")
                                ?: false
                        }

                        else -> {
                            true
                        }
                    }
                }
            return if (granted.size == names.size) names else null
        }

        fun counted(): Boolean {
            val text = parameters.scalars[COUNT] ?: return false
            when {
                text != COUNT_CAPPED -> refuse(path(COUNT), RainErrorCodes.INVALID_FORMAT, "the only count of query dialect v1 is capped")
                rules.pagination.countCap == null -> refuse(path(COUNT), RainCrudErrorCodes.NOT_OFFERED, "this resource offers no count")
            }
            return true
        }

        /** Records the refusal and answers `null`, so a compile step reads `return refused(…)`. */
        private fun <V> refused(
            at: List<PathStep>,
            code: ErrorCode,
            message: String,
        ): V? {
            refuse(at, code, message)
            return null
        }
    }

    private companion object {
        val NON_NEGATIVE = Regex("^(0|[1-9][0-9]*)$")
    }
}
