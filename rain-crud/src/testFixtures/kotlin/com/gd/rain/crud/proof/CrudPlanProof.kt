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
import com.gd.rain.crud.web.CrudOperation
import com.gd.rain.crud.web.MountedResource
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlan
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

/** The statements a resource runs for its mounted operations. */
public enum class StatementKind {
    /** The first cursor page of a shape (`LIST`). */
    FIRST_PAGE,

    /** The cursor page after a row (`LIST`). */
    SEEK_FORWARD,

    /** The cursor page before a row, read nearest-first in the inverted order (`LIST`). */
    SEEK_BACKWARD,

    /** The deepest offset page the pagination allows (`LIST`). */
    OFFSET_PAGE,

    /** The capped count of a shape's filters, when the resource declares a count cap (`COUNT`, or `LIST` with `count=capped`). */
    CAPPED_COUNT,

    /** One item by identifier within the scope (`GET`). */
    ITEM,

    /** The insert of a create (`CREATE`). */
    INSERT,

    /** The update by identifier within the scope of an update or a replacement (`UPDATE`, `REPLACE`). */
    UPDATE,

    /**
     * The check by identifier and scope a write runs in its transaction: that an inserted or updated row is in the scope,
     * or, on a versioned resource, that a row an update did not write is there at another version (`CREATE` under a
     * scope; `UPDATE` and `REPLACE` under a scope or on a versioned resource).
     */
    ROW_IN_SCOPE,

    /** The delete by identifier within the scope (`DELETE`). */
    DELETE,

    /** The delete of a list of identifiers within the scope (`BULK_DELETE`). */
    BULK_DELETE,
}

/**
 * One statement the proof explained: which shape (`null` for a statement addressed by identifier), under which scope,
 * which kind, with which values, as SQL.
 */
public class ProvenStatement(
    public val shape: QueryShape?,
    public val scope: String,
    public val kind: StatementKind,
    public val values: String,
    public val sql: String,
) {
    override fun toString(): String = "${shape?.let { "shape $it" } ?: "by identifier"} | scope $scope | $kind | $values"
}

/** A statement whose plan criterion v3 does not accept, with every reason, the SQL and the plan. */
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
 * The plan proof of a mounted resource, version [VERSION]: every statement the resource's store runs for the mounted
 * operations, explained against the application's database under every stated scope and judged by criterion v3 of
 * rain-test's `QueryPlan.boundedScan` over the resource's schema-qualified table.
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
 * Enumeration is deterministic. The statements rendered are the store's own ([PageReads],
 * [JooqResourceStore.readQuery], [JooqResourceStore.countQuery], [JooqResourceStore.findQuery],
 * [JooqResourceStore.insertQuery], [JooqResourceStore.updateQuery], [JooqResourceStore.inScopeQuery],
 * [JooqResourceStore.deleteQuery], [JooqResourceStore.deleteManyQuery]), inlined with jOOQ's
 * `Query.getSQL(ParamType.INLINED)` (which is `renderInlined`) and explained through `QueryPlans.explain`.
 *
 * First, for every declared shape (declaration order) × stated scope (stated order) × [StatementKind] (enum order) ×
 * value variant, the statements of a shape, for a shape with effective order `o`:
 * - [StatementKind.FIRST_PAGE], [StatementKind.SEEK_FORWARD], [StatementKind.SEEK_BACKWARD] when `LIST` is mounted:
 *   pages of `maxLimit` rows; a seek is keyed by the representative value of each order field;
 * - [StatementKind.OFFSET_PAGE] when `LIST` is mounted and `maxOffset ≥ 1`: the page of `min(maxLimit, maxOffset)` rows
 *   ending at `maxOffset`;
 * - [StatementKind.CAPPED_COUNT] when `LIST` or `COUNT` is mounted and the resource declares a count cap.
 *
 * The value variants of a shape are every combination of each filter's variants, in filter order:
 * - `eq`, `gt`, `gte`, `lt`, `lte`: one value;
 * - `in`: one value (PostgreSQL plans a one-element list as an equality) and, when `maxInValues > 1`,
 *   `maxInValues` values;
 * - `isnull`: `true` (`IS NULL`) and `false` (`IS NOT NULL`), its whole domain.
 *
 * Then, for every stated scope × [StatementKind] × variant, the statements addressed by identifier, each keyed by the
 * representative `UUID` at ordinal 0 (and a bulk list by ordinals 0, 1, …):
 * - [StatementKind.ITEM] when `GET` is mounted, of every field;
 * - [StatementKind.INSERT] when `CREATE` is mounted, of the identifier and every other field `writable` grants;
 * - [StatementKind.UPDATE] when `UPDATE` or `REPLACE` is mounted, of every field of `CrudResource.replaceable`, stating
 *   version 1 on a versioned resource;
 * - [StatementKind.ROW_IN_SCOPE] when the store runs it: `CREATE` mounted and the scope is not every row, or `UPDATE`
 *   or `REPLACE` mounted and the scope is not every row or the resource is versioned;
 * - [StatementKind.DELETE] when `DELETE` is mounted;
 * - [StatementKind.BULK_DELETE] when `BULK_DELETE` is mounted: one identifier and, when `maxBulkIds > 1`, `maxBulkIds`.
 *
 * A value is [RepresentativeValues.of] the field's kind at ordinal 0 (then 1, 2… along an `in` list), except that an
 * `eq` filter, the first value of an `in` filter and a written field take the value the stated scope pins the same field
 * to — the scope's `eq` comparison, or `in` with one value, among its top-level conjuncts. Two different constants
 * equated to one column make PostgreSQL plan the statement as reading nothing, which would prove a statement no request
 * runs.
 */
