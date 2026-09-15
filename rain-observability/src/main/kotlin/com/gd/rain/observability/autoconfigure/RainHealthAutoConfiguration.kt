package com.gd.rain.observability.autoconfigure

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.observability.health.DatabaseHealthContribution
import com.gd.rain.observability.health.DatabaseHealthProperties
import com.gd.rain.observability.health.DatabaseImportanceCheck
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration
import org.springframework.boot.health.registry.HealthContributorRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/**
 * The [HealthRegistry] over every [HealthContribution] bean, draining on context close, and the
 * database contribution when the application has a `DataSource`.
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
        properties: HealthProperties,
        clock: Clock,
    ): HealthRegistry = HealthRegistry(contributions.orderedStream().toList(), properties.checkTimeout, properties.freshness, clock)

    @Bean
    public fun healthDrainingListener(registry: HealthRegistry): HealthDrainingListener = HealthDrainingListener(registry)

    /** The database check; its importance is `rain.health.database.importance` and is never inferred. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource::class)
    public class DatabaseHealthConfiguration {
        @Bean
        public fun databaseImportanceCheck(properties: HealthProperties): ConfigurationCheck = DatabaseImportanceCheck(properties)

        @Bean
        @ConditionalOnProperty(name = [DatabaseHealthProperties.IMPORTANCE])
        public fun databaseHealthContribution(
            dataSource: DataSource,
            properties: HealthProperties,
        ): HealthContribution =
            DatabaseHealthContribution(
                dataSource,
                checkNotNull(
                    properties.database?.importance,
                ) { "${DatabaseHealthProperties.IMPORTANCE} is stated but binds to no importance" },
                properties.checkTimeout,
            )
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
