package com.gd.rain.boot.runtime

import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Matches when any of [anyOf] is an active role — stated in `rain.runtime.roles`, or declared by the
 * command the process runs as. An invalid selection is not "no match": it fails the context with
 * the problems that make it invalid.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(OnRainRoleCondition::class)
public annotation class ConditionalOnRainRole(
    public vararg val anyOf: RuntimeRole,
)

/** Matches when the process runs as a command: any command when [names] is empty, otherwise one of them. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(OnRainCommandCondition::class)
public annotation class ConditionalOnRainCommand(
    public vararg val names: String,
)

internal class OnRainRoleCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val attributes = checkNotNull(metadata.getAnnotationAttributes(ConditionalOnRainRole::class.java.name))

        @Suppress("UNCHECKED_CAST")
        val wanted = (attributes["anyOf"] as Array<RuntimeRole>).toSet()
        check(wanted.isNotEmpty()) { "@ConditionalOnRainRole names at least one role" }

        val active =
            RuntimeSelection.activeRoles(
                RuntimeSelection.resolve(context.environment, CommandDeclarations.load(context.classLoader)),
            )
        val names = wanted.joinToString(", ") { it.wire }
        return if (active.any(wanted::contains)) {
            ConditionOutcome.match("a rain role among [$names] is active")
        } else {
            ConditionOutcome.noMatch("no rain role among [$names] is active; active: [${active.joinToString(", ") { it.wire }}]")
        }
    }
}

internal class OnRainCommandCondition : SpringBootCondition() {
    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val attributes = checkNotNull(metadata.getAnnotationAttributes(ConditionalOnRainCommand::class.java.name))

        @Suppress("UNCHECKED_CAST")
        val names = (attributes["names"] as Array<String>).toSet()

        return when (val selection = RuntimeSelection.resolve(context.environment, CommandDeclarations.load(context.classLoader))) {
            is RuntimeSelection.Invalid -> {
                RuntimeSelection.activeRoles(selection)
                error("unreachable: an invalid selection is refused above")
            }

            is RuntimeSelection.Roles -> {
                ConditionOutcome.noMatch("the process runs with roles, not a command")
            }

            is RuntimeSelection.Command -> {
                val name = selection.declaration.name
                if (names.isEmpty() || name in names) {
                    ConditionOutcome.match("the process runs command \"$name\"")
                } else {
                    ConditionOutcome.noMatch("the process runs command \"$name\", not one of $names")
                }
            }
        }
    }
}
