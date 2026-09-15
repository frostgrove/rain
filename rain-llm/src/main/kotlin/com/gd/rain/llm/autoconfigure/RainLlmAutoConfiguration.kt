package com.gd.rain.llm.autoconfigure

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.ConditionalOnRainCommand
import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.llm.JooqLlmSlotStore
import com.gd.rain.llm.LlmConfigurationCheck
import com.gd.rain.llm.LlmFaultTranslator
import com.gd.rain.llm.LlmGateway
import com.gd.rain.llm.LlmPoolProvisioning
import com.gd.rain.llm.LlmProperties
import com.gd.rain.llm.LlmRequestCustomizer
import com.gd.rain.llm.LlmSettings
import com.gd.rain.llm.LlmSlotStore
import com.gd.rain.llm.LlmSlots
import com.gd.rain.llm.RainLlmErrorCodes
import com.gd.rain.llm.SlotPause
import com.gd.rain.llm.SmokeLlmCommand
import com.gd.rain.llm.SmokeLlmCommandDeclaration
import com.gd.rain.llm.TokenCounter
import com.gd.rain.llm.TokenCounters
import com.gd.rain.persistence.autoconfigure.RainPersistenceAutoConfiguration
import com.gd.rain.resilience.AdmissionGate
import com.gd.rain.resilience.BreakerDeclaration
import com.gd.rain.resilience.BreakerRegistry
import com.gd.rain.resilience.autoconfigure.RainResilienceAutoConfiguration
import org.jooq.DSLContext
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * rain-llm's error codes and fault translation, the `smoke-llm` command, and — when `rain.llm.enabled` is
 * true — the gateway over the application's `ChatModel`.
 */
@AutoConfiguration(after = [JooqAutoConfiguration::class, RainPersistenceAutoConfiguration::class, RainResilienceAutoConfiguration::class])
public class RainLlmAutoConfiguration {
    @Bean
    public fun rainLlmErrorCodes(): ErrorCodeCatalog = RainLlmErrorCodes

    @Bean
    public fun llmFaultTranslator(): FaultTranslator = LlmFaultTranslator

    @Bean
    @ConditionalOnRainCommand(SmokeLlmCommandDeclaration.NAME)
    public fun smokeLlmCommand(
        settings: ObjectProvider<LlmSettings>,
        models: ObjectProvider<ChatModel>,
        customizers: ObjectProvider<LlmRequestCustomizer>,
    ): SmokeLlmCommand = SmokeLlmCommand(settings.ifAvailable, { models.ifUnique }, customizers.orderedStream().toList())

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(LlmProperties.ENABLED)
    @EnableConfigurationProperties(LlmProperties::class)
    public class Enabled {
        @Bean
        public fun llmSettings(properties: LlmProperties): LlmSettings = LlmSettings.of(properties)

        @Bean
        @ConditionalOnMissingBean
        public fun llmSlotStore(dsl: DSLContext): LlmSlotStore = JooqLlmSlotStore(dsl)

        @Bean
        @ConditionalOnMissingBean
        public fun slotPause(): SlotPause = SlotPause.SLEEP

        @Bean
        public fun llmSlots(
            store: LlmSlotStore,
            settings: LlmSettings,
            clock: Clock,
            ids: IdGenerator,
            pause: SlotPause,
            environment: Environment,
        ): LlmSlots =
            LlmSlots(
                store,
                settings.timeout,
                settings.slotPollInterval,
                clock,
                ids,
                environment.getRequiredProperty("spring.application.name") + "/" + ProcessHandle.current().pid(),
                pause,
            )

        @Bean
        public fun tokenCounters(
            settings: LlmSettings,
            counters: ObjectProvider<TokenCounter>,
        ): TokenCounters = TokenCounters.of(settings, counters.orderedStream().toList())

        @Bean
        @ConditionalOnMissingBean
        public fun llmGateway(
            settings: LlmSettings,
            slots: LlmSlots,
            gate: AdmissionGate,
            breakers: BreakerRegistry,
            models: ObjectProvider<ChatModel>,
            customizers: ObjectProvider<LlmRequestCustomizer>,
            counters: TokenCounters,
            clock: Clock,
        ): LlmGateway =
            LlmGateway(settings, slots, gate, breakers, { models.getObject() }, customizers.orderedStream().toList(), counters, clock)

        @Bean
        public fun llmConfigurationCheck(
            settings: LlmSettings,
            declarations: ObjectProvider<BreakerDeclaration>,
            models: ObjectProvider<ChatModel>,
            counters: TokenCounters,
        ): ConfigurationCheck {
            val chatModels = models.stream().count().toInt()
            val resolvable = if (chatModels > 1 && models.ifUnique != null) 1 else chatModels
            return LlmConfigurationCheck(settings, declarations.orderedStream().toList(), resolvable, counters)
        }

        @Bean
        @ConditionalOnRainRole(RuntimeRole.API, RuntimeRole.WORKER, RuntimeRole.SEEDER)
        public fun llmPoolProvisioning(
            store: LlmSlotStore,
            settings: LlmSettings,
        ): LlmPoolProvisioning = LlmPoolProvisioning(store, settings)
    }
}
