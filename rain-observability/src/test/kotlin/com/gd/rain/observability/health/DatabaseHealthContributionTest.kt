package com.gd.rain.observability.health

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

/** A ping with this check's budget, and the connection given back either way. */
class DatabaseHealthContributionTest {
    private val connection = mockk<Connection>(relaxed = true)
    private val dataSource = mockk<DataSource>()

    init {
        every { dataSource.connection } returns connection
    }

    @Test
    fun `a database that answers the ping passes, and the connection goes back to the pool`() {
        every { connection.isValid(any()) } returns true

        contribution(Duration.ofSeconds(2)).probe()

        verify { connection.isValid(2) }
        verify { connection.close() }
    }

    @Test
    fun `a database that does not answer fails the check and still gives the connection back`() {
        every { connection.isValid(any()) } returns false

        assertThatThrownBy { contribution(Duration.ofSeconds(2)).probe() }
            .isInstanceOf(SQLException::class.java)
            .hasMessage("the database did not answer a ping within 2s")
        verify { connection.close() }
    }

    @Test
    fun `the driver timeout is the budget rounded up to whole seconds and never zero`() {
        assertThat(DatabaseHealthContribution.pingSecondsFor(Duration.ofMillis(400))).isEqualTo(1)
        assertThat(DatabaseHealthContribution.pingSecondsFor(Duration.ofMillis(2001))).isEqualTo(3)
        assertThat(DatabaseHealthContribution.pingSecondsFor(Duration.ofSeconds(2))).isEqualTo(2)
        assertThatThrownBy { DatabaseHealthContribution.pingSecondsFor(Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DatabaseHealthContribution.pingSecondsFor(Duration.ofSeconds(Int.MAX_VALUE.toLong() + 1)) }
            .isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `the check publishes its name and code and runs at the stated importance and budget`() {
        val contribution = DatabaseHealthContribution(dataSource, Importance.DEGRADING, Duration.ofMillis(1500))

        assertThat(contribution.name).isEqualTo("database")
        assertThat(contribution.code).isEqualTo("database")
        assertThat(contribution.importance).isEqualTo(Importance.DEGRADING)
        assertThat(contribution.timeout).isEqualTo(Duration.ofMillis(1500))
    }

    private fun contribution(budget: Duration) = DatabaseHealthContribution(dataSource, Importance.REQUIRED, budget)
}
