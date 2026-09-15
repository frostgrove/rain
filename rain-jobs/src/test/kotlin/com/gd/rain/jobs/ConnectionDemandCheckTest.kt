package com.gd.rain.jobs

import com.gd.rain.core.config.ProblemCode
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

class ConnectionDemandCheckTest {
    private val catalog =
        JobCatalog(
            listOf(Fixtures.profile("interactive"), Fixtures.profile("batch")),
            listOf(
                Fixtures.definition("a.one", "interactive"),
                Fixtures.definition("a.two", "interactive"),
                Fixtures.definition("b.one", "batch"),
            ),
        )
    private val properties =
        JobsProperties(
            workers = mapOf("a.one" to 3, "a.two" to 2, "b.one" to 4),
            requiredRecurring = listOf("sweep"),
            drainGrace = Duration.ofSeconds(20),
            reservedConnections = 12,
        )
    private val sweep =
        object : RecurringWork {
            override val name = "sweep"
            override val interval: Duration = Duration.ofMinutes(1)

            override fun run() = Unit
        }

    private fun check(pool: Int?): ConnectionDemandCheck {
        val environment = MockEnvironment()
        pool?.let { environment.setProperty("spring.datasource.hikari.maximum-pool-size", it.toString()) }
        return ConnectionDemandCheck(catalog, listOf(sweep), properties, environment)
    }

    @Test
    fun `the demand is every thread and connection a worker can hold, term by term`() {
        val demand = check(100).demand()

        assertThat(demand.profileThreads).isEqualTo(9)
        assertThat(demand.recurringThreads).describedAs("reaper, retention and the sweep").isEqualTo(3)
        assertThat(demand.schedulers).describedAs("two profiles and recurring work").isEqualTo(3)
        assertThat(demand.libraryConnections).isEqualTo(12)
        assertThat(demand.total).isEqualTo(9 + 3 + 12 + 1 + 12)
    }

    @Test
    fun `a pool that holds the demand passes, and a smaller one contradicts it naming every term`() {
        assertThat(check(37).problems()).isEmpty()

        val problems = check(36).problems()

        assertThat(problems.map { it.path to it.code })
            .containsExactly("spring.datasource.hikari.maximum-pool-size" to ProblemCode.CONTRADICTS)
        assertThat(problems.single().message)
            .contains("is 36, below the worker's demand of 37")
            .contains("9 profile threads", "3 recurring threads", "12 db-scheduler connections (3 schedulers × 4)")
            .contains("1 lease renewer", "12 rain.jobs.reserved-connections")
    }

    @Test
    fun `an unstated pool size is required, not assumed`() {
        assertThat(check(null).problems().map { it.path to it.code })
            .containsExactly("spring.datasource.hikari.maximum-pool-size" to ProblemCode.REQUIRED)
    }

    @Test
    fun `declarations with problems leave the demand not evaluated`() {
        val broken = ConnectionDemandCheck(catalog, listOf(sweep), properties.copy(workers = mapOf("a.one" to 3)), MockEnvironment())

        assertThat(broken.problems().map { it.code }).containsExactly(ProblemCode.NOT_EVALUATED)
    }
}
