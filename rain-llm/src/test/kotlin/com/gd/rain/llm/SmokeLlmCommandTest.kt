package com.gd.rain.llm

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.llm.autoconfigure.RainLlmAutoConfiguration
import com.gd.rain.observability.autoconfigure.RainHealthAutoConfiguration
import com.gd.rain.observability.health.Importance
import com.gd.rain.resilience.BreakerDeclaration
import com.gd.rain.resilience.autoconfigure.RainResilienceAutoConfiguration
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

/** `smoke-llm` as a real command start: one round trip to the model, exit 0 or 1. */
class SmokeLlmCommandTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        CircuitBreakerAutoConfiguration::class,
        RainRuntimeAutoConfiguration::class,
        RainHealthAutoConfiguration::class,
        RainResilienceAutoConfiguration::class,
        RainLlmAutoConfiguration::class,
    )
    class Application {
        @Bean
        fun ids(): IdGenerator = IdGenerator { UUID.randomUUID() }

        @Bean
        fun modelBreaker(): BreakerDeclaration = BreakerDeclaration(MODEL_BREAKER, "model_unavailable", Importance.DEGRADING)
    }

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val store = MemorySlotStore()

    @Test
    fun `a model that answers exits zero, holding no slot and touching no breaker`() {
        val context = start(ScriptedChatModel().answers("OK"), *ENABLED)

        val breaker = context.getBean(CircuitBreakerRegistry::class.java).find("model").get()
        assertThat(breaker.metrics.numberOfBufferedCalls).isZero()
        assertThat(SpringApplication.exit(context)).isZero()
        assertThat(out.toString()).contains("smoke-llm: model test-model answered with 2 characters")
        assertThat(store.attempts.get()).isZero()
        assertThat(store.ensured).describedAs("a command process has no role, so it provisions no pool").isEmpty()
    }

    @Test
    fun `a model that fails exits one with the reason`() {
        val context = start(ScriptedChatModel().fails(IllegalStateException("connection refused")), *ENABLED)

        assertThat(SpringApplication.exit(context)).isEqualTo(1)
        assertThat(err.toString()).contains("smoke-llm: connection refused")
    }

    @Test
    fun `a model that answers nothing exits one`() {
        val context = start(ScriptedChatModel().answers(""), *ENABLED)

        assertThat(SpringApplication.exit(context)).isEqualTo(1)
        assertThat(err.toString()).contains("without usable text")
    }

    @Test
    fun `a deployment with the model disabled exits one and says why`() {
        val context = start(ScriptedChatModel(), "rain.llm.enabled=false")

        assertThat(SpringApplication.exit(context)).isEqualTo(1)
        assertThat(err.toString()).contains("smoke-llm: rain.llm.enabled is not true")
    }

    private fun start(
        model: ChatModel,
        vararg properties: String,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(Application::class.java)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                    context.beanFactory.registerSingleton("commandOutput", CommandOutput(PrintStream(out, true), PrintStream(err, true)))
                    context.beanFactory.registerSingleton("llmSlotStore", store)
                    context.beanFactory.registerSingleton("chatModel", model)
                },
            ).properties(
                "spring.application.name=sample",
                "rain.deployment.stage=test",
                "rain.runtime.command=smoke-llm",
                "rain.persistence.statement-timeout=5s",
                "resilience4j.circuitbreaker.instances.model.wait-duration-in-open-state=30s",
                *properties,
            ).run()

    private companion object {
        val ENABLED: Array<String> = ENABLED_SECTION.map { (key, value) -> "$key=$value" }.toTypedArray()
    }
}
