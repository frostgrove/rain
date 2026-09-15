package com.gd.rain.test

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** An index of a relation a plan reads, as the catalog describes it. */
public class PlanIndex(
    public val schema: String,
    /** The relation the index is on, in [schema]. */
    public val table: String,
    public val name: String,
    /** The access method, e.g. `btree` or `gist`. */
    public val method: String,
    keyColumns: List<String?>,
    /** Whether the index is unique (`pg_index.indisunique`); a partial unique index is unique among the rows its predicate admits. */
    public val unique: Boolean,
    /** Whether the index has a predicate (`pg_index.indpred`): it holds only the rows the predicate admits. */
    public val partial: Boolean,
) {
    /** The key columns in index order; `null` for a key that is an expression rather than a column. */
    public val keyColumns: List<String?> = keyColumns.toList()

    override fun toString(): String =
        "$schema.$name (${if (unique) "unique " else ""}${if (partial) "partial " else ""}$method on " +
            "${keyColumns.joinToString(", ") { it ?: "<expression>" }})"
}

/** Whether a plan bounds every row it reads from a relation ([QueryPlan.boundedScan]). */
public sealed interface PlanVerdict {
    public data object Bounded : PlanVerdict

    public data class Unbounded(
        public val reasons: List<String>,
    ) : PlanVerdict {
        init {
            require(reasons.isNotEmpty()) { "an unbounded verdict names at least one reason" }
        }
    }
}

/**
 * One `EXPLAIN (FORMAT JSON, VERBOSE)` plan and the descriptions of the indexes it uses.
 *
 * The JSON is parsed when the plan is constructed; anything but a one-plan EXPLAIN document is refused.
 */
