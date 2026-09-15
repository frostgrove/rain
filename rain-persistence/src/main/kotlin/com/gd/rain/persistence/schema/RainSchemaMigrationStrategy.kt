package com.gd.rain.persistence.schema

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy

/**
 * How a rain application migrates: every module's schema first, in module-name order, each with its
 * own history table and explicit settings, then the application's own migrations.
 *
 * Module schemas are separate PostgreSQL schemas, so no module's Flyway ever sees another owner's
 * tables and no run ever needs a baseline. Application migrations may reference module tables;
 * module migrations never reference anything outside their schema.
 */
public class RainSchemaMigrationStrategy(
    public val descriptors: List<SchemaDescriptor>,
) : FlywayMigrationStrategy {
    @Volatile
    public var report: MigrationReport? = null
        private set

    override fun migrate(flyway: Flyway) {
        val modules = descriptors.map { descriptor -> ModuleMigration(descriptor.schema, flywayFor(flyway, descriptor).migrate()) }
        val application = flyway.migrate()
        val finished = MigrationReport(modules, application)
        report = finished
        modules.forEach { log.info("migrated schema {}: {} applied", it.schema, it.result.migrationsExecuted) }
        log.info("migrated application schema: {} applied", application.migrationsExecuted)
    }

    public companion object {
        private val log = LoggerFactory.getLogger(RainSchemaMigrationStrategy::class.java)

        public const val HISTORY_TABLE: String = "flyway_schema_history"

        /** A module's Flyway: nothing inherited from the application's configuration except the connection. */
        public fun flywayFor(
            application: Flyway,
            descriptor: SchemaDescriptor,
        ): Flyway =
            Flyway
                .configure(application.configuration.classLoader)
                .dataSource(application.configuration.dataSource)
                .schemas(descriptor.schema)
                .defaultSchema(descriptor.schema)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(descriptor.location)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .failOnMissingLocations(true)
                .load()
    }
}

public data class ModuleMigration(
    public val schema: String,
    public val result: MigrateResult,
)

public data class MigrationReport(
    public val modules: List<ModuleMigration>,
    public val application: MigrateResult,
)