public object CrudPlanProof {
    public const val VERSION: Int = 2

    public fun verify(
        store: JooqResourceStore<*>,
        mounted: MountedResource<*>,
        scopes: List<ProofScope>,
        dataSource: DataSource,
    ): PlanProofResult {
        val resource = mounted.resource
        require(store.schema == resource.schema) { "the store is the resource's store: ${store.schema} is not ${resource.schema}" }
        require(scopes.isNotEmpty()) { "the proof runs under at least one stated scope" }
        val repeated = scopes.groupBy(ProofScope::name).filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "each proof scope is named once, but ${repeated.sorted()} are named more than once" }

        val schema = resource.schema
        requirePristine(dataSource, schema.table)
        val operations = mounted.operations.toSet()
        val proof = Explained(dataSource, schema.table)

        val listed = CrudOperation.LIST in operations
        val counted = resource.rules.pagination.countCap != null && (listed || CrudOperation.COUNT in operations)
        if (listed || counted) shapeStatements(store, resource, scopes, listed, counted, proof)
        scopes.forEach { scope -> identifierStatements(store, resource, operations, scope, proof) }
        return PlanProofResult(proof.statements, proof.findings)
    }

    private class Explained(
        val dataSource: DataSource,
        val table: TableName,
    ) {
        val statements = mutableListOf<ProvenStatement>()
        val findings = mutableListOf<PlanFinding>()

        fun explain(
            shape: QueryShape?,
            scope: ProofScope,
            kind: StatementKind,
            values: String,
            query: Query,
        ) {
            val sql = query.getSQL(ParamType.INLINED)
            val statement = ProvenStatement(shape, scope.name, kind, values, sql)
            statements += statement
            val plan: QueryPlan = QueryPlans.explain(dataSource, sql, generic = false)
            val verdict = plan.boundedScan(table.schema, table.name)
            if (verdict is PlanVerdict.Unbounded) findings += PlanFinding(statement, verdict.reasons, plan.json)
        }
    }

    private fun shapeStatements(
        store: JooqResourceStore<*>,
        resource: CrudResource<*>,
        scopes: List<ProofScope>,
        listed: Boolean,
        counted: Boolean,
        proof: Explained,
    ) {
        val schema = resource.schema
        val rules = resource.rules
        val pagination = rules.pagination
        val compiler = QueryCompiler(schema, rules, resource.relationNames, store.itemFields)
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
                                if (listed) {
                                    val read = PageReads.first(scope.scope, filter, order, pagination.maxLimit, Projection.Full)
                                    proof.explain(shape, scope, kind, described, store.readQuery(read))
                                }
                            }

                            StatementKind.SEEK_FORWARD -> {
                                if (listed) {
                                    val read = PageReads.after(scope.scope, filter, order, keys, pagination.maxLimit, Projection.Full)
                                    proof.explain(shape, scope, kind, "$described; after ${keysOf(order, keys)}", store.readQuery(read))
                                }
                            }

                            StatementKind.SEEK_BACKWARD -> {
                                if (listed) {
                                    val read = PageReads.before(scope.scope, filter, order, keys, pagination.maxLimit, Projection.Full)
                                    proof.explain(shape, scope, kind, "$described; before ${keysOf(order, keys)}", store.readQuery(read))
                                }
                            }

                            StatementKind.OFFSET_PAGE -> {
                                if (listed && pagination.maxOffset >= 1) {
                                    val limit = minOf(pagination.maxLimit.toLong(), pagination.maxOffset).toInt()
                                    val offset = pagination.maxOffset - limit
                                    val read: RowRead = PageReads.offset(scope.scope, filter, order, offset, limit, Projection.Full)
                                    proof.explain(shape, scope, kind, "$described; offset $offset, limit $limit", store.readQuery(read))
                                }
                            }

                            StatementKind.CAPPED_COUNT -> {
                                val cap = pagination.countCap
                                if (counted && cap != null) {
                                    proof.explain(shape, scope, kind, "$described; cap $cap", store.countQuery(scope.scope, filter, cap))
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun identifierStatements(
        store: JooqResourceStore<*>,
        resource: CrudResource<*>,
        operations: Set<CrudOperation>,
        scope: ProofScope,
        proof: Explained,
    ) {
        val schema = resource.schema
        val id = RepresentativeValues.of(FieldKind.UUID, 0) as UUID
        val scoped = scope.scope.predicate != null
        val byIdentifier = CrudOperation.UPDATE in operations || CrudOperation.REPLACE in operations
        StatementKind.entries.forEach { kind ->
            when (kind) {
                StatementKind.ITEM -> {
                    if (CrudOperation.GET in operations) {
                        proof.explain(null, scope, kind, "id=$id", store.findQuery(id, scope.scope, Projection.Full))
                    }
                }

                StatementKind.INSERT -> {
                    if (CrudOperation.CREATE in operations) {
                        val written =
                            schema.fields.filter {
                                it != schema.id && it != schema.version &&
                                    resource.policy.writable.grants(it.name)
                            }
                        val values = written.associate { it.name to written(scope.scope, it.name, it.kind) } + (schema.id.name to id)
                        proof.explain(null, scope, kind, "id=$id, ${written.size} fields", store.insertQuery(values))
                    }
                }

                StatementKind.UPDATE -> {
                    if (byIdentifier) {
                        val values = resource.replaceable.associate { it.name to written(scope.scope, it.name, it.kind) }
                        val version = schema.version?.let { schema.initialVersion }
                        val described = "id=$id, ${values.size} fields${version?.let { ", version $it" } ?: ""}"
                        proof.explain(null, scope, kind, described, store.updateQuery(id, scope.scope, values, version))
                    }
                }

                StatementKind.ROW_IN_SCOPE -> {
                    val afterInsert = CrudOperation.CREATE in operations && scoped
                    val afterUpdate = byIdentifier && (scoped || schema.version != null)
                    if (afterInsert || afterUpdate) proof.explain(null, scope, kind, "id=$id", store.inScopeQuery(id, scope.scope))
                }

                StatementKind.DELETE -> {
                    if (CrudOperation.DELETE in operations) proof.explain(null, scope, kind, "id=$id", store.deleteQuery(id, scope.scope))
                }

                StatementKind.BULK_DELETE -> {
                    if (CrudOperation.BULK_DELETE in operations) {
                        val maxBulkIds = resource.rules.limits.maxBulkIds
                        listOf(1, maxBulkIds).distinct().forEach { size ->
                            val ids = (0 until size).mapTo(LinkedHashSet()) { RepresentativeValues.of(FieldKind.UUID, it) as UUID }
                            proof.explain(null, scope, kind, "$size ids", store.deleteManyQuery(ids, scope.scope))
                        }
                    }
                }

                else -> {}
            }
        }
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

    /** The value a write states for the field named [name]: the one the scope pins it to, or the representative one. */
    private fun written(
        scope: RowScope,
        name: String,
        kind: FieldKind,
    ): Any = pinnedValue(scope.predicate, name) ?: RepresentativeValues.of(kind, 0)

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
