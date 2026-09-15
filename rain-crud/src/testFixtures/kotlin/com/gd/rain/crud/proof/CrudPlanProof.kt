package com.gd.rain.crud.proof

import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.PageReads
import com.gd.rain.crud.RowRead
import com.gd.rain.crud.RowScope
import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.Order
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.QueryCompiler
import com.gd.rain.crud.query.QueryShape
import com.gd.rain.crud.query.ShapeFilter
import com.gd.rain.crud.query.TableName
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import org.jooq.Query
import org.jooq.conf.ParamType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * The proof was not evaluated: the database is not in the state that makes its answer depend on the schema
 * and indexes alone. The message names every reason.
 */
public class PlanProofNotEvaluatedException(
    message: String,
) : IllegalStateException(message)

/** A row scope the application states for the proof — typically one per scope rule, for a representative caller — named for its findings. */
public class ProofScope(
    public val name: String,
    public val scope: RowScope,
) {
    init {
        require(name.isNotBlank()) { "a proof scope is named" }
    }
}

/** The statements a resource runs for one query shape. */
public enum class StatementKind {
    /** The first cursor page. */
    FIRST_PAGE,

    /** The cursor page after a row. */
    SEEK_FORWARD,

    /** The cursor page before a row, read nearest-first in the inverted order. */
    SEEK_BACKWARD,

    /** The deepest offset page the pagination allows. */
    OFFSET_PAGE,

    /** The capped count, when the resource declares a count cap. */
    CAPPED_COUNT,
}

/** One statement the proof explained: which shape, under which scope, which kind, with which values, as SQL. */
public class ProvenStatement(
    public val shape: QueryShape,
    public val scope: String,
    public val kind: StatementKind,
    public val values: String,
    public val sql: String,
) {
    override fun toString(): String = "shape $shape | scope $scope | $kind | $values"
}

/** A statement whose plan criterion v1 does not accept, with every reason, the SQL and the plan. */
public class PlanFinding(
    public val statement: ProvenStatement,
    reasons: List<String>,
    public val planJson: String,
) {
    public val reasons: List<String> = reasons.toList()

    override fun toString(): String =
        buildString {
            append(statement).append('\n')
            reasons.forEach { append("  - ").append(it).append('\n') }
            append("  sql: ").append(statement.sql).append('\n')
            append("  plan: ").append(planJson)
        }
}

/** Every statement the proof explained, and the findings among them. */
public class PlanProofResult(
    statements: List<ProvenStatement>,
    findings: List<PlanFinding>,
) {
    public val statements: List<ProvenStatement> = statements.toList()
    public val findings: List<PlanFinding> = findings.toList()

    /** Fails listing every finding, when there is one. */
    public fun assertBounded() {
        if (findings.isEmpty()) return
        throw AssertionError(
            buildString {
                append(findings.size).append(" of ").append(statements.size).append(" statements are not bounded by their plans:\n")
                findings.forEach { append('\n').append(it).append('\n') }
            },
        )
    }
}

/**
 * The representative constants the proof renders a statement with. Each kind has one value per ordinal, so
 * a list of `n` values is `n` distinct constants:
 *
 * - `TEXT`: `rain-proof-<ordinal>`
 * - `BOOLEAN`: `true` at even ordinals, `false` at odd ones
 * - `INT`, `LONG`: `1 + ordinal`
 * - `DECIMAL`: `1 + ordinal`, scale 0
 * - `UUID`: `00000000-0000-7000-8000-<ordinal + 1 as 12 hex digits>`
 * - `TIMESTAMP`: `2000-01-01T00:00:00Z` plus `ordinal` seconds
 * - `DATE`: `2000-01-01` plus `ordinal` days
 */
public object RepresentativeValues {
    private val INSTANT: Instant = Instant.parse("2000-01-01T00:00:00Z")
    private val DATE: LocalDate = LocalDate.of(2000, 1, 1)

    public fun of(
        kind: FieldKind,
        ordinal: Int,
    ): Any {
        require(ordinal >= 0) { "an ordinal is not negative, got $ordinal" }
        return when (kind) {
            FieldKind.TEXT -> "rain-proof-$ordinal"
            FieldKind.BOOLEAN -> ordinal % 2 == 0
            FieldKind.INT -> 1 + ordinal
            FieldKind.LONG -> 1L + ordinal
            FieldKind.DECIMAL -> BigDecimal.valueOf(1L + ordinal)
            FieldKind.UUID -> UUID.fromString("00000000-0000-7000-8000-%012x".format(ordinal + 1L))
            FieldKind.TIMESTAMP -> INSTANT.plusSeconds(ordinal.toLong())
            FieldKind.DATE -> DATE.plusDays(ordinal.toLong())
        }
    }
}