public class QueryPlan(
    public val json: String,
    indexes: List<PlanIndex>,
) {
    private val indexes: Map<Pair<String, String>, PlanIndex> = indexes.associateBy { it.schema to it.name }
    private val root: PlanNode = PlanNode.parse(json)
    private val nodes: List<PlanNode> = root.descendants()

    /** Whether any node of the plan reads the index named [name]. */
    public fun usesIndex(name: String): Boolean = nodes.any { it.text(INDEX_NAME) == name }

    /** Whether any node of the plan is a sequential scan of [relation]. */
    public fun scansSequentially(relation: String): Boolean = nodes.any { it.type == "Seq Scan" && it.text(RELATION_NAME) == relation }

    /** Whether the plan has a `Limit` node anywhere; [boundedScan] is the criterion for boundedness. */
    public fun hasLimit(): Boolean = nodes.any { it.type == LIMIT }

    /**
     * Criterion **v3** ([CRITERION_VERSION]) for the relation [relation] in [schema]: whether every row this plan reads
     * from it is bounded, whatever the size of the relation — by the statement's own `LIMIT` (and `OFFSET`), or by a
     * unique key the read is pinned to.
     *
     * The plan is [PlanVerdict.Bounded] when it reads the relation at all and every node that reads it (every node
     * naming it as `Relation Name` in `Schema`, except the `ModifyTable` a write targets) is a unique-key lookup
     * (rule 0) or satisfies all of rules 1–5:
     *
     * 0. **Unique-key lookup.** An `Index Scan` or `Index Only Scan` of a b-tree index, every clause of whose
     *    `Index Cond` bounds the scanned range (rule 3), and whose `Index Cond` pins a unique key of the relation. A
     *    unique key is the key columns, none an expression, of a unique b-tree index described in the plan's
     *    [PlanIndex]es: the scanned index itself, or another index of the same relation that has no predicate (a
     *    partial unique index is unique only among the rows it holds). The `Index Cond` pins the key when
     *    - every key column has an `=` clause against one value (not `= ANY`, not `IS NULL`): the scan reads at most
     *      one entry each time it runs; or
     *    - the key has exactly one column, which has an `= ANY` clause against an inline array literal of *k*
     *      elements (`= ANY ('{…}'::uuid[])`, as PostgreSQL deparses an `IN` list of constants): the scan reads at
     *      most *k* entries each time it runs. `= ANY` over a parameter (`$1` of a generic plan) or any other
     *      operand states no *k* and pins nothing.
     *
     *    Its `Filter` is then evaluated on at most that many rows, and no `Limit` is needed. It is bounded when it runs
     *    once (rule 5) or, as the `Inner` input of a `Nested Loop` — with or without `Inner Unique`, with or without a
     *    `Join Filter` — when that join's `Outer` input has a bounded output and the join runs once. A unique-key
     *    lookup also counts as a bounded output wherever rule 4 asks for one.
     *
     * 1. **Index scan.** It is an `Index Scan` or an `Index Only Scan`.
     * 2. **No filter.** It carries no `Filter`: every condition is an `Index Cond`.
     * 3. **Bounding conditions.** Its index is a b-tree described in the plan's [PlanIndex]es, and every
     *    clause of its `Index Cond` bounds the scanned range: a clause on key column *k* is preceded, on every
     *    key column before *k*, by an equality clause (`=`, `= ANY (…)` or `IS NULL`); a row comparison covers
     *    consecutive key columns. A clause on a later column alone would be checked against every entry of
     *    the range instead of ending it, so the entries visited would grow with the relation. A clause
     *    criterion v3 cannot read (an expression key, any other form) makes the scan unbounded.
     * 4. **Limited or keyed.** Either
     *    - *limited*: walking up from the scan through its row input, the first node that is not `LockRows`,
     *      a `Subquery Scan` without `Filter` or a `Result` without `Filter` is a `Limit`. Every other node
     *      between them — `Sort`, `Incremental Sort`, `Hash`, `Materialize`, a bitmap or sequential scan, a
     *      join, `Append`, `Merge Append`, `Gather`, `Aggregate` — reorders or accumulates rows and makes the
     *      scan unbounded. (`LockRows` with `SKIP LOCKED` passes over rows other transactions hold.) Or
     *    - *keyed*: the scan is the `Inner` input of a `Nested Loop` (directly or through `Memoize`) that
     *      reports `Inner Unique`, carries no `Join Filter`, and whose `Outer` input has a bounded output — a
     *      `Limit`; a plain `Aggregate`; a `CTE Scan` of a bounded CTE; or a node that emits no more rows than
     *      its bounded input (`Subquery Scan`, `LockRows`, `Unique`, a grouped `Aggregate`, `Sort`,
     *      `Incremental Sort`, `Materialize`, `Memoize`, `Hash`, `Result`, `Gather`, `Gather Merge`,
     *      `WindowAgg`, `ModifyTable`, a semi or anti or inner-unique `Nested Loop`, an `Append` of bounded
     *      members).
     * 5. **Executed once.** From that `Limit` (or that `Nested Loop`) up to the root, every edge is an
     *    `Outer`, `InitPlan`, `Subquery` or `Member` edge: nothing re-executes it once per row of another
     *    input.
     *
     * Rows read are then at most the `LIMIT` plus `OFFSET` for a limited scan, at most one per row of the bounded
     * outer input for a keyed scan, and at most one (or *k*) per run for a unique-key lookup. Nodes above the
     * `Limit` are not constrained, so a capped count (`Aggregate` over `Limit`) and
     * `DELETE … WHERE id IN (SELECT … LIMIT n)` are bounded.
     *
     * A plan that reads no relation at all — PostgreSQL proved the conditions contradictory and planned a
     * constant-false `Result` — reads no row of the relation and is bounded. A plan that reads other relations
     * but not this one is unbounded for it, so a misspelled relation (or a view, whose plan reads the tables
     * under it) is never proven by default. The schema is the plan's `Schema`, which `VERBOSE` reports.
     *
     * [PlanVerdict.Unbounded] lists every reason, for every read of the relation that fails.
     *
     * v2 added rule 0 for a scan of the unique index itself with `=` on every key column; v3 extends rule 0 to a scan
     * of another index that pins a non-partial unique key, and to `= ANY` over an inline literal on a one-column unique
     * key. A scan v2 accepts by rule 0 bounds its range trivially, since every key column before any clause has an
     * equality, so every plan v2 accepts, v3 accepts.
     */
    public fun boundedScan(
        schema: String,
        relation: String,
    ): PlanVerdict {
        val reads = readsOf(relation).filter { it.text(SCHEMA) == schema }
        if (reads.isEmpty()) {
            val unqualified = readsOf(relation).filter { it.text(SCHEMA) == null }
            return when {
                unqualified.isNotEmpty() -> {
                    PlanVerdict.Unbounded(
                        listOf("the plan names no schema for the relation $relation it reads; explain it with VERBOSE"),
                    )
                }

                readsAnything() -> {
                    PlanVerdict.Unbounded(listOf("the plan reads no relation named $schema.$relation"))
                }

                else -> {
                    PlanVerdict.Bounded
                }
            }
        }
        return verdictOf(reads)
    }

    /**
     * [boundedScan] for a relation named without its schema. The bare name is accepted only when every read of a
     * relation with that name is in one schema; a name read in more than one schema is refused with an
     * [IllegalArgumentException], because the plan cannot say which relation is meant. A name holding a `.` is refused
     * too: a schema-qualified relation is named with `boundedScan(schema, relation)`.
     */
    public fun boundedScan(relation: String): PlanVerdict {
        require('.' !in relation) {
            "\"$relation\" is not a bare relation name; name a schema-qualified relation with boundedScan(schema, relation)"
        }
        val reads = readsOf(relation)
        val schemas = reads.map { it.text(SCHEMA) }.distinct()
        require(schemas.size <= 1) {
            "the plan reads relations named $relation in ${schemas.size} schemas (${schemas.joinToString(", ") { it ?: "<none>" }}); " +
                "name the one meant with boundedScan(schema, relation)"
        }
        if (reads.isEmpty()) {
            return if (readsAnything()) PlanVerdict.Unbounded(listOf("the plan reads no relation named $relation")) else PlanVerdict.Bounded
        }
        return verdictOf(reads)
    }

    /** The `(schema, index)` pairs the index scans of this plan use. */
    internal fun indexReferences(): Set<Pair<String, String>> =
        nodes
            .filter { it.type in INDEX_SCANS }
            .mapNotNullTo(LinkedHashSet()) { node -> node.text(INDEX_NAME)?.let { name -> node.text(SCHEMA)?.let { it to name } } }

    /** The `(schema, relation)` pairs the index scans of this plan read, whose unique keys rule 0 may use. */
    internal fun indexScannedRelations(): Set<Pair<String, String>> =
        nodes
            .filter { it.type in INDEX_SCANS }
            .mapNotNullTo(LinkedHashSet()) { node -> node.text(RELATION_NAME)?.let { name -> node.text(SCHEMA)?.let { it to name } } }

    override fun toString(): String = json

    private fun readsOf(relation: String): List<PlanNode> = nodes.filter { it.text(RELATION_NAME) == relation && it.type != MODIFY_TABLE }

    private fun readsAnything(): Boolean = nodes.any { it.text(RELATION_NAME) != null && it.type != MODIFY_TABLE }

    private fun verdictOf(reads: List<PlanNode>): PlanVerdict {
        val reasons = reads.flatMap(::reasonsAgainst)
        return if (reasons.isEmpty()) PlanVerdict.Bounded else PlanVerdict.Unbounded(reasons)
    }

    private fun reasonsAgainst(scan: PlanNode): List<String> {
        if (uniqueKeyLookup(scan)) {
            val join = scan.innerOfNestedLoop() ?: return executionReasons(scan)
            val outer = join.children.singleOrNull { it.relationship == OUTER }
            val driven = if (outer == null) listOf("${join.label()} above ${scan.label()} has no outer input") else outputReasons(outer)
            return driven + executionReasons(join)
        }
        val reasons = mutableListOf<String>()
        val indexScan = scan.type in INDEX_SCANS
        if (!indexScan) reasons += "${scan.label()} is not an index scan"
        scan.text(FILTER)?.let { reasons += "${scan.label()} carries a Filter: $it" }
        if (indexScan) reasons += boundingReasons(scan)
        val limit = limitAbove(scan)
        val join = scan.innerOfNestedLoop()
        reasons +=
            when {
                limit is Walk.Reached -> executionReasons(limit.node)
                indexScan && join != null -> keyedReasons(scan, join)
                else -> listOf((limit as Walk.Stopped).reason)
            }
        return reasons
    }

    /** Rule 0: a b-tree scan whose bounding `Index Cond` pins a unique key of the relation it reads. */
    private fun uniqueKeyLookup(scan: PlanNode): Boolean {
        if (scan.type !in INDEX_SCANS) return false
        val name = scan.text(INDEX_NAME) ?: return false
        val schema = scan.text(SCHEMA) ?: return false
        val relation = scan.text(RELATION_NAME) ?: return false
        val scanned = indexes[schema to name] ?: return false
        if (scanned.method != BTREE) return false
        val clauses = scan.text(INDEX_COND)?.let(IndexConditions::parse) ?: return false
        if (boundingReasons(scan).isNotEmpty()) return false
        val keys =
            indexes.values.filter { index ->
                index.unique &&
                    index.method == BTREE &&
                    null !in index.keyColumns &&
                    (index === scanned || (index.schema == schema && index.table == relation && !index.partial))
            }
        return keys.any { pins(clauses, it.keyColumns.filterNotNull()) }
    }

    /** Whether [clauses] equate every column of [key] with one value, or its one column with an inline literal of k values. */
    private fun pins(
        clauses: List<IndexConditions.Clause>,
        key: List<String>,
    ): Boolean {
        val single = clauses.filter { it.singleValue }.flatMap { it.columns }.toSet()
        if (key.all { it in single }) return true
        return key.size == 1 && clauses.any { it.overArray && it.values != null && it.columns == key }
    }

    private fun boundingReasons(scan: PlanNode): List<String> {
        val condition = scan.text(INDEX_COND) ?: return emptyList()
        val name = scan.text(INDEX_NAME) ?: return listOf("${scan.label()} names no index")
        val schema = scan.text(SCHEMA) ?: return listOf("${scan.label()} names no schema; the plan is explained with VERBOSE")
        val index = indexes[schema to name] ?: return listOf("the plan carries no description of index $schema.$name")
        if (index.method != BTREE) return listOf("${scan.label()} reads a ${index.method} index; criterion v3 bounds b-tree scans only")
        val clauses =
            IndexConditions.parse(condition)
                ?: return listOf("${scan.label()} has an Index Cond criterion v3 cannot read: $condition")
        val positions =
            clauses.map { clause ->
                clause to clause.columns.map { column -> index.keyColumns.indexOf(column) }
            }
        val unread = positions.filter { (_, at) -> -1 in at }.map { (clause, _) -> clause }
        if (unread.isNotEmpty()) {
            return unread.map { "Index Cond clause (${it.text}) of ${scan.label()} reads no key column of $index" }
        }
        val equalities = positions.filter { (clause, _) -> clause.kind == IndexConditions.Kind.EQUALITY }.flatMap { it.second }.toSet()
        return positions.mapNotNull { (clause, at) ->
            val first = at.first()
            val consecutive = at.indices.all { at[it] == first + it }
            val open = (0 until first).filterNot(equalities::contains).map { index.keyColumns[it] ?: "<expression>" }
            when {
                !consecutive -> {
                    "Index Cond clause (${clause.text}) of ${scan.label()} does not compare consecutive key columns of $index"
                }

                open.isNotEmpty() -> {
                    "Index Cond clause (${clause.text}) of ${scan.label()} does not bound the scanned range of $index: " +
                        "key column${if (open.size > 1) "s" else ""} ${open.joinToString(", ")} before it " +
                        "${if (open.size > 1) "have" else "has"} no equality condition"
                }

                else -> {
                    null
                }
            }
        }
    }

    private fun limitAbove(scan: PlanNode): Walk {
        var node = scan
        while (true) {
            val parent = node.parent ?: return Walk.Stopped("no Limit is above ${scan.label()}")
            if (node.relationship !in ROW_INPUT) {
                return Walk.Stopped("${scan.label()} is a ${node.relationship} input of ${parent.label()}, not under a Limit")
            }
            when {
                parent.type == LIMIT -> return Walk.Reached(parent)
                parent.type in PASS_THROUGH && parent.text(FILTER) == null -> node = parent
                else -> return Walk.Stopped("${parent.label()} is between ${scan.label()} and any Limit above it")
            }
        }
    }

    private fun keyedReasons(
        scan: PlanNode,
        join: PlanNode,
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (join.flag(INNER_UNIQUE) != true) reasons += "${scan.label()} is the inner input of a ${join.label()} that is not Inner Unique"
        join.text(JOIN_FILTER)?.let { reasons += "${join.label()} above ${scan.label()} carries a Join Filter: $it" }
        val outer = join.children.singleOrNull { it.relationship == OUTER }
        reasons +=
            if (outer == null) {
                listOf("${join.label()} above ${scan.label()} has no outer input")
            } else {
                outputReasons(outer)
            }
        reasons += executionReasons(join)
        return reasons
    }

    /** Why [node] may emit a number of rows that grows with a relation; empty when its output is bounded. */
    private fun outputReasons(node: PlanNode): List<String> =
        when {
            uniqueKeyLookup(node) -> {
                emptyList()
            }

            node.type == LIMIT -> {
                emptyList()
            }

            node.type == AGGREGATE && node.text(STRATEGY) == "Plain" -> {
                emptyList()
            }

            node.type == "CTE Scan" -> {
                val name = node.text("CTE Name")
                val definition = nodes.singleOrNull { it.text(SUBPLAN_NAME) == "CTE $name" }
                if (definition == null) listOf("the plan does not define the CTE ${node.label()} reads") else outputReasons(definition)
            }

            node.type == NESTED_LOOP && (node.flag(INNER_UNIQUE) == true || node.text(JOIN_TYPE) in setOf("Semi", "Anti")) -> {
                node.children.singleOrNull { it.relationship == OUTER }?.let(::outputReasons)
                    ?: listOf("${node.label()} has no outer input")
            }

            node.type in APPENDS -> {
                val members = node.children.filter { it.relationship == MEMBER }
                if (members.isEmpty()) listOf("${node.label()} has no members") else members.flatMap(::outputReasons)
            }

            node.type in NON_INCREASING -> {
                val input = node.children.filter { it.relationship in ROW_INPUT }
                when (input.size) {
                    0 -> if (node.type == "Result") emptyList() else listOf("${node.label()} has no row input")
                    1 -> outputReasons(input.single())
                    else -> listOf("${node.label()} has more than one row input")
                }
            }

            else -> {
                listOf("the output of ${node.label()} is not bounded")
            }
        }

    private fun executionReasons(start: PlanNode): List<String> {
        var node = start
        while (true) {
            val parent = node.parent ?: return emptyList()
            if (node.relationship !in ONCE) {
                return listOf("${start.label()} runs once per row of ${parent.label()} (a ${node.relationship} input)")
            }
            node = parent
        }
    }

    private sealed interface Walk {
        class Reached(
            val node: PlanNode,
        ) : Walk

        class Stopped(
            val reason: String,
        ) : Walk
    }

    public companion object {
        /** The version of the criterion [boundedScan] applies. */
        public const val CRITERION_VERSION: Int = 3

        private const val LIMIT = "Limit"
        private const val AGGREGATE = "Aggregate"
        private const val NESTED_LOOP = "Nested Loop"
        private const val MODIFY_TABLE = "ModifyTable"
        private const val RELATION_NAME = "Relation Name"
        private const val INDEX_NAME = "Index Name"
        private const val INDEX_COND = "Index Cond"
        private const val FILTER = "Filter"
        private const val JOIN_FILTER = "Join Filter"
        private const val JOIN_TYPE = "Join Type"
        private const val INNER_UNIQUE = "Inner Unique"
        private const val SCHEMA = "Schema"
        private const val STRATEGY = "Strategy"
        private const val SUBPLAN_NAME = "Subplan Name"
        private const val OUTER = "Outer"
        private const val MEMBER = "Member"
        private const val BTREE = "btree"

        private val INDEX_SCANS = setOf("Index Scan", "Index Only Scan")
        private val PASS_THROUGH = setOf("LockRows", "Subquery Scan", "Result")
        private val ROW_INPUT = setOf(OUTER, "Subquery")
        private val ONCE = setOf(OUTER, "InitPlan", "Subquery", MEMBER)
        private val APPENDS = setOf("Append", "Merge Append")
        private val NON_INCREASING =
            setOf(
                "Subquery Scan",
                "LockRows",
                "Unique",
                AGGREGATE,
                "Sort",
                "Incremental Sort",
                "Materialize",
                "Memoize",
                "Hash",
                "Result",
                "Gather",
                "Gather Merge",
                "WindowAgg",
                MODIFY_TABLE,
            )
    }
}

