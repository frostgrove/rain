package com.gd.rain.resilience

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import io.github.resilience4j.common.circuitbreaker.configuration.CommonCircuitBreakerConfigurationProperties

/**
 * The rules a declared breaker's configuration obeys (version 1), checked once beans exist:
 *
 * 1. a breaker is declared once;
 * 2. it has an explicit `resilience4j.circuitbreaker.instances.<name>` entry — never the library's
 *    default configuration;
 * 3. the instance entry itself states a positive `wait-duration-in-open-state` — never the library's
 *    built-in value. Resilience4j sets the open-wait interval from the instance's own wait and flags
 *    whenever the instance states a positive wait, whatever the configuration it inherits says, so this
 *    is where the wait is decided;
 * 4. its open wait is fixed: the instance does not turn on `enable-exponential-backoff` or
 *    `enable-randomized-wait`, because a held caller is told one cooldown and the gate hands out one
 *    probe per cooldown;
 * 5. no `CircuitBreakerConfigCustomizer` names it, because a customizer changes the configuration where
 *    rules 3 and 4 cannot read it.
 */
public class BreakerConfigurationCheck(
    private val declarations: List<BreakerDeclaration>,
    private val properties: CommonCircuitBreakerConfigurationProperties,
    private val customizers: List<CircuitBreakerConfigCustomizer>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val customized = customizers.map(CircuitBreakerConfigCustomizer::name).toSet()
        return problems {
            declarations.groupBy(BreakerDeclaration::name).toSortedMap(compareBy(BreakerName::value)).forEach { (name, declared) ->
                val path = "$INSTANCES.$name"
                expect(declared.size == 1, "breaker:$name", ProblemCode.CONTRADICTS) {
                    "breaker $name is declared ${declared.size} times"
                }
                val instance = properties.instances[name.value]
                if (instance == null) {
                    add(
                        ConfigurationProblem(
                            path,
                            ProblemCode.REQUIRED,
                            "breaker $name is declared, so its configuration is stated explicitly; it never falls back to the default",
                        ),
                    )
                    return@forEach
                }
                val wait = instance.waitDurationInOpenState
                if (wait == null) {
                    add(
                        ConfigurationProblem(
                            "$path.wait-duration-in-open-state",
                            ProblemCode.REQUIRED,
                            "no value is provided on the instance",
                        ),
                    )
                } else {
                    expect(wait.toMillis() > 0, "$path.wait-duration-in-open-state") { "is $wait; it has to be at least one millisecond" }
                }
                expect(instance.enableExponentialBackoff != true, "$path.enable-exponential-backoff") {
                    "is on; a declared breaker's open wait is fixed, so a held caller can be told when to return"
                }
                expect(instance.enableRandomizedWait != true, "$path.enable-randomized-wait") {
                    "is on; a declared breaker's open wait is fixed, so a held caller can be told when to return"
                }
                expect(name.value !in customized, path, ProblemCode.CONTRADICTS) {
                    "a CircuitBreakerConfigCustomizer names breaker $name; a declared breaker is configured by its properties alone"
                }
            }
        }
    }

    public companion object {
        public const val INSTANCES: String = "resilience4j.circuitbreaker.instances"
    }
}