/**
 * The plan proof of a resource, version [VERSION]: every statement the resource can run for its declared
 * query shapes, explained against the application's database and judged by criterion v1 of rain-test's
 * `QueryPlan.boundedScan` over the resource's table.
 *
 * An application runs it in its own integration tests, against a database migrated with its real schema
 * and indexes, before any row is written. **Precondition:** the resource's table holds no row and carries no
 * planner statistics (never vacuumed or analysed, no column or extended statistics); otherwise the proof
 * throws [PlanProofNotEvaluatedException] naming why. With statistics, PostgreSQL chooses among the paths the
 * indexes offer by the data and the constants of each statement, so the same indexes would be judged
 * differently on different data; without them, the chosen plan shows whether the indexes offer a bounded
 * path, which is what the proof states. It does not state which plan the server will choose for a given
 * request on production data.
 *
 * Enumeration is deterministic: for every declared shape (declaration order) × stated scope
 * (stated order) × [StatementKind] (enum order) × value variant, the proof renders the statement the
 * resource runs ([PageReads], [JooqResourceStore.readQuery], [JooqResourceStore.countQuery]) with jOOQ's
 * inlined rendering (`Query.getSQL(ParamType.INLINED)`, which is `renderInlined`) and explains it through
 * `QueryPlans.explain`.
 *
 * The statements, for a shape with effective order `o`:
 * - [StatementKind.FIRST_PAGE], [StatementKind.SEEK_FORWARD], [StatementKind.SEEK_BACKWARD]: pages of
 *   `maxLimit` rows; a seek is keyed by the representative value of each order field;
 * - [StatementKind.OFFSET_PAGE] when `maxOffset ≥ 1`: the page of `min(maxLimit, maxOffset)` rows ending at
 *   `maxOffset`;
 * - [StatementKind.CAPPED_COUNT] when the resource declares a count cap.
 *
 * The value variants of a shape are every combination of each filter's variants, in filter order:
 * - `eq`, `gt`, `gte`, `lt`, `lte`: one value;
 * - `in`: one value (PostgreSQL plans a one-element list as an equality) and, when `maxInValues > 1`,
 *   `maxInValues` values;
 * - `isnull`: `true` (`IS NULL`) and `false` (`IS NOT NULL`), its whole domain.
 *
 * A value is [RepresentativeValues.of] the field's kind at ordinal 0 (then 1, 2… along an `in` list), except
 * that an `eq` filter and the first value of an `in` filter take the value the stated scope pins the same
 * field to — the scope's `eq` comparison, or `in` with one value, among its top-level conjuncts. Two
 * different constants equated to one column make PostgreSQL plan the statement as reading nothing, which
 * would prove a statement no request runs.
 */
public object CrudPlanProof {
    public const val VERSION: Int = 1

    public fun verify(
        store: JooqResourceStore<*>,
        resource: CrudResource<*>,
        scopes: List<ProofScope>,
        dataSource: DataSource,
    ): PlanProofResult {
        require(store.schema == resource.schema) { "the store is the resource's store: ${store.schema} is not ${resource.schema}" }
        require(scopes.isNotEmpty()) { "the proof runs under at least one stated scope" }
        val repeated = scopes.groupBy(ProofScope::name).filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "each proof scope is named once, but ${repeated.sorted()} are named more than once" }

        val schema = resource.schema
        requirePristine(dataSource, schema.table)
        val rules = resource.rules
        val pagination = rules.pagination
        val compiler = QueryCompiler(schema, rules, resource.relationNames)
        val statements = mutableListOf<ProvenStatement>()
        val findings = mutableListOf<PlanFinding>()

        fun explain(
            shape: QueryShape,
            scope: ProofScope,
            kind: StatementKind,
            values: String,
            query: Query,
        ) {
            val sql = query.getSQL(ParamType.INLINED)
            val statement = ProvenStatement(shape, scope.name, kind, values, sql)
            statements += statement
            val plan = QueryPlans.explain(dataSource, sql, generic = false)
            val verdict = plan.boundedScan(schema.table.name)
            if (verdict is PlanVerdict.Unbounded) findings += PlanFinding(statement, verdict.reasons, plan.json)
        }

