package com.gd.rain.test

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** An index an index scan of a plan uses, as the catalog describes it. */
public class PlanIndex(
    public val schema: String,
    public val name: String,
    /** The access method, e.g. `btree` or `gist`. */
    public val method: String,
    keyColumns: List<String?>,
) {
    /** The key columns in index order; `null` for a key that is an expression rather than a column. */
    public val keyColumns: List<String?> = keyColumns.toList()

    override fun toString(): String = "$schema.$name ($method on ${keyColumns.joinToString(", ") { it ?: "<expression>" }})"
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
     * Criterion **v1**: whether every row this plan reads from [relation] is bounded by the statement's own
     * `LIMIT` (and `OFFSET`), whatever the size of the relation.
     *
     * The plan is [PlanVerdict.Bounded] when it reads [relation] at all and every node that reads it (every
     * node naming it as `Relation Name`, except the `ModifyTable` a write targets) satisfies all of:
     *
     * 1. **Index scan.** It is an `Index Scan` or an `Index Only Scan`.
     * 2. **No filter.** It carries no `Filter`: every condition is an `Index Cond`.
     * 3. **Bounding conditions.** Its index is a b-tree described in the plan's [PlanIndex]es, and every
     *    clause of its `Index Cond` bounds the scanned range: a clause on key column *k* is preceded, on every
     *    key column before *k*, by an equality clause (`=`, `= ANY (…)` or `IS NULL`); a row comparison covers
     *    consecutive key columns. A clause on a later column alone would be checked against every entry of
     *    the range instead of ending it, so the entries visited would grow with the relation. A clause
     *    criterion v1 cannot read (an expression key, any other form) makes the scan unbounded.
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
     * Rows read are then at most the `LIMIT` plus `OFFSET` for a limited scan, and at most one per row of the
     * bounded outer input for a keyed scan. Nodes above the `Limit` are not constrained, so a capped count
     * (`Aggregate` over `Limit`) and `DELETE … WHERE id IN (SELECT … LIMIT n)` are bounded.
     *
     * A plan that reads no relation at all — PostgreSQL proved the conditions contradictory and planned a
     * constant-false `Result` — reads no row of [relation] and is bounded. A plan that reads other relations
     * but not [relation] is unbounded for it, so a misspelled relation (or a view, whose plan reads the
     * tables under it) is never proven by default.
     *
     * [PlanVerdict.Unbounded] lists every reason, for every read of [relation] that fails.
     */
    public fun boundedScan(relation: String): PlanVerdict {
        val reads = nodes.filter { it.text(RELATION_NAME) == relation && it.type != MODIFY_TABLE }
        if (reads.isEmpty()) {
            val readsAnything = nodes.any { it.text(RELATION_NAME) != null && it.type != MODIFY_TABLE }
            return if (readsAnything) PlanVerdict.Unbounded(listOf("the plan reads no relation named $relation")) else PlanVerdict.Bounded
        }
        val reasons = reads.flatMap(::reasonsAgainst)
        return if (reasons.isEmpty()) PlanVerdict.Bounded else PlanVerdict.Unbounded(reasons)
    }

    /** The `(schema, index)` pairs the index scans of this plan use. */
    internal fun indexReferences(): Set<Pair<String, String>> =
        nodes
            .filter { it.type in INDEX_SCANS }
            .mapNotNullTo(LinkedHashSet()) { node -> node.text(INDEX_NAME)?.let { name -> node.text(SCHEMA)?.let { it to name } } }

    override fun toString(): String = json

    private fun reasonsAgainst(scan: PlanNode): List<String> {
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

    private fun boundingReasons(scan: PlanNode): List<String> {
        val condition = scan.text(INDEX_COND) ?: return emptyList()
        val name = scan.text(INDEX_NAME) ?: return listOf("${scan.label()} names no index")
        val schema = scan.text(SCHEMA) ?: return listOf("${scan.label()} names no schema; the plan is explained with VERBOSE")
        val index = indexes[schema to name] ?: return listOf("the plan carries no description of index $schema.$name")
        if (index.method != BTREE) return listOf("${scan.label()} reads a ${index.method} index; criterion v1 bounds b-tree scans only")
        val clauses =
            IndexConditions.parse(condition)
                ?: return listOf("${scan.label()} has an Index Cond criterion v1 cannot read: $condition")
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

    private companion object {
        const val LIMIT = "Limit"
        const val AGGREGATE = "Aggregate"
        const val NESTED_LOOP = "Nested Loop"
        const val MODIFY_TABLE = "ModifyTable"
        const val RELATION_NAME = "Relation Name"
        const val INDEX_NAME = "Index Name"
        const val INDEX_COND = "Index Cond"
        const val FILTER = "Filter"
        const val JOIN_FILTER = "Join Filter"
        const val JOIN_TYPE = "Join Type"
        const val INNER_UNIQUE = "Inner Unique"
        const val SCHEMA = "Schema"
        const val STRATEGY = "Strategy"
        const val SUBPLAN_NAME = "Subplan Name"
        const val OUTER = "Outer"
        const val MEMBER = "Member"
        const val BTREE = "btree"

        val INDEX_SCANS = setOf("Index Scan", "Index Only Scan")
        val PASS_THROUGH = setOf("LockRows", "Subquery Scan", "Result")
        val ROW_INPUT = setOf(OUTER, "Subquery")
        val ONCE = setOf(OUTER, "InitPlan", "Subquery", MEMBER)
        val APPENDS = setOf("Append", "Merge Append")
        val NON_INCREASING =
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
