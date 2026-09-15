package com.gd.rain.persistence.autoconfigure

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.ConditionalOnRainCommand
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.LockProperties
import com.gd.rain.persistence.PersistenceProperties
import com.gd.rain.persistence.fault.DataAccessFaultTranslator
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.persistence.jdbc.StatementTimeout
import com.gd.rain.persistence.jdbc.StatementTimeoutAgreementCheck
import com.gd.rain.persistence.lock.AdvisoryLockStore
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.persistence.lock.JooqAdvisoryLockStore
import com.gd.rain.persistence.schema.MigrateCommand
import com.gd.rain.persistence.schema.MigrateCommandDeclaration
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.persistence.schema.SingleMigrationStrategyCheck
import com.gd.rain.persistence.tx.TransactionRetry
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration

@AutoConfiguration(after = [JooqAutoConfiguration::class])
@ConditionalOnClass(DSLContext::class)
@EnableConfigurationProperties(PersistenceProperties::class, LockProperties::class)
public class RainPersistenceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun idGenerator(): IdGenerator = UuidV7Ids

    @Bean
    public fun transactionRetry(properties: PersistenceProperties): TransactionRetry =
        TransactionRetry(properties.retry.attempts, properties.retry.initialDelay, properties.retry.maxDelay)

    @Bean
    public fun dataAccessFaultTranslator(): FaultTranslator = DataAccessFaultTranslator

    @Bean
    public fun statementTimeout(properties: PersistenceProperties): StatementTimeout = StatementTimeout(properties.statementTimeout)

    @Bean
    public fun statementTimeoutJooqCustomizer(timeout: StatementTimeout): DefaultConfigurationCustomizer = timeout.jooqCustomizer()

    @Bean
    public fun statementTimeoutAgreementCheck(
        environment: Environment,
        timeout: StatementTimeout,
    ): ConfigurationCheck = StatementTimeoutAgreementCheck(environment, timeout)

    @Bean
    @ConditionalOnBean(DSLContext::class, PlatformTransactionManager::class)
    public fun advisoryLockStore(dsl: DSLContext): AdvisoryLockStore = JooqAdvisoryLockStore(dsl)

    @Bean
    @ConditionalOnBean(DSLContext::class, PlatformTransactionManager::class)
    public fun advisoryLocks(
        transactions: PlatformTransactionManager,
        store: AdvisoryLockStore,
        properties: LockProperties,
        persistence: PersistenceProperties,
    ): AdvisoryLocks =
        AdvisoryLocks(
            transactions,
            store,
            TransactionRetry(
                properties.attempts,
                persistence.retry.initialDelay,
                persistence.retry.maxDelay,
            ),
            properties.timeout,
        )

    public companion object {
        /** Static, so post-processing JdbcTemplate beans does not force this configuration to initialise early. */
        @JvmStatic
        @Bean
        public fun statementTimeoutJdbcTemplatePostProcessor(environment: Environment): BeanPostProcessor {
            val timeout =
                Binder.get(environment).bind("rain.persistence.statement-timeout", Duration::class.java).orElse(null)
                    ?: return object : BeanPostProcessor {}
            return StatementTimeout(timeout).jdbcTemplatePostProcessor()
        }
    }
}

/** Schema-per-module migrations; before Flyway's auto-configuration so its initializer finds the strategy. */
@AutoConfiguration(before = [FlywayAutoConfiguration::class])
@ConditionalOnClass(Flyway::class)
public class RainSchemaAutoConfiguration {
    @Bean
    public fun rainSchemaMigrationStrategy(): RainSchemaMigrationStrategy {
        val loaded = SchemaDescriptor.load()
        if (loaded.problems.isNotEmpty()) throw ConfigurationProblemsException(loaded.problems)
        return RainSchemaMigrationStrategy(loaded.descriptors)
    }

    @Bean
    public fun singleMigrationStrategyCheck(strategies: ObjectProvider<FlywayMigrationStrategy>): ConfigurationCheck =
        SingleMigrationStrategyCheck(strategies.orderedStream().toList())

    @Bean
    @ConditionalOnRainCommand(MigrateCommandDeclaration.NAME)
    public fun migrateCommand(strategy: RainSchemaMigrationStrategy): MigrateCommand = MigrateCommand(strategy)
}
