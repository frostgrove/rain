package com.gd.rain.jobs.support

import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/** A fresh database with every rain module schema migrated, and the handles a store test needs. */
internal class JobsDatabase private constructor(
    val database: RainDatabase,
) {
    val dataSource: DataSource = database.dataSource()
    val transactions: DataSourceTransactionManager = DataSourceTransactionManager(dataSource)

    /** Transaction-aware, as Boot's jOOQ auto-configuration builds it, so statements join a Spring transaction. */
    val dsl: DSLContext = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
    val jdbc: JdbcTemplate = JdbcTemplate(dataSource)

    fun <T> inTransaction(block: () -> T): T = requireNotNull(TransactionTemplate(transactions).execute { block() })

    fun count(sql: String): Long = requireNotNull(jdbc.queryForObject(sql, Long::class.java))

    companion object {
        fun fresh(prefix: String): JobsDatabase {
            val fixture = JobsDatabase(RainPostgres.freshDatabase(prefix))
            RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
                .migrate(
                    Flyway
                        .configure()
                        .dataSource(fixture.dataSource)
                        .locations("classpath:db/none")
                        .failOnMissingLocations(false)
                        .load(),
                )
            return fixture
        }
    }
}
