package com.gd.rain.test

import javax.sql.DataSource

/**
 * The plan PostgreSQL chooses for a statement, with sequential scans disabled so any scan the planner
 * still has to use is one no index can serve.
 *
 * Scale is asserted from the plan, never from timing or row counts: a statement whose plan reaches its
 * rows through a named index under a `Limit` costs the same whether the table holds ten rows or ten
 * million. With [generic], parameters are written as `$1`, `$2` and planned without values; without it
 * the statement carries its values inline (e.g. jOOQ's `renderInlined`).
 */
public object QueryPlans {
    private const val GENERIC = "EXPLAIN (FORMAT JSON, GENERIC_PLAN)"
    private const val INLINED = "EXPLAIN (FORMAT JSON)"

    public fun explain(
        dataSource: DataSource,
        sql: String,
        generic: Boolean = true,
    ): QueryPlan =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET enable_seqscan = off")
                statement.executeQuery("${if (generic) GENERIC else INLINED} $sql").use { rows ->
                    check(rows.next()) { "EXPLAIN returned no plan" }
                    QueryPlan(rows.getString(1))
                }
            }
        }
}

public class QueryPlan(
    public val json: String,
) {
    public fun usesIndex(name: String): Boolean = json.contains("\"Index Name\": \"$name\"")

    public fun scansSequentially(relation: String): Boolean =
        Regex("\"Node Type\": \"Seq Scan\",[^}]*?\"Relation Name\": \"${Regex.escape(relation)}\"").containsMatchIn(json)

    public fun hasLimit(): Boolean = json.contains("\"Node Type\": \"Limit\"")

    override fun toString(): String = json
}
