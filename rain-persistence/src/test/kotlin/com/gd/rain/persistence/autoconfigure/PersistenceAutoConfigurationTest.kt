package com.gd.rain.persistence.autoconfigure

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.tx.TransactionRetry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

/** Wiring only: the data source points nowhere, and nothing here connects. */
class PersistenceAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    JdbcTemplateAutoConfiguration::class.java,
                    JooqAutoConfiguration::class.java,
                    RainRuntimeAutoConfiguration::class.java,
                    RainPersistenceAutoConfiguration::class.java,
                    RainSchemaAutoConfiguration::class.java,
                ),
            ).withBean(DataSource::class.java, { PGSimpleDataSource().apply { setURL("jdbc:postgresql://127.0.0.1:1/none") } })
            .withPropertyValues("rain.runtime.roles=api", "rain.deployment.stage=test", "rain.persistence.statement-timeout=5s")

    @Test
    fun `persistence contributes ids, retry, fault translation, locks and the migration strategy`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(IdGenerator::class.java)).isSameAs(UuidV7Ids)
            assertThat(context).hasSingleBean(TransactionRetry::class.java)
            assertThat(context).hasSingleBean(FaultTranslator::class.java)
            assertThat(context).hasSingleBean(AdvisoryLocks::class.java)
            assertThat(context).hasSingleBean(RainSchemaMigrationStrategy::class.java)
        }
    }

    @Test
    fun `every JdbcTemplate carries the statement timeout`() {
        runner.run { context ->
            assertThat(context.getBean(JdbcTemplate::class.java).queryTimeout).isEqualTo(5)
        }
    }

    @Test
    fun `an application's own id generator wins`() {
        val fixed = IdGenerator { UUID(0, 1) }

        runner.withBean(IdGenerator::class.java, { fixed }).run { context ->
            assertThat(context.getBean(IdGenerator::class.java)).isSameAs(fixed)
        }
    }

    @Test
    fun `the test module descriptors are discovered in module order`() {
        runner.run { context ->
            assertThat(context.getBean(RainSchemaMigrationStrategy::class.java).descriptors.map { it.schema })
                .containsExactly("rain_alpha", "rain_beta")
        }
    }
}