/** One node of a parsed plan and the edge (`Parent Relationship`) that connects it to its parent. */
internal class PlanNode private constructor(
    private val fields: JsonNode,
    val parent: PlanNode?,
) {
    val type: String =
        fields.get("Node Type")?.takeIf(JsonNode::isString)?.stringValue()
            ?: throw IllegalArgumentException("a plan node has no Node Type: $fields")

    val relationship: String? = text("Parent Relationship")

    val children: List<PlanNode> =
        fields.get("Plans")?.let { plans ->
            require(plans.isArray) { "Plans of a $type node is not an array" }
            plans.values().map { PlanNode(it, this) }
        } ?: emptyList()

    fun text(name: String): String? = fields.get(name)?.takeIf(JsonNode::isString)?.stringValue()

    fun flag(name: String): Boolean? = fields.get(name)?.takeIf(JsonNode::isBoolean)?.booleanValue()

    fun descendants(): List<PlanNode> = listOf(this) + children.flatMap(PlanNode::descendants)

    fun innerOfNestedLoop(): PlanNode? {
        val edge = if (parent?.type == "Memoize" && relationship == "Outer") parent else this
        val join = edge.parent ?: return null
        return join.takeIf { it.type == "Nested Loop" && edge.relationship == "Inner" }
    }

    fun label(): String =
        buildString {
            append(type)
            text("Index Name")?.let { append(" using ").append(it) }
            text("Relation Name")?.let { relation ->
                append(" on ").append(relation)
                text("Alias")?.takeIf { it != relation }?.let { append(" (").append(it).append(')') }
            }
        }

    companion object {
        private val JSON: JsonMapper = JsonMapper.builder().build()

        fun parse(json: String): PlanNode {
            val document =
                try {
                    JSON.readTree(json)
                } catch (failure: JacksonException) {
                    throw IllegalArgumentException("the plan is not JSON: ${failure.originalMessage}", failure)
                }
            require(document.isArray && document.size() == 1) { "an EXPLAIN (FORMAT JSON) document is an array of one plan" }
            val plan = document.get(0).get("Plan")
            require(plan != null && plan.isObject) { "the EXPLAIN document has no Plan object" }
            return PlanNode(plan, null)
        }
    }
}
