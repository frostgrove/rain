package com.gd.rain.resilience.autoconfigure

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.resilience.AdmissionGate
import com.gd.rain.resilience.BreakerConfigurationCheck
import com.gd.rain.resilience.BreakerDeclaration
import com.gd.rain.resilience.BreakerHealthRegistrar
import com.gd.rain.resilience.BreakerRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import io.github.resilience4j.common.circuitbreaker.configuration.CommonCircuitBreakerConfigurationProperties
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * rain's breakers over the Resilience4j starter.
 *
 * The starter's `CircuitBreakerAutoConfiguration` is **used on purpose**: its `CircuitBreakerRegistry` bean
 * is the only registry, and its `resilience4j.circuitbreaker.*` properties are the only breaker
 * configuration. Nothing here builds a registry of its own.
 */
@AutoConfiguration(after = [CircuitBreakerAutoConfiguration::class, RainRuntimeAutoConfiguration::class])
public class RainResilienceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun breakerRegistry(
        circuitBreakers: CircuitBreakerRegistry,
        declarations: ObjectProvider<BreakerDeclaration>,
    ): BreakerRegistry = BreakerRegistry(circuitBreakers, declarations.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    public fun admissionGate(breakers: BreakerRegistry): AdmissionGate = AdmissionGate(breakers)

    @Bean
    public fun breakerConfigurationCheck(
        declarations: ObjectProvider<BreakerDeclaration>,
        properties: CommonCircuitBreakerConfigurationProperties,
        customizers: ObjectProvider<CircuitBreakerConfigCustomizer>,
    ): ConfigurationCheck =
        BreakerConfigurationCheck(declarations.orderedStream().toList(), properties, customizers.orderedStream().toList())

    public companion object {
        /** Static, so registering health contributions does not initialise this configuration early. */
        @JvmStatic
        @Bean
        public fun breakerHealthRegistrar(): BreakerHealthRegistrar = BreakerHealthRegistrar()
    }
}
