package com.gd.rain.event

import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.event.projection.ProjectionCheckpointStore
import com.gd.rain.event.projection.SameUnitProjectionHoldStore
import com.gd.rain.event.projection.postgres.PostgresProjectionCheckpointStore
import com.gd.rain.event.projection.postgres.PostgresProjectionHoldStore
import com.gd.rain.event.test.ProjectionHoldConformance
import com.gd.rain.event.test.ProjectionHoldConformanceTarget
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.RainPostgres
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Tag("integration")
class PostgresProjectionHoldConformanceIT {
    @Test
    fun `postgres hold adapter passes the reusable park and redrive conformance`() {
        val database = RainPostgres.freshDatabase("projection_hold_conformance")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val events = PostgresEventStore(dsl, transactions)
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin, transactions)
        val holds = PostgresProjectionHoldStore(dsl, events.origin, transactions)
        val target =
            object : ProjectionHoldConformanceTarget {
                override val id: String = "postgres-hold"
                override val origin: EventLogOrigin = events.origin

                override fun checkpointStore(): ProjectionCheckpointStore = checkpoints

                override fun holdStore(): SameUnitProjectionHoldStore = holds

                override fun <T : Any> inUnit(block: () -> T): T =
                    checkNotNull(TransactionTemplate(transactions).execute { block() }) {
                        "postgres hold conformance transaction returned null"
                    }
            }

        ProjectionHoldConformance(target, "postgres-hold", Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC))
            .verify()
            .requireCertified()
    }
}