        rules.shapes.forEach { shape ->
            val order = compiler.effectiveOrder(shape.sort)
            val keys = order.map { RepresentativeValues.of(it.field.kind, 0) }
            scopes.forEach { scope ->
                val variants = variants(shape, scope.scope, rules.limits.maxInValues, resource)
                StatementKind.entries.forEach { kind ->
                    variants.forEach { variant ->
                        val filter = shape.filter(schema, variant)
                        val described = describe(variant)
                        when (kind) {
                            StatementKind.FIRST_PAGE -> {
                                val read = PageReads.first(scope.scope, filter, order, pagination.maxLimit, Projection.Full)
                                explain(shape, scope, kind, described, store.readQuery(read))
                            }

                            StatementKind.SEEK_FORWARD -> {
                                val read = PageReads.after(scope.scope, filter, order, keys, pagination.maxLimit, Projection.Full)
                                explain(shape, scope, kind, "$described; after ${keysOf(order, keys)}", store.readQuery(read))
                            }

                            StatementKind.SEEK_BACKWARD -> {
                                val read = PageReads.before(scope.scope, filter, order, keys, pagination.maxLimit, Projection.Full)
                                explain(shape, scope, kind, "$described; before ${keysOf(order, keys)}", store.readQuery(read))
                            }

                            StatementKind.OFFSET_PAGE -> {
                                if (pagination.maxOffset >= 1) {
                                    val limit = minOf(pagination.maxLimit.toLong(), pagination.maxOffset).toInt()
                                    val offset = pagination.maxOffset - limit
                                    val read: RowRead = PageReads.offset(scope.scope, filter, order, offset, limit, Projection.Full)
                                    explain(shape, scope, kind, "$described; offset $offset, limit $limit", store.readQuery(read))
                                }
                            }

                            StatementKind.CAPPED_COUNT -> {
                                pagination.countCap?.let { cap ->
                                    explain(shape, scope, kind, "$described; cap $cap", store.countQuery(scope.scope, filter, cap))
                                }
                            }
                        }
                    }
                }
            }
        }
        return PlanProofResult(statements, findings)
    }

    private const val RELATION_STATE = """
        SELECT c.reltuples,
               EXISTS (SELECT 1 FROM pg_catalog.pg_stats s WHERE s.schemaname = n.nspname AND s.tablename = c.relname),
               EXISTS (SELECT 1 FROM pg_catalog.pg_stats_ext x WHERE x.schemaname = n.nspname AND x.tablename = c.relname)
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = ? AND c.relname = ?
    """

    private fun requirePristine(
        dataSource: DataSource,
        table: TableName,
    ) {
        val reasons = mutableListOf<String>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(RELATION_STATE).use { statement ->
                statement.setString(1, table.schema)
                statement.setString(2, table.name)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) throw PlanProofNotEvaluatedException("the plan proof was not evaluated: table $table does not exist")
                    val tuples = rows.getDouble(1)
                    if (tuples >= 0) reasons += "has been vacuumed or analysed (reltuples = $tuples)"
                    if (rows.getBoolean(2)) reasons += "has column statistics"
                    if (rows.getBoolean(3)) reasons += "has extended statistics"
                }
            }
            // TableName admits only [A-Za-z_][A-Za-z0-9_]*, so the quoted identifiers cannot be broken out of.
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1 FROM \"${table.schema}\".\"${table.name}\" LIMIT 1").use { rows ->
                    if (rows.next()) reasons += "holds rows"
                }
            }
        }
        if (reasons.isNotEmpty()) {
            throw PlanProofNotEvaluatedException(
                "the plan proof was not evaluated: table $table ${reasons.joinToString("; ")}; " +
                    "the proof explains against an empty, never-analysed table so that the plans depend on the indexes alone",
            )
        }
    }

    private fun variants(
        shape: QueryShape,
        scope: RowScope,
        maxInValues: Int,
        resource: CrudResource<*>,
    ): List<Map<ShapeFilter, List<Any>>> =
        shape.filters.fold(listOf(emptyMap())) { partial, filter ->
            val field =
                checkNotNull(resource.schema.field(filter.field)) { "resource ${resource.schema.name} has no field ${filter.field}" }
            val pinned = pinnedValue(scope.predicate, field.name)
            val first = pinned ?: RepresentativeValues.of(field.kind, 0)
            val choices: List<List<Any>> =
                when (filter.operator) {
                    Operator.EQ -> {
                        listOf(listOf(first))
                    }

                    Operator.GT, Operator.GTE, Operator.LT, Operator.LTE -> {
                        listOf(listOf(RepresentativeValues.of(field.kind, 0)))
                    }

                    Operator.IN -> {
                        if (maxInValues > 1) {
                            listOf(listOf(first), listOf(first) + (1 until maxInValues).map { RepresentativeValues.of(field.kind, it) })
                        } else {
                            listOf(listOf(first))
                        }
                    }

                    Operator.IS_NULL -> {
                        listOf(listOf(true), listOf(false))
                    }
                }
            partial.flatMap { chosen -> choices.map { chosen + (filter to it) } }
        }

    /** The value [predicate]'s top-level conjuncts pin the field named [field] to by `eq`, or by `in` with one value. */
    private fun pinnedValue(
        predicate: Predicate?,
        field: String,
    ): Any? =
        when (predicate) {
            is Predicate.AllOf -> {
                predicate.of.firstNotNullOfOrNull { pinnedValue(it, field) }
            }

            is Predicate.Compare -> {
                val pins = predicate.operator == Operator.EQ || (predicate.operator == Operator.IN && predicate.values.size == 1)
                if (pins && predicate.field.name == field) predicate.values.single() else null
            }

            else -> {
                null
            }
        }

    private fun describe(variant: Map<ShapeFilter, List<Any>>): String =
        if (variant.isEmpty()) {
            "no filter"
        } else {
            variant.entries.joinToString(", ") { (filter, values) ->
                if (values.size == 1) "$filter=${values.single()}" else "$filter=${values.size} values"
            }
        }

    private fun keysOf(
        order: List<Order>,
        keys: List<Any>,
    ): String = order.indices.joinToString(", ", "(", ")") { "${order[it].field.name}=${keys[it]}" }
}
