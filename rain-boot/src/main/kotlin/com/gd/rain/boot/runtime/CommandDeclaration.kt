package com.gd.rain.boot.runtime

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.core.io.support.SpringFactoriesLoader

/**
 * A one-shot command a process can be started as, instead of with roles.
 *
 * Declarations are read from `META-INF/spring.factories` under this interface's name, before the
 * context exists: the command decides whether there is a web server at all, and which roles'
 * contributions are active while it runs.
 */
public interface CommandDeclaration {
    /** Lower-case kebab name; what `rain.runtime.command` states. */
    public val name: String

    public val description: String

    /** The roles whose contributions the command needs, e.g. `seed` needs [RuntimeRole.SEEDER]. */
    public val roles: Set<RuntimeRole>

    /** Properties the command sets with the highest precedence, e.g. `migrate` enables Flyway. */
    public val properties: Map<String, String>
}

public object CommandDeclarations {
    private val NAME = Regex("^[a-z][a-z0-9-]{0,63}$")

    public fun load(classLoader: ClassLoader?): List<CommandDeclaration> =
        SpringFactoriesLoader
            .forDefaultResourceLocation(classLoader)
            .load(CommandDeclaration::class.java)
            .sortedBy(CommandDeclaration::name)

    /** A malformed name, or one name declared twice, is a problem rather than a first-wins choice. */
    public fun problems(declarations: List<CommandDeclaration>): List<ConfigurationProblem> {
        val malformed =
            declarations.filterNot { NAME.matches(it.name) }.map {
                ConfigurationProblem(
                    RuntimeSelection.COMMAND,
                    ProblemCode.INVALID,
                    "${it.javaClass.name} declares command \"${it.name}\", which does not match ${NAME.pattern}",
                )
            }
        val duplicates =
            declarations
                .groupBy(CommandDeclaration::name)
                .filterValues { it.size > 1 }
                .toSortedMap()
                .map { (name, owners) ->
                    ConfigurationProblem(
                        RuntimeSelection.COMMAND,
                        ProblemCode.CONTRADICTS,
                        "command \"$name\" is declared by ${owners.joinToString(", ") { it.javaClass.name }}",
                    )
                }
        return malformed + duplicates
    }
}
