package com.gd.rain.realtime.autoconfigure

import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.realtime.HikariListenerConnections
import com.gd.rain.realtime.RealtimeErrorCodes
import com.gd.rain.realtime.RealtimeListener
import com.gd.rain.realtime.RealtimeProperties
import com.gd.rain.realtime.RealtimePublisher
import com.gd.rain.realtime.configurationProblems
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.ConfigurationPropertyState
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Present only when the application states a key under `rain.realtime` (see [OnRealtimeSectionCondition]).
 *
 * The publisher exists in every role: it writes on the caller's transaction and holds nothing. The
 * listener holds a connection for the life of the process, so it exists only where the API role runs.
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, JooqAutoConfiguration::class])
@Conditional(OnRealtimeSectionCondition::class)
@EnableConfigurationProperties(RealtimeProperties::class)
public class RainRealtimeAutoConfiguration {
    @Bean
    public fun realtimeErrorCodes(): ErrorCodeCatalog = RealtimeErrorCodes

    @Bean
    @ConditionalOnMissingBean
    public fun realtimePublisher(dsl: DSLContext): RealtimePublisher = RealtimePublisher(dsl)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRainRole(RuntimeRole.API)
    @EnableConfigurationProperties(DataSourceProperties::class)
    public class Listening {
        /** Started by the context as a `SmartLifecycle`; its pool is closed with the bean. */
        @Bean(destroyMethod = "close")
        public fun realtimeListener(
            properties: RealtimeProperties,
            dataSource: DataSourceProperties,
        ): RealtimeListener {
            val problems = properties.configurationProblems()
            if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
            return RealtimeListener(HikariListenerConnections.of(properties, dataSource), properties)
        }
    }
}

/**
 * Matches when a property source states `rain.realtime` or a key under it — the same test the
 * configuration validator uses to decide that an optional section is present, so a section is either
 * validated and wired, or absent and neither.
 */
internal class OnRealtimeSectionCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val section = ConfigurationPropertyName.of(RealtimeProperties.PREFIX)
        val stated =
            ConfigurationPropertySources.get(context.environment).any { source ->
                source.getConfigurationProperty(section) != null ||
                    source.containsDescendantOf(section) == ConfigurationPropertyState.PRESENT
            }
        return if (stated) {
            ConditionOutcome.match("a key under ${RealtimeProperties.PREFIX} is stated")
        } else {
            ConditionOutcome.noMatch("no key under ${RealtimeProperties.PREFIX} is stated")
        }
    }
}
