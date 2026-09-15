package com.gd.rain.test

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * `QueryPlans.explain` used to `SET enable_seqscan = off` on the connection it borrowed and never reset it,
 * so a pooled connection kept planning without sequential scans for whoever borrowed it next. The settings
 * are now `SET LOCAL` inside a transaction that is rolled back, and the connection's auto-commit is restored.
 */
@Tag("integration")
class ExplainLeavesPooledConnectionUnchangedIT {
    private fun pool(): HikariDataSource {
        val database = RainPostgres.freshDatabase("rain_test_explain")
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = database.url
                username = database.username
                password = database.password
                maximumPoolSize = 1
            },
        )
    }

    private fun HikariDataSource.settings(): List<String> =
        connection.use { connection ->
            listOf("enable_seqscan", "enable_bitmapscan", "enable_tidscan", "max_parallel_workers_per_gather").map { name ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SHOW $name").use { rows ->
                        check(rows.next())
                        "$name=${rows.getString(1)}"
                    }
                }
            } + "autoCommit=${connection.autoCommit}"
        }

    @Test
    fun `the one pooled connection plans as it did before an explain`() {
        pool().use { pool ->
            val before = pool.settings()
            pool.connection.use { it.createStatement().use { statement -> statement.execute("CREATE TABLE t (id int PRIMARY KEY)") } }

            val plan = QueryPlans.explain(pool, "SELECT id FROM t WHERE id = 1", generic = false)

            assertThat(plan.boundedScan("t")).isInstanceOf(PlanVerdict.Unbounded::class.java)
            assertThat(pool.settings()).isEqualTo(before).contains("enable_seqscan=on", "autoCommit=true")
        }
    }

    @Test
    fun `a statement that cannot be explained leaves the connection unchanged too`() {
        pool().use { pool ->
            val before = pool.settings()

            assertThatThrownBy {
                QueryPlans.explain(
                    pool,
                    "SELECT id FROM missing",
                    generic = false,
                )
            }.isInstanceOf(SQLException::class.java)

            assertThat(pool.settings()).isEqualTo(before).contains("enable_seqscan=on")
        }
    }
}
