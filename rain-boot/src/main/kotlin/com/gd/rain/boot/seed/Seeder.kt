package com.gd.rain.boot.seed

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.boot.command.RainCommand
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.boot.ApplicationArguments

/**
 * One write the `seed` command performs. A seeder must be safe to run again: seeding is repeated on
 * every deployment that runs the command, and a second run changes nothing the first did.
 */
public interface Seeder {
    /** Unique across the application; named in progress output and in a refusal. */
    public val name: String

    /** Seeders run by ascending order, then by name. */
    public val order: Int

    public fun seed()
}

public class SeedCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "run every seeder once, in order"
    override val roles: Set<RuntimeRole> = setOf(RuntimeRole.SEEDER)
    override val properties: Map<String, String> = emptyMap()

    public companion object {
        public const val NAME: String = "seed"
    }
}

public class SeedCommand(
    private val seeders: List<Seeder>,
) : RainCommand {
    override val name: String = SeedCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int {
        val ordered = seeders.sortedWith(ORDER)
        output.err.println("seeding: registered=${ordered.size}")
        ordered.forEach { seeder ->
            seeder.seed()
            output.err.println("seeded: ${seeder.name}")
        }
        output.err.println("seeding complete: ran=${ordered.size}")
        return 0
    }

    public companion object {
        public val ORDER: Comparator<Seeder> = compareBy<Seeder> { it.order }.thenBy { it.name }
    }
}

/** Seeder names are well formed and unique. */
public class SeedersCheck(
    private val seeders: List<Seeder>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val malformed =
            seeders.filterNot { NAME.matches(it.name) }.map {
                ConfigurationProblem(
                    "seeder:${it.name}",
                    ProblemCode.INVALID,
                    "${it.javaClass.name} is named \"${it.name}\", which does not match ${NAME.pattern}",
                )
            }
        val duplicates =
            seeders.groupBy { it.name }.filterValues { it.size > 1 }.toSortedMap().map { (name, owners) ->
                ConfigurationProblem(
                    "seeder:$name",
                    ProblemCode.CONTRADICTS,
                    "is the name of ${owners.joinToString(", ") { it.javaClass.name }}",
                )
            }
        return malformed + duplicates
    }

    private companion object {
        val NAME = Regex("^[a-z][a-z0-9.-]{0,127}$")
    }
}
