package com.gd.rain.boot.runtime

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

/**
 * What this process was started as: a set of roles, or one command. Exactly one of
 * `rain.runtime.roles` and `rain.runtime.command` is stated; there is no default, because a process
 * that guessed it was a worker is the failure this exists to prevent.
 *
 * [resolve] is a pure function of the environment and the declared commands, so the environment
 * post-processor, the validator and every condition reach the same answer.
 */
public sealed interface RuntimeSelection {
    public data class Roles(
        public val roles: Set<RuntimeRole>,
    ) : RuntimeSelection

    public data class Command(
        public val declaration: CommandDeclaration,
    ) : RuntimeSelection

    public data class Invalid(
        public val problems: List<ConfigurationProblem>,
    ) : RuntimeSelection

    public companion object {
        public const val ROLES: String = "rain.runtime.roles"
        public const val COMMAND: String = "rain.runtime.command"

        public fun resolve(
            environment: Environment,
            declarations: List<CommandDeclaration>,
        ): RuntimeSelection {
            val stated = Binder.get(environment).bind(ROLES, Bindable.listOf(String::class.java)).orElse(null)
            val rolesStated = stated != null || environment.containsProperty(ROLES)
            val commandStated = environment.containsProperty(COMMAND)

            return when {
                rolesStated && commandStated -> {
                    Invalid(
                        listOf(
                            ConfigurationProblem(ROLES, ProblemCode.EXCLUSIVE, "is stated together with $COMMAND; state one of them"),
                            ConfigurationProblem(COMMAND, ProblemCode.EXCLUSIVE, "is stated together with $ROLES; state one of them"),
                        ),
                    )
                }

                commandStated -> {
                    command(environment.getProperty(COMMAND).orEmpty(), declarations)
                }

                rolesStated -> {
                    roles(stated.orEmpty())
                }

                else -> {
                    Invalid(
                        listOf(
                            ConfigurationProblem(
                                "rain.runtime",
                                ProblemCode.REQUIRED,
                                "state $ROLES (one or more of ${RuntimeRole.wireNames.joinToString(", ")}) or $COMMAND " +
                                    "(one of ${declarations.joinToString(", ") { it.name }.ifEmpty { "no declared commands" }})",
                            ),
                        ),
                    )
                }
            }
        }

        /** The roles whose contributions are active, or a refusal naming why the selection is invalid. */
        public fun activeRoles(selection: RuntimeSelection): Set<RuntimeRole> =
            when (selection) {
                is Roles -> selection.roles
                is Command -> selection.declaration.roles
                is Invalid -> throw ConfigurationProblemsException(selection.problems)
            }

        private fun command(
            name: String,
            declarations: List<CommandDeclaration>,
        ): RuntimeSelection {
            val matching = declarations.filter { it.name == name }
            return when (matching.size) {
                1 -> {
                    Command(matching.single())
                }

                0 -> {
                    Invalid(
                        listOf(
                            ConfigurationProblem(
                                COMMAND,
                                ProblemCode.INVALID,
                                "is \"$name\"; the declared commands are ${declarations.joinToString(", ") { it.name }.ifEmpty { "none" }}",
                            ),
                        ),
                    )
                }

                else -> {
                    Invalid(listOf(ConfigurationProblem(COMMAND, ProblemCode.CONTRADICTS, "\"$name\" is declared ${matching.size} times")))
                }
            }
        }

        private fun roles(stated: List<String>): RuntimeSelection {
            val problems = mutableListOf<ConfigurationProblem>()
            if (stated.isEmpty()) {
                problems +=
                    ConfigurationProblem(
                        ROLES,
                        ProblemCode.INVALID,
                        "names no role; name one or more of ${RuntimeRole.wireNames.joinToString(", ")}",
                    )
            }
            stated.filter { RuntimeRole.fromWire(it) == null }.distinct().forEach {
                problems +=
                    ConfigurationProblem(
                        ROLES,
                        ProblemCode.INVALID,
                        "names \"$it\", which is not one of ${RuntimeRole.wireNames.joinToString(", ")}",
                    )
            }
            stated.groupBy { it }.filterValues { it.size > 1 }.keys.sorted().forEach {
                problems += ConfigurationProblem(ROLES, ProblemCode.INVALID, "names \"$it\" more than once")
            }
            if (problems.isNotEmpty()) return Invalid(problems)
            return Roles(stated.mapNotNull(RuntimeRole::fromWire).toSet())
        }
    }
}
