package com.gd.rain.observability.health

import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/**
 * The database check: borrow a connection and ask the driver whether it is still valid.
 *
 * A validation query would be a different question — it reaches the server through the planner, and a
 * `SELECT 1` that answers proves more than readiness needs while a pool with no connection to give
 * proves less. `Connection.isValid` takes whole seconds and treats 0 as "no timeout", so the driver is
 * given the budget rounded up to the next whole second; the registry still cuts the check at the
 * budget itself.
 *
 * Its importance is stated by the application as `rain.health.checks.database`.
 */
public class DatabaseHealthCheck(
    private val dataSource: DataSource,
    budget: Duration,
) : HealthCheck {
    override val name: String = NAME
    override val code: String = NAME
    override val timeout: Duration = budget

    private val pingSeconds: Int = pingSecondsFor(budget)

    override fun probe() {
        dataSource.connection.use { connection ->
            if (!connection.isValid(pingSeconds)) {
                throw SQLException("the database did not answer a ping within ${pingSeconds}s")
            }
        }
    }

    public companion object {
        public const val NAME: String = "database"

        /** The budget in whole seconds, rounded up; a budget that is not positive or exceeds `Int.MAX_VALUE` seconds is refused. */
        public fun pingSecondsFor(budget: Duration): Int {
            require(!budget.isZero && !budget.isNegative) { "a database check budget is positive, got $budget" }
            val seconds = if (budget.nano > 0) Math.addExact(budget.seconds, 1L) else budget.seconds
            return Math.toIntExact(seconds)
        }
    }
}
