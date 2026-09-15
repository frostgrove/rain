package com.gd.rain.observability.health

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.problems
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `rain.health` — the two numbers that bound an evaluation, and how much each health check matters.
 *
 * The section is optional: [checkTimeout] and [freshness] have declared defaults (2 s and 1 s). [checks]
 * has no default to give — a check's importance is the composition root's decision — so every
 * [HealthCheck] a process runs is named there (`rain.health.checks.database: required`) or the process
 * does not start.
 */
@ConfigurationProperties(HealthProperties.PREFIX)
public data class HealthProperties(
    /** The budget of a check that states none; counted from the start of the evaluation. */
    public val checkTimeout: Duration = DEFAULT_CHECK_TIMEOUT,
    /** How long one evaluation is served before the next caller evaluates again. */
    public val freshness: Duration = DEFAULT_FRESHNESS,
    /**
     * Check name to importance. One configuration usually serves every role, so an entry may name a check
     * that only another role runs; in a process that does not run it, the entry is reported not evaluated.
     */
    public val checks: Map<String, Importance> = emptyMap(),
) : ConfigurationSection {
    public fun problems(): List<ConfigurationProblem> =
        problems {
            expect(positive(checkTimeout), CHECK_TIMEOUT) { "is ${checkTimeout.written()}; it has to be positive" }
            expect(positive(freshness), FRESHNESS) { "is ${freshness.written()}; it has to be positive" }
            checks.keys.sorted().forEach { name ->
                expect(
                    NAME.matches(name),
                    checkPath(name),
                ) { "names the check \"$name\", which does not match ${HealthRegistry.NAME_PATTERN}" }
            }
        }

    public companion object {
        public const val PREFIX: String = "rain.health"
        public const val CHECK_TIMEOUT: String = "$PREFIX.check-timeout"
        public const val FRESHNESS: String = "$PREFIX.freshness"
        public const val CHECKS: String = "$PREFIX.checks"

        public val DEFAULT_CHECK_TIMEOUT: Duration = Duration.ofSeconds(2)
        public val DEFAULT_FRESHNESS: Duration = Duration.ofSeconds(1)

        /** Where the importance of the check named [name] is stated. */
        public fun checkPath(name: String): String = "$CHECKS.$name"

        private val NAME = Regex(HealthRegistry.NAME_PATTERN)

        private fun positive(duration: Duration): Boolean = !duration.isZero && !duration.isNegative
    }
}

/** Declares the `rain.health` section to rain's configuration validation. */
public class RainHealthConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(SectionSpec(HealthProperties.PREFIX, HealthProperties::class, Presence.OPTIONAL) { section, _ -> section.problems() })
}
