package com.gd.rain.boot.autoconfigure

import com.gd.rain.boot.command.CommandHandlersCheck
import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.boot.command.CommandRunner
import com.gd.rain.boot.command.ConfigCheckCommand
import com.gd.rain.boot.command.ConfigCheckCommandDeclaration
import com.gd.rain.boot.command.RainCommand
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.config.RainBeanTimeValidator
import com.gd.rain.boot.runtime.CommandDeclarations
import com.gd.rain.boot.runtime.ConditionalOnRainCommand
import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.boot.runtime.RuntimeSelection
import com.gd.rain.boot.runtime.StageResolution
import com.gd.rain.boot.seed.SeedCommand
import com.gd.rain.boot.seed.SeedCommandDeclaration
import com.gd.rain.boot.seed.Seeder
import com.gd.rain.boot.seed.SeedersCheck
import com.gd.rain.core.config.ConfigurationProblemsException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

@AutoConfiguration
public class RainRuntimeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun clock(): Clock = Clock.systemUTC()

    @Bean
    public fun deploymentStage(environment: Environment): DeploymentStage =
        when (val resolution = DeploymentStage.resolve(environment)) {
            is StageResolution.Resolved -> resolution.stage
            is StageResolution.Invalid -> throw ConfigurationProblemsException(listOf(resolution.problem))
        }

    @Bean
    public fun runtimeSelection(environment: Environment): RuntimeSelection {
        val selection = RuntimeSelection.resolve(environment, CommandDeclarations.load(javaClass.classLoader))
        RuntimeSelection.activeRoles(selection)
        return selection
    }

    @Bean
    public fun rainBeanTimeValidator(checks: ObjectProvider<ConfigurationCheck>): RainBeanTimeValidator = RainBeanTimeValidator(checks)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRainCommand
    public class CommandMode {
        @Bean
        @ConditionalOnMissingBean
        public fun commandOutput(): CommandOutput = CommandOutput.SYSTEM

        @Bean
        public fun commandRunner(
            selection: RuntimeSelection,
            commands: ObjectProvider<RainCommand>,
            output: CommandOutput,
        ): CommandRunner = CommandRunner((selection as RuntimeSelection.Command).declaration, commands.orderedStream().toList(), output)

        @Bean
        public fun commandHandlersCheck(
            selection: RuntimeSelection,
            commands: ObjectProvider<RainCommand>,
        ): ConfigurationCheck = CommandHandlersCheck((selection as RuntimeSelection.Command).declaration, commands.orderedStream().toList())

        @Bean
        @ConditionalOnRainCommand(ConfigCheckCommandDeclaration.NAME)
        public fun configCheckCommand(stage: DeploymentStage): ConfigCheckCommand = ConfigCheckCommand(stage)

        @Bean
        @ConditionalOnRainCommand(SeedCommandDeclaration.NAME)
        public fun seedCommand(seeders: ObjectProvider<Seeder>): SeedCommand = SeedCommand(seeders.orderedStream().toList())
    }

    @Bean
    @ConditionalOnRainRole(RuntimeRole.SEEDER)
    public fun seedersCheck(seeders: ObjectProvider<Seeder>): ConfigurationCheck = SeedersCheck(seeders.orderedStream().toList())
}
