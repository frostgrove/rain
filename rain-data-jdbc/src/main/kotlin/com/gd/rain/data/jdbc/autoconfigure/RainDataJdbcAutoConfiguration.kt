package com.gd.rain.data.jdbc.autoconfigure

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.data.jdbc.AssignIdCallback
import com.gd.rain.data.jdbc.JdbcConversionContribution
import com.gd.rain.data.jdbc.RainConverters
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.data.relational.core.mapping.RelationalMappingContext

/**
 * Before Boot's repository auto-configuration, so these conversions take the slot its own
 * `@ConditionalOnMissingBean` conversions would. Nothing here registers repositories: the
 * application's repositories stay Boot's to find.
 */
@AutoConfiguration(before = [DataJdbcRepositoriesAutoConfiguration::class])
@ConditionalOnClass(JdbcAggregateTemplate::class)
public class RainDataJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun jdbcCustomConversions(contributions: ObjectProvider<JdbcConversionContribution>): JdbcCustomConversions =
        JdbcCustomConversions(RainConverters.ALL + contributions.orderedStream().toList().flatMap { it.converters() })

    @Bean
    public fun assignIdCallback(
        ids: IdGenerator,
        mappingContext: ObjectProvider<RelationalMappingContext>,
    ): AssignIdCallback = AssignIdCallback(ids, mappingContext)
}
