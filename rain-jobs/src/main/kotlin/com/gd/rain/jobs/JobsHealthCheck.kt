package com.gd.rain.jobs

import com.gd.rain.observability.health.HealthCheck
import java.time.Clock
import java.time.Duration

/**
 * The worker's readiness check, `jobs`. Its importance is the application's (`rain.health.checks.jobs`).
 *
 * It fails, naming each reason, when:
 * - the worker lifecycle is not running;
 * - a scheduler is not started, is shutting down, has never polled, or last finished a poll longer ago
 *   than `rain.jobs.health.max-poll-age`;
 * - the lease renewer is not running, has not run a round, or last ran longer ago than `rain.jobs.lease.ttl` —
 *   past that horizon the leases this process holds are lapsing.
 *
 * Wedged attempts are not a readiness failure; they are counted in `rain.jobs.attempts.wedged`.
 */
public class JobsHealthCheck(
    private val health: JobsHealth,
    private val maxPollAge: Duration,
    private val leaseTtl: Duration,
    private val clock: Clock,
) : HealthCheck {
    override val name: String = NAME
    override val code: String = NAME
    override val timeout: Duration? = null

    override fun probe() {
        val failures = failuresOf(health.report())
        check(failures.isEmpty()) { failures.joinToString("; ") }
    }

    /** Every reason [report] is failing, in the order above; empty when it passes. */
    public fun failuresOf(report: JobsHealthReport): List<String> {
        val now = clock.instant()
        val found = mutableListOf<String>()
        if (!report.running) found += "the job worker is not running"
        report.schedulers.forEach { scheduler ->
            val lastPoll = scheduler.lastPoll
            when {
                !scheduler.started -> {
                    found += "scheduler ${scheduler.name} is not started"
                }

                scheduler.shuttingDown -> {
                    found += "scheduler ${scheduler.name} is shutting down"
                }

                lastPoll == null -> {
                    found += "scheduler ${scheduler.name} has not polled"
                }

                Duration.between(lastPoll, now) > maxPollAge -> {
                    found += "scheduler ${scheduler.name} last polled at $lastPoll, longer ago than max-poll-age $maxPollAge"
                }
            }
        }
        val renewer = report.leaseRenewer
        val lastRound = renewer.lastRound
        when {
            !renewer.running -> {
                found += "the lease renewer is not running"
            }

            lastRound == null -> {
                found += "the lease renewer has not run a round"
            }

            Duration.between(lastRound, now) > leaseTtl -> {
                found += "the lease renewer last ran at $lastRound, longer ago than the lease ttl $leaseTtl"
            }
        }
        return found
    }

    public companion object {
        public const val NAME: String = "jobs"
    }
}
