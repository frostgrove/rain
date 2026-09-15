package com.gd.rain.observability.autoconfigure

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.observability.health.DatabaseHealthCheck
import com.gd.rain.observability.health.HealthCheck
import com.gd.rain.observability.health.HealthCheckImportanceCheck
import com.gd.rain.observability.health.HealthChecks
import com.gd.rain.observability.health.HealthContribution
import com.gd.rain.observability.health.HealthDrainingListener
import com.gd.rain.observability.health.HealthProperties
import com.gd.rain.observability.health.HealthRegistry
import com.gd.rain.observability.health.actuator.ActuatorHealthBridge
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration
import org.springframework.boot.health.registry.HealthContributorRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/**
 * The [HealthRegistry] over every [HealthContribution] bean and every [HealthCheck] bean at its stated
 * importance, draining on context close, and the database check when the application has a `DataSource`.
 */
@AutoConfiguration(
    after = [RainRuntimeAutoConfiguration::class],
    afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"],
)
@EnableConfigurationProperties(HealthProperties::class)
public class RainHealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun healthRegistry(
        contributions: ObjectProvider<HealthContribution>,
        checks: ObjectProvider<HealthCheck>,
        properties: HealthProperties,
        clock: Clock,
    ): HealthRegistry =
        HealthRegistry(
            contributions.orderedStream().toList() + HealthChecks.contributions(checks.orderedStream().toList(), properties.checks),
            properties.checkTimeout,
            properties.freshness,
            clock,
        )

    @Bean
    public fun healthDrainingListener(registry: HealthRegistry): HealthDrainingListener = HealthDrainingListener(registry)

    @Bean
    public fun healthCheckImportanceCheck(
        contributions: ObjectProvider<HealthContribution>,
        checks: ObjectProvider<HealthCheck>,
        properties: HealthProperties,
    ): ConfigurationCheck =
        HealthCheckImportanceCheck(checks.orderedStream().toList(), contributions.orderedStream().toList(), properties.checks)

    /** The database check; its importance is `rain.health.checks.database` and is never inferred. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource::class)
    public class DatabaseHealthConfiguration {
        @Bean
        public fun databaseHealthCheck(
            dataSource: DataSource,
            properties: HealthProperties,
        ): HealthCheck = DatabaseHealthCheck(dataSource, properties.checkTimeout)
    }
}

/** Publishes every contribution's reading to Actuator's health registry, when the application has one. */
@AutoConfiguration(after = [RainHealthAutoConfiguration::class, HealthContributorRegistryAutoConfiguration::class])
@ConditionalOnClass(HealthContributorRegistry::class)
public class RainActuatorHealthAutoConfiguration {
    @Bean
    @ConditionalOnBean(HealthContributorRegistry::class, HealthRegistry::class)
    public fun actuatorHealthBridge(
        registry: HealthRegistry,
        contributors: HealthContributorRegistry,
    ): ActuatorHealthBridge = ActuatorHealthBridge(registry, contributors).also(ActuatorHealthBridge::register)
}
