package com.gd.rain.boot.command

import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.boot.runtime.RuntimeRole
import org.springframework.boot.ApplicationArguments

/**
 * `config-check`: starts the application with no role and no web server, which validates the whole configuration and
 * runs the bean-time checks of such a process, and exits 0 when none refused. Checks that exist only in a role (the
 * worker's connection demand) or only in a servlet application (forwarding headers, multipart limits) do not run here;
 * a start in that role evaluates them. A refusal never reaches this command — it fails the start.
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
