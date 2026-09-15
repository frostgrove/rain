package com.gd.rain.observability.health.actuator

import com.gd.rain.observability.health.CheckState
import com.gd.rain.observability.health.HealthContribution
import com.gd.rain.observability.health.HealthReadings
import com.gd.rain.observability.health.HealthRegistry
import com.gd.rain.observability.health.Importance
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.health.registry.HealthContributorRegistry

/**
 * One contribution's reading, published to Actuator.
 *
 * The indicator never probes: it reads the evaluation [HealthRegistry] already made, so a scrape of
 * the Actuator health endpoint and a scrape of `/ready` share one evaluation and one freshness window.
 * The reading is found by name in the evaluation's index — one map lookup per indicator, never a pass
 * over every check.
 *
 * The status is a declared table: passing is `UP`; disabled is `UNKNOWN`; failing is `DOWN` for a
 * required check, [DEGRADED] for a degrading one and `UP` for an informational one, whose failure
 * readiness does not count either. How `DEGRADED` ranks and which HTTP status it maps to on the
 * management endpoint is the application's Actuator configuration
 * (`management.endpoint.health.status.order`, `management.endpoint.health.status.http-mapping`).
 */
public class HealthContributionIndicator(
    private val contribution: HealthContribution,
    private val readings: HealthReadings,
) : HealthIndicator {
    override fun health(): Health {
        val reading =
            checkNotNull(readings.inspect().reading(contribution.name)) {
                "the evaluation holds no reading for \"${contribution.name}\", which the registry accepted"
            }
        val builder =
            when (reading.state) {
                CheckState.PASSING -> Health.up()
                CheckState.DISABLED -> Health.unknown()
                CheckState.FAILING -> failureOf(contribution.importance)
            }
        contribution.code?.let { builder.withDetail("code", it) }
        builder.withDetail("importance", contribution.importance.wire)
        reading.message?.let { builder.withDetail("error", it) }
        return builder.build()
    }

    private fun failureOf(importance: Importance): Health.Builder =
        when (importance) {
            Importance.REQUIRED -> Health.down()
            Importance.DEGRADING -> Health.status(DEGRADED)
            Importance.INFORMATIONAL -> Health.up()
            Importance.DISABLED -> Health.unknown()
        }

    public companion object {
        /** The status of a process that is worse than it was and still worth traffic. */
        public val DEGRADED: Status = Status("DEGRADED")
    }
}

/**
 * Registers one indicator per contribution in Actuator's registry, under the contribution's name, and
 * removes them when the context closes.
 */
public class ActuatorHealthBridge(
    private val registry: HealthRegistry,
    private val contributors: HealthContributorRegistry,
) : DisposableBean {
    public fun register() {
        registry.contributions().forEach { contribution ->
            contributors.registerContributor(contribution.name, HealthContributionIndicator(contribution, registry))
        }
    }

    override fun destroy() {
        registry.contributions().forEach { contributors.unregisterContributor(it.name) }
    }
}
