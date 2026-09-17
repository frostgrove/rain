package com.gd.rain.i18n.observability.autoconfigure

import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.observability.I18nMicrometerObserver
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Magic default for applications that have both this pairwise bridge and a MeterRegistry.
 *
 * A declared I18nObserver always wins. The bridge only supplies an observer; web, jobs and tenancy
 * decide at their own boundary whether and how to place it in an explicit ViewSpec.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry::class)
@ConditionalOnBean(MeterRegistry::class)
public class RainI18nObservabilityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(I18nObserver::class)
    public fun i18nMicrometerObserver(meters: MeterRegistry): I18nObserver = I18nMicrometerObserver(meters)
}
