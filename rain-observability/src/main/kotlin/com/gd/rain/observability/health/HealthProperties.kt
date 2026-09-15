package com.gd.rain.observability.health

import com.gd.rain.boot.config.ConfigurationCheck
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
 * `rain.health` — the two numbers that bound an evaluation, and how much the database matters when
 * the application has one.
 *
 * The section is optional: [checkTimeout] and [freshness] have declared defaults (2 s and 1 s). The
 * database's importance has none — it is the composition root's decision, so a process with a
 * `DataSource` states `rain.health.database.importance` or does not start.
 */
@ConfigurationProperties(HealthProperties.PREFIX)
public data class HealthProperties(
    /** The budget of a check that states none; counted from the start of the evaluation. */
    public val checkTimeout: Duration = DEFAULT_CHECK_TIMEOUT,
    /** How long one evaluation is served before the next caller evaluates again. */
    public val freshness: Duration = DEFAULT_FRESHNESS,
    public val database: DatabaseHealthProperties? = null,
) : ConfigurationSection {
    public fun problems(): List<ConfigurationProblem> =
        problems {
            expect(positive(checkTimeout), CHECK_TIMEOUT) { "is ${checkTimeout.written()}; it has to be positive" }
            expect(positive(freshness), FRESHNESS) { "is ${freshness.written()}; it has to be positive" }
            database?.let {
                expect(it.importance != null, DatabaseHealthProperties.IMPORTANCE, ProblemCode.REQUIRED) {
                    "no value is provided; state one of ${Importance.wireNames.joinToString(", ")}"
                }
            }
        }

    public companion object {
        public const val PREFIX: String = "rain.health"
        public const val CHECK_TIMEOUT: String = "$PREFIX.check-timeout"
        public const val FRESHNESS: String = "$PREFIX.freshness"

        public val DEFAULT_CHECK_TIMEOUT: Duration = Duration.ofSeconds(2)
        public val DEFAULT_FRESHNESS: Duration = Duration.ofSeconds(1)

        private fun positive(duration: Duration): Boolean = !duration.isZero && !duration.isNegative
    }
}

/** `rain.health.database` — present when the application has a `DataSource`. */
public data class DatabaseHealthProperties(
    public val importance: Importance? = null,
) : ConfigurationSection {
    public companion object {
        public const val PREFIX: String = "${HealthProperties.PREFIX}.database"
        public const val IMPORTANCE: String = "$PREFIX.importance"
    }
}

/** Declares the `rain.health` section to rain's configuration validation. */
public class RainHealthConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(SectionSpec(HealthProperties.PREFIX, HealthProperties::class, Presence.OPTIONAL) { section, _ -> section.problems() })
}

/** A process with a `DataSource` states how much the database matters; nothing is inferred. */
public class DatabaseImportanceCheck(
    private val properties: HealthProperties,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        problems {
            expect(properties.database?.importance != null, DatabaseHealthProperties.IMPORTANCE, ProblemCode.REQUIRED) {
                "the application has a DataSource, so readiness has to know how much the database matters; " +
                    "state one of ${Importance.wireNames.joinToString(", ")}"
            }
        }
}
