package com.gd.rain.llm

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.llm.autoconfigure.RainLlmAutoConfiguration
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.observability.health.Importance
import com.gd.rain.resilience.BreakerDeclaration
import com.gd.rain.resilience.autoconfigure.RainResilienceAutoConfiguration
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID

/** The gateway exists exactly when `rain.llm.enabled` is true, over the application's own ChatModel and breaker. */
class LlmAutoConfigurationTest {
    private val store = MemorySlotStore()

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration::class.java,
                    RainRuntimeAutoConfiguration::class.java,
                    RainHealthAutoConfiguration::class.java,
                    RainResilienceAutoConfiguration::class.java,
                    RainLlmAutoConfiguration::class.java,
                ),
            ).withPropertyValues(
                "spring.application.name=sample",
                "rain.runtime.roles=api",
                "rain.deployment.stage=test",
                "rain.health.checks.breaker.model=degrading",
                "resilience4j.circuitbreaker.instances.model.wait-duration-in-open-state=30s",
            ).withBean(IdGenerator::class.java, { IdGenerator { UUID.randomUUID() } })
            .withBean(LlmSlotStore::class.java, { store })

    private val enabled = ENABLED_SECTION.map { (key, value) -> "$key=$value" }.toTypedArray()

    private val complete =
        runner
            .withPropertyValues(*enabled)
            .withBean(ChatModel::class.java, { ScriptedChatModel() })
            .withBean(
                "modelBreaker",
                BreakerDeclaration::class.java,
                { BreakerDeclaration(MODEL_BREAKER, "model_unavailable") },
            )

    @Test
    fun `without the section there is no gateway, and the module's codes and translator are declared`() {
        runner.run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(LlmGateway::class.java).doesNotHaveBean(LlmSettings::class.java)
            assertThat(context.getBeansOfType(ErrorCodeCatalog::class.java).values).contains(RainLlmErrorCodes)
            assertThat(context.getBeansOfType(FaultTranslator::class.java).values).contains(LlmFaultTranslator)
        }
    }

    @Test
    fun `a disabled section builds nothing`() {
        runner.withPropertyValues("rain.llm.enabled=false").run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(LlmGateway::class.java)
        }
    }

    @Test
    fun `an enabled section builds the gateway and creates the pool rows before traffic`() {
        complete.run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(LlmGateway::class.java)
            assertThat(store.ensured).containsExactly(listOf("default"))
        }
    }

    @Test
    fun `the configured breaker has to be declared`() {
        runner.withPropertyValues(*enabled).withBean(ChatModel::class.java, { ScriptedChatModel() }).run { context ->
            assertThat(problems(context)).containsExactly("rain.llm.breaker" to ProblemCode.INVALID)
        }
    }

    @Test
    fun `an enabled section without a ChatModel refuses start-up`() {
        runner
            .withPropertyValues(*enabled)
            .withBean("modelBreaker", BreakerDeclaration::class.java, { BreakerDeclaration(MODEL_BREAKER, null) })
            .run { context -> assertThat(problems(context)).containsExactly("rain.llm.enabled" to ProblemCode.REQUIRED) }
    }

    @Test
    fun `two token counters for the model refuse start-up`() {
        complete
            .withPropertyValues(
                "rain.llm.models.test-model.encoding=cl100k_base",
                "rain.llm.models.test-model.message-overhead=4",
                "rain.llm.models.test-model.prompt-overhead=3",
            ).withBean(TokenCounter::class.java, { FixedTokenCounter("test-model", 1) })
            .run { context -> assertThat(problems(context)).containsExactly("rain.llm.models.test-model" to ProblemCode.CONTRADICTS) }
    }

    private fun problems(context: AssertableApplicationContext): List<Pair<String, ProblemCode>> {
        assertThat(context).hasFailed()
        return generateSequence(context.startupFailure, Throwable::cause)
            .filterIsInstance<ConfigurationProblemsException>()
            .first()
            .problems
            .map { it.path to it.code }
    }
}
