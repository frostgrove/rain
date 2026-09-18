package com.gd.rain.observability.health

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** The stated-importance adapter preserves a check's machine contract and delegates probing. */
class HealthChecksTest {
    @Test
    fun `a stated check delegates to its source and missing importance excludes it`() {
        val database = TestCheck()
        val included = HealthChecks.contributions(listOf(database), mapOf("database" to Importance.DEGRADING)).single()

        included.probe()

        assertThat(database.asked).isEqualTo(1)
        assertThat(included.name).isEqualTo("database")
        assertThat(included.code).isEqualTo("postgres")
        assertThat(included.timeout).isNull()
        assertThat(included.importance).isEqualTo(Importance.DEGRADING)
        assertThat(included.toString()).endsWith("at ${Importance.DEGRADING.wire}")
        assertThat(HealthChecks.contributions(listOf(database), emptyMap())).isEmpty()
    }

    private class TestCheck : HealthCheck {
        var asked: Int = 0

        override val name: String = "database"
        override val code: String = "postgres"
        override val timeout: Duration? = null

        override fun probe() {
            asked++
        }
    }
}
