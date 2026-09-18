package com.gd.rain.event

import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.event.projection.postgres.PostgresProjectionCheckpointStore
import com.gd.rain.event.test.ProjectionCheckpointConformance
import com.gd.rain.event.test.ProjectionCheckpointConformanceTarget
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
import java.time.Clock
import java.time.Instant

@Tag("integration")
class PostgresProjectionCheckpointConformanceIT {
    @Test
    fun `postgres checkpoint adapter passes the reusable fenced checkpoint conformance`() {
        val database = RainPostgres.freshDatabase("projection_checkpoint_conformance")
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
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin)
        val target =
            object : ProjectionCheckpointConformanceTarget {
                override val id: String = "postgres"
                override val origin: EventLogOrigin = events.origin

                override fun checkpointStore(): com.gd.rain.event.projection.ProjectionCheckpointStore = checkpoints
            }

        ProjectionCheckpointConformance(target, "postgres", Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), java.time.ZoneOffset.UTC))
            .verify()
            .requireCertified()
    }
}
