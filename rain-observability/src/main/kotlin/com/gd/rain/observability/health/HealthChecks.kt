package com.gd.rain.observability.health

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import java.time.Duration

/** Turns the checks a process runs into the contributions its registry probes. */
public object HealthChecks {
    /**
     * Each check at the importance stated for it. A check with no stated importance is not turned into a
     * contribution: [HealthCheckImportanceCheck] refuses the start before anything is served.
     */
    public fun contributions(
        checks: List<HealthCheck>,
        stated: Map<String, Importance>,
    ): List<HealthContribution> = checks.mapNotNull { check -> stated[check.name]?.let { StatedImportance(check, it) } }
}

/** A [HealthCheck] at the importance the application stated for it. */
internal class StatedImportance(
    private val check: HealthCheck,
    override val importance: Importance,
) : HealthContribution {
    override val name: String get() = check.name
    override val code: String? get() = check.code
    override val timeout: Duration? get() = check.timeout

    override fun probe() {
        check.probe()
    }

    override fun toString(): String = "${check.javaClass.name} at ${importance.wire}"
}

/**
 * Every check this process runs has a stated importance.
 *
 * - a running check without an entry under `rain.health.checks` is `required`;
 * - an entry naming a contribution, which states its own importance, `contradicts` it;
 * - an entry naming a check this process does not run is `not_evaluated`: the same configuration
 *   serves roles that run it.
 */
public class HealthCheckImportanceCheck(
    private val checks: List<HealthCheck>,
    private val contributions: List<HealthContribution>,
    private val stated: Map<String, Importance>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        problems {
            val running = checks.map(HealthCheck::name).toSortedSet()
            val contributed = contributions.map(HealthContribution::name).toSet()
            running.forEach { name ->
                expect(name in stated, HealthProperties.checkPath(name), ProblemCode.REQUIRED) {
                    "no value is provided; this process runs the health check $name, so state one of " +
                        Importance.wireNames.joinToString(", ")
                }
            }
            stated.keys.sorted().forEach { name ->
                val path = HealthProperties.checkPath(name)
                when {
                    name in contributed -> {
                        add(
                            ConfigurationProblem(
                                path,
                                ProblemCode.CONTRADICTS,
                                "names $name, a contribution that states its own importance",
                            ),
                        )
                    }

                    name !in running -> {
                        add(ConfigurationProblem(path, ProblemCode.NOT_EVALUATED, "names no health check this process runs"))
                    }
                }
            }
        }
}
