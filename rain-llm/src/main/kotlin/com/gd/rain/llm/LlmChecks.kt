package com.gd.rain.llm

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import com.gd.rain.resilience.BreakerDeclaration
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * What an enabled `rain.llm` needs from the beans: a declared breaker by the configured name, exactly one
 * `ChatModel`, and at most one token counter per model.
 */
public class LlmConfigurationCheck(
    private val settings: LlmSettings,
    private val declarations: List<BreakerDeclaration>,
    private val chatModels: Int,
    private val counters: TokenCounters,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        problems {
            expect(declarations.any { it.name == settings.breaker }, "${LlmProperties.PREFIX}.breaker") {
                "names breaker ${settings.breaker}, which no BreakerDeclaration bean declares"
            }
            expect(chatModels >= 1, LlmProperties.ENABLED, ProblemCode.REQUIRED) { "is true, and the application has no ChatModel bean" }
            expect(chatModels <= 1, LlmProperties.ENABLED, ProblemCode.CONTRADICTS) {
                "is true, and the application has $chatModels ChatModel beans and none is primary"
            }
            addAll(counters.problems())
        }
}

/** Creates the budget row of every configured pool, before the process takes traffic. */
public class LlmPoolProvisioning(
    private val store: LlmSlotStore,
    private val settings: LlmSettings,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        store.ensurePools(settings.pools.keys)
    }
}
