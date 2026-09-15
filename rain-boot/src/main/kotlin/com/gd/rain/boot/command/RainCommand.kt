package com.gd.rain.boot.command

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.RuntimeSelection
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.io.PrintStream

/**
 * The work behind a declared command. Exactly one bean answers to each declared name while that
 * command runs; its return value is the process exit code.
 */
public interface RainCommand {
    public val name: String

    public fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int
}

/** Where a command writes: its result on [out], progress and counts on [err]. */
public class CommandOutput(
    public val out: PrintStream,
    public val err: PrintStream,
) {
    public companion object {
        public val SYSTEM: CommandOutput = CommandOutput(System.out, System.err)
    }
}

/** Runs the one command the process was started as, and hands its exit code to Spring's exit. */
public class CommandRunner(
    private val declaration: CommandDeclaration,
    private val commands: List<RainCommand>,
    private val output: CommandOutput,
) : ApplicationRunner,
    ExitCodeGenerator {
    @Volatile
    private var exitCode: Int = 0

    override fun run(args: ApplicationArguments) {
        val handler = commands.single { it.name == declaration.name }
        val code = handler.run(args, output)
        require(code in 0..EXIT_CODE_MAX) { "command \"${declaration.name}\" returned exit code $code, outside 0..$EXIT_CODE_MAX" }
        exitCode = code
        output.out.flush()
        output.err.flush()
    }

    override fun getExitCode(): Int = exitCode

    private companion object {
        const val EXIT_CODE_MAX = 125
    }
}

/** The command a process runs as is answered by exactly one [RainCommand] bean. */
public class CommandHandlersCheck(
    private val declaration: CommandDeclaration,
    private val commands: List<RainCommand>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val handlers = commands.filter { it.name == declaration.name }
        return when (handlers.size) {
            1 -> {
                emptyList()
            }

            0 -> {
                listOf(
                    ConfigurationProblem(
                        RuntimeSelection.COMMAND,
                        ProblemCode.INVALID,
                        "command \"${declaration.name}\" is declared but no RainCommand bean answers to it",
                    ),
                )
            }

            else -> {
                listOf(
                    ConfigurationProblem(
                        RuntimeSelection.COMMAND,
                        ProblemCode.CONTRADICTS,
                        "command \"${declaration.name}\" is answered by ${handlers.joinToString(", ") { it.javaClass.name }}",
                    ),
                )
            }
        }
    }
}
