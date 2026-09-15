package com.gd.rain.jobs

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `rain.jobs`, required whenever rain-jobs is on the classpath.
 *
 * Required, because no value is right for every deployment: [workers] (one ceiling per declared definition),
 * [requiredRecurring] (the application's recurring work this deployment runs; an empty list is a statement too),
 * [drainGrace] and [reservedConnections]. The nested sections carry declared tuning defaults.
 */
@ConfigurationProperties(JobsProperties.PREFIX)
public data class JobsProperties(
    /** Per definition name: how many of its attempts one process runs at once. Its profile's scheduler has Σ threads. */
    public val workers: Map<String, Int>,
    public val requiredRecurring: List<String>,
    /** How long a stopping worker waits for running attempts, for all schedulers together. */
    public val drainGrace: Duration,
    /** Pool connections kept for everything that is not a job scheduler: requests, health, operators. */
    public val reservedConnections: Int,
    public val lease: LeaseProperties = LeaseProperties(),
    public val scheduler: SchedulerProperties = SchedulerProperties(),
    public val reaper: ReaperProperties = ReaperProperties(),
    public val retention: RetentionProperties = RetentionProperties(),
) {
    public companion object {
        public const val PREFIX: String = "rain.jobs"
    }
}

/** The attempt lease: renewed every [renewInterval] (at most a third of [ttl]) while an attempt runs. */
public data class LeaseProperties(
    public val ttl: Duration = Duration.ofSeconds(60),
    public val renewInterval: Duration = Duration.ofSeconds(15),
) : ConfigurationSection

/** db-scheduler's own polling and dead-execution detection. */
public data class SchedulerProperties(
    public val pollInterval: Duration = Duration.ofSeconds(1),
    public val heartbeatInterval: Duration = Duration.ofSeconds(15),
    public val missedHeartbeats: Int = 4,
) : ConfigurationSection

/** The cluster-singleton pass that returns invocations with a lapsed lease to the queue, [batch] rows per pass. */
public data class ReaperProperties(
    public val interval: Duration = Duration.ofSeconds(15),
    public val batch: Int = 100,
) : ConfigurationSection

/** The cluster-singleton pass that deletes expired terminal invocations and released reservations. */
public data class RetentionProperties(
    public val interval: Duration = Duration.ofHours(1),
    public val batch: Int = 1_000,
    /** A pass stops starting new batches once it has run this long, and reports that it did not finish. */
    public val runBudget: Duration = Duration.ofSeconds(30),
) : ConfigurationSection

public class JobsConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(SectionSpec(JobsProperties.PREFIX, JobsProperties::class, Presence.REQUIRED) { properties, _ -> problemsOf(properties) })

    public companion object {
        public fun problemsOf(properties: JobsProperties): List<ConfigurationProblem> =
            problems {
                val p = JobsProperties.PREFIX
                properties.workers.toSortedMap().forEach { (definition, ceiling) ->
                    expect(ceiling >= 1, "$p.workers.$definition") { "is $ceiling; a definition with no worker never runs" }
                }
                expect(positive(properties.drainGrace), "$p.drain-grace") { "is ${properties.drainGrace.written()}; it has to be positive" }
                expect(
                    properties.reservedConnections >= 0,
                    "$p.reserved-connections",
                ) { "is ${properties.reservedConnections}; it cannot be negative" }
                properties.requiredRecurring.groupBy { it }.filterValues { it.size > 1 }.keys.sorted().forEach {
                    expect(false, "$p.required-recurring") { "names $it more than once" }
                }
                properties.requiredRecurring.filterNot(JobDefinition.NAME::matches).sorted().forEach {
                    expect(false, "$p.required-recurring") { "names \"$it\", which does not match ${JobDefinition.NAME.pattern}" }
                }
                val lease = properties.lease
                expect(positive(lease.ttl), "$p.lease.ttl") { "is ${lease.ttl.written()}; it has to be positive" }
                expect(
                    positive(lease.renewInterval),
                    "$p.lease.renew-interval",
                ) { "is ${lease.renewInterval.written()}; it has to be positive" }
                expect(lease.renewInterval.multipliedBy(3) <= lease.ttl, "$p.lease.renew-interval", ProblemCode.CONTRADICTS) {
                    "is ${lease.renewInterval.written()}, more than a third of lease.ttl ${lease.ttl.written()}; " +
                        "a lease has to survive two missed renewals"
                }
                val scheduler = properties.scheduler
                expect(
                    positive(scheduler.pollInterval),
                    "$p.scheduler.poll-interval",
                ) { "is ${scheduler.pollInterval.written()}; it has to be positive" }
                expect(positive(scheduler.heartbeatInterval), "$p.scheduler.heartbeat-interval") {
                    "is ${scheduler.heartbeatInterval.written()}; it has to be positive"
                }
                expect(
                    scheduler.missedHeartbeats >= 1,
                    "$p.scheduler.missed-heartbeats",
                ) { "is ${scheduler.missedHeartbeats}; it is at least 1" }
                expect(
                    positive(properties.reaper.interval),
                    "$p.reaper.interval",
                ) { "is ${properties.reaper.interval.written()}; it has to be positive" }
                expect(properties.reaper.batch >= 1, "$p.reaper.batch") { "is ${properties.reaper.batch}; a pass takes at least one row" }
                val retention = properties.retention
                expect(
                    positive(retention.interval),
                    "$p.retention.interval",
                ) { "is ${retention.interval.written()}; it has to be positive" }
                expect(retention.batch >= 1, "$p.retention.batch") { "is ${retention.batch}; a batch deletes at least one row" }
                expect(
                    positive(retention.runBudget),
                    "$p.retention.run-budget",
                ) { "is ${retention.runBudget.written()}; it has to be positive" }
            }

        private fun positive(duration: Duration): Boolean = duration.isPositive
    }
}
