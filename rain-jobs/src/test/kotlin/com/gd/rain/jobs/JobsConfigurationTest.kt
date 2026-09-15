package com.gd.rain.jobs

import com.gd.rain.boot.config.RainConfigurationValidator
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

/** `rain.jobs` through rain-boot's validator, the way a starting application sees it. */
class JobsConfigurationTest {
    private fun validate(vararg properties: Pair<String, String>): List<ConfigurationProblem> {
        // Section problems are evaluated once the stage is known.
        val environment = MockEnvironment().withProperty("rain.deployment.stage", "test")
        properties.forEach { (key, value) -> environment.setProperty(key, value) }
        return RainConfigurationValidator
            .validate(environment, listOf(JobsConfigurationContributor()), emptyList())
            .problems
            .filter { it.path.startsWith("rain.jobs") }
    }

    private val complete =
        arrayOf(
            "rain.jobs.workers.tickets.summarize" to "3",
            "rain.jobs.workers.tickets-export" to "1",
            "rain.jobs.required-recurring" to "tickets.sweep",
            "rain.jobs.drain-grace" to "20s",
            "rain.jobs.reserved-connections" to "10",
        )

    @Test
    fun `a complete section has no problems, and a definition name with dots is one worker key`() {
        assertThat(validate(*complete)).isEmpty()

        val environment = MockEnvironment()
        complete.forEach { (key, value) -> environment.setProperty(key, value) }
        val bound = Binder.get(environment).bind(JobsProperties.PREFIX, JobsProperties::class.java).get()
        assertThat(bound.workers).containsExactlyInAnyOrderEntriesOf(mapOf("tickets.summarize" to 3, "tickets-export" to 1))
        assertThat(bound.lease).isEqualTo(LeaseProperties(Duration.ofSeconds(60), Duration.ofSeconds(15)))
    }

    @Test
    fun `the section is required, and each required leaf is named`() {
        assertThat(validate().map { it.path to it.code }).containsExactly("rain.jobs" to ProblemCode.REQUIRED)
        assertThat(validate("rain.jobs.drain-grace" to "20s").map { it.path to it.code }).containsExactlyInAnyOrder(
            "rain.jobs.workers" to ProblemCode.REQUIRED,
            "rain.jobs.required-recurring" to ProblemCode.REQUIRED,
            "rain.jobs.reserved-connections" to ProblemCode.REQUIRED,
        )
    }

    @Test
    fun `a renewal interval above a third of the lease ttl contradicts it`() {
        val problems = validate(*complete, "rain.jobs.lease.ttl" to "30s", "rain.jobs.lease.renew-interval" to "11s")

        assertThat(problems.map { it.path to it.code }).containsExactly("rain.jobs.lease.renew-interval" to ProblemCode.CONTRADICTS)
        assertThat(validate(*complete, "rain.jobs.lease.ttl" to "30s", "rain.jobs.lease.renew-interval" to "10s")).isEmpty()
    }

    @Test
    fun `tuning numbers that cannot be meant are refused`() {
        val problems =
            validate(
                *complete,
                "rain.jobs.workers.tickets.summarize" to "0",
                "rain.jobs.drain-grace" to "0s",
                "rain.jobs.reserved-connections" to "-1",
                "rain.jobs.reaper.batch" to "0",
                "rain.jobs.retention.run-budget" to "0s",
                "rain.jobs.scheduler.missed-heartbeats" to "0",
            )

        assertThat(problems.map { it.path }).containsExactlyInAnyOrder(
            "rain.jobs.workers.tickets.summarize",
            "rain.jobs.drain-grace",
            "rain.jobs.reserved-connections",
            "rain.jobs.reaper.batch",
            "rain.jobs.retention.run-budget",
            "rain.jobs.scheduler.missed-heartbeats",
        )
    }

    @Test
    fun `a key under rain jobs that nothing declares is unknown`() {
        assertThat(validate(*complete, "rain.jobs.lease.heartbeat" to "5s").map { it.path to it.code })
            .containsExactly("rain.jobs.lease.heartbeat" to ProblemCode.UNKNOWN_KEY)
    }
}
