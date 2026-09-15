package com.gd.rain.jobs

import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** Readiness of the worker: each failure is a stated rule over the report, and the passing report names none. */
class JobsHealthCheckTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = MutableClock(now)
    private var report = healthy()
    private val check =
        JobsHealthCheck(
            object : JobsHealth {
                override fun report(): JobsHealthReport = report
            },
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            clock,
        )

    private fun healthy() =
        JobsHealthReport(
            running = true,
            schedulers =
                listOf(
                    SchedulerHealth(
                        "standard",
                        3,
                        started = true,
                        shuttingDown = false,
                        lastPoll = now.minusSeconds(1),
                        wedgedAttempts = 0,
                    ),
                    SchedulerHealth(SchedulerHealth.RECURRING, 2, started = true, shuttingDown = false, lastPoll = now, wedgedAttempts = 0),
                ),
            leaseRenewer = RenewerHealth(running = true, lastRound = now.minusSeconds(10), activeLeases = 4),
        )

    @Test
    fun `a running worker whose schedulers polled and whose renewer ran passes`() {
        check.probe()
        assertThat(check.name).isEqualTo("jobs")
        assertThat(check.code).isEqualTo("jobs")
    }

    @Test
    fun `a scheduler at exactly the max poll age still passes and one past it fails`() {
        report = healthy().copy(schedulers = listOf(healthy().schedulers[0].copy(lastPoll = now.minusSeconds(30))))
        assertThat(check.failuresOf(report)).isEmpty()

        report = healthy().copy(schedulers = listOf(healthy().schedulers[0].copy(lastPoll = now.minusSeconds(31))))
        assertThat(check.failuresOf(report))
            .containsExactly("scheduler standard last polled at 2026-01-01T11:59:29Z, longer ago than max-poll-age PT30S")
    }

    @Test
    fun `every failing rule is named at once`() {
        report =
            JobsHealthReport(
                running = false,
                schedulers =
                    listOf(
                        SchedulerHealth("a", 1, started = false, shuttingDown = false, lastPoll = null, wedgedAttempts = 0),
                        SchedulerHealth("b", 1, started = true, shuttingDown = true, lastPoll = now, wedgedAttempts = 0),
                        SchedulerHealth("c", 1, started = true, shuttingDown = false, lastPoll = null, wedgedAttempts = 0),
                    ),
                leaseRenewer = RenewerHealth(running = false, lastRound = null, activeLeases = 0),
            )

        assertThat(check.failuresOf(report)).containsExactly(
            "the job worker is not running",
            "scheduler a is not started",
            "scheduler b is shutting down",
            "scheduler c has not polled",
            "the lease renewer is not running",
        )
        assertThatThrownBy {
            check.probe()
        }.isInstanceOf(IllegalStateException::class.java).hasMessageStartingWith("the job worker is not running; ")
    }

    @Test
    fun `a renewer past the lease ttl fails, one that has not run a round fails, and wedged attempts do not`() {
        report = healthy().copy(leaseRenewer = RenewerHealth(running = true, lastRound = now.minusSeconds(61), activeLeases = 1))
        assertThat(check.failuresOf(report))
            .containsExactly("the lease renewer last ran at 2026-01-01T11:58:59Z, longer ago than the lease ttl PT1M")

        report = healthy().copy(leaseRenewer = RenewerHealth(running = true, lastRound = null, activeLeases = 0))
        assertThat(check.failuresOf(report)).containsExactly("the lease renewer has not run a round")

        report = healthy().copy(schedulers = listOf(healthy().schedulers[0].copy(wedgedAttempts = 5)))
        assertThat(check.failuresOf(report)).isEmpty()
    }

    @Test
    fun `a max poll age that is not above the poll interval contradicts it`() {
        val problems =
            JobsConfigurationContributor.problemsOf(
                JobsProperties(
                    workers = mapOf("notes.write" to 1),
                    requiredRecurring = emptyList(),
                    drainGrace = Duration.ofSeconds(10),
                    reservedConnections = 1,
                    scheduler = SchedulerProperties(pollInterval = Duration.ofSeconds(5)),
                    health = JobsHealthProperties(maxPollAge = Duration.ofSeconds(5)),
                ),
            )

        assertThat(problems.map { it.path to it.code })
            .containsExactly("rain.jobs.health.max-poll-age" to com.gd.rain.core.config.ProblemCode.CONTRADICTS)
    }
}
