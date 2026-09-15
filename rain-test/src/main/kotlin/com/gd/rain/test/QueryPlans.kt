package com.gd.rain.test

import java.sql.Connection
import javax.sql.DataSource

/**
 * The plan PostgreSQL chooses for a statement, read so that boundedness can be judged from the plan alone.
 *
 * Scale is asserted from the plan, never from timing or row counts: a statement whose every read of a
 * relation is bounded by its own `LIMIT` ([QueryPlan.boundedScan]) costs the same whether the table holds
 * ten rows or ten million.
 *
 * The statement is explained with `VERBOSE` (which reports `Inner Unique` on joins) inside a transaction
 * that is rolled back, with [SETTINGS] applied by `SET LOCAL`, so a pooled connection is handed back as it
 * was lent. The settings disable the scan kinds criterion v1 never accepts — sequential, bitmap and TID
 * scans — and parallel plans. PostgreSQL 18 prefers any plan with fewer disabled nodes over a cheaper plan
 * with more, so the plan shows an index access path whenever the indexes offer one, however few rows the
 * test database holds. Sorts, hashed aggregates and joins stay enabled: above a `LIMIT` they are part of
 * bounded statements (a capped count, a delete of the ids a limited subquery picked).
 *
 * With [generic] the statement carries `$1`, `$2` placeholders and is planned without values
 * (`GENERIC_PLAN`); without it the statement carries its values inline (e.g. jOOQ's `renderInlined`).
 *
 * Every index an index scan of the plan uses is described from the catalog ([PlanIndex]), because the
 * criterion needs each index's access method and key columns.
 */
public object QueryPlans {
    /** The planner settings every explained statement runs under, applied with `SET LOCAL`. */
    public val SETTINGS: List<String> =
        listOf(
            "enable_seqscan = off",
            "enable_bitmapscan = off",
            "enable_tidscan = off",
            "max_parallel_workers_per_gather = 0",
        )

    private const val DESCRIBE_INDEX = """
        SELECT am.amname, a.attname
        FROM pg_catalog.pg_class ic
        JOIN pg_catalog.pg_namespace n ON n.oid = ic.relnamespace
        JOIN pg_catalog.pg_index ix ON ix.indexrelid = ic.oid
        JOIN pg_catalog.pg_am am ON am.oid = ic.relam
        CROSS JOIN LATERAL unnest(ix.indkey::int2[]) WITH ORDINALITY AS k(attnum, position)
        LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = k.attnum AND k.attnum <> 0
        WHERE n.nspname = ? AND ic.relname = ? AND k.position <= ix.indnkeyatts
        ORDER BY k.position
    """

    public fun explain(
        dataSource: DataSource,
        sql: String,
        generic: Boolean = true,
    ): QueryPlan =
        dataSource.connection.use { connection ->
            val autoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val json =
                    connection.createStatement().use { statement ->
                        SETTINGS.forEach { statement.execute("SET LOCAL $it") }
                        val options = if (generic) "FORMAT JSON, VERBOSE, GENERIC_PLAN" else "FORMAT JSON, VERBOSE"
                        statement.executeQuery("EXPLAIN ($options) $sql").use { rows ->
                            check(rows.next()) { "EXPLAIN returned no plan" }
                            rows.getString(1)
                        }
                    }
                val references = QueryPlan(json, emptyList()).indexReferences()
                QueryPlan(json, references.mapNotNull { (schema, name) -> describe(connection, schema, name) })
            } finally {
                connection.rollback()
                connection.autoCommit = autoCommit
            }
        }

    private fun describe(
        connection: Connection,
        schema: String,
        name: String,
    ): PlanIndex? =
        connection.prepareStatement(DESCRIBE_INDEX).use { statement ->
            statement.setString(1, schema)
            statement.setString(2, name)
            statement.executeQuery().use { rows ->
                var method: String? = null
                val columns = mutableListOf<String?>()
                while (rows.next()) {
                    method = rows.getString(1)
                    columns += rows.getString(2)
                }
                method?.let { PlanIndex(schema, name, it, columns) }
            }
        }
}
