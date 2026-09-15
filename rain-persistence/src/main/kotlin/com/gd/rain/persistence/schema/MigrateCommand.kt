package com.gd.rain.persistence.schema

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.boot.command.RainCommand
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.flywaydb.core.api.output.MigrateResult
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy

/**
 * `migrate`: a process that applies every pending migration and exits. It enables Flyway for itself,
 * so a deployment can keep migration off in the processes that serve and run it as an explicit step.
 */
public class MigrateCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "apply pending module and application migrations, then exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = mapOf("spring.flyway.enabled" to "true")

    public companion object {
        public const val NAME: String = "migrate"
    }
}

/** Reports what the migration, which ran while the context started, applied. */
public class MigrateCommand(
    private val strategy: RainSchemaMigrationStrategy,
) : RainCommand {
    override val name: String = MigrateCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int {
        val report = checkNotNull(strategy.report) { "migrate ran without a migration; spring.flyway.enabled is off for this process" }
        report.modules.forEach { output.out.println(line(it.schema, it.result)) }
        output.out.println(line("application", report.application))
        return 0
    }

    private fun line(
        schema: String,
        result: MigrateResult,
    ): String = "migrate: $schema applied ${result.migrationsExecuted}, now at ${result.targetSchemaVersion ?: "no version"}"
}

/** rain's strategy is the only migration strategy, so no other bean can migrate the application differently. */
public class SingleMigrationStrategyCheck(
    private val strategies: List<FlywayMigrationStrategy>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        if (strategies.size == 1 && strategies.single() is RainSchemaMigrationStrategy) {
            emptyList()
        } else {
            listOf(
                ConfigurationProblem(
                    "spring.flyway",
                    ProblemCode.CONTRADICTS,
                    "rain migrates through RainSchemaMigrationStrategy alone; found ${strategies.joinToString(
                        ", ",
                    ) { it.javaClass.name }.ifEmpty { "none" }}",
                ),
            )
        }
}
