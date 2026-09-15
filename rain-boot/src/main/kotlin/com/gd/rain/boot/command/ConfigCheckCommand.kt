package com.gd.rain.boot.command

import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.boot.runtime.RuntimeRole
import org.springframework.boot.ApplicationArguments

/**
 * `config-check`: starts the application with no role, which runs every configuration and bean-time
 * check, and exits 0 when none refused. A refusal never reaches this command — it fails the start.
 */
public class ConfigCheckCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "validate the configuration and exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = emptyMap()

    public companion object {
        public const val NAME: String = "config-check"
    }
}

public class ConfigCheckCommand(
    private val stage: DeploymentStage,
) : RainCommand {
    override val name: String = ConfigCheckCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int {
        output.out.println("configuration: ok (stage=${stage.wire})")
        return 0
    }
}
