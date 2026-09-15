package com.gd.rain.boot.config

import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.core.config.ConfigurationProblem
import org.springframework.core.io.support.SpringFactoriesLoader
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * Marker for a configuration type whose members are part of the same section. The validator descends
 * into a member only when its type is a section (or the member is annotated
 * `@NestedConfigurationProperty`); every other member is a value.
 */
public interface ConfigurationSection

/** The leaf must come from an environment variable in the named stages, never from a file. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class RequiredFromEnvironment(
    public vararg val stages: DeploymentStage,
)

public enum class Presence {
    /** The deployment has to state at least one key under the prefix. */
    REQUIRED,

    /** The section may be absent; rules reading it are then reported as not evaluated. */
    OPTIONAL,
}

/** One `@ConfigurationProperties` section: where it binds, what it binds to, and what is wrong with a bound value. */
public class SectionSpec<T : Any>(
    public val prefix: String,
    public val type: KClass<T>,
    public val presence: Presence,
    private val validate: (T, DeploymentStage) -> List<ConfigurationProblem> = { _, _ -> emptyList() },
) {
    init {
        require(PREFIX.matches(prefix)) { "section prefix \"$prefix\" does not match ${PREFIX.pattern}" }
    }

    public fun problems(
        value: Any,
        stage: DeploymentStage,
    ): List<ConfigurationProblem> = validate(type.cast(value), stage)

    private companion object {
        val PREFIX = Regex("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*$")
    }
}

public sealed interface RuleOutcome {
    public data object Satisfied : RuleOutcome

    public data class Violated(
        public val problems: List<ConfigurationProblem>,
    ) : RuleOutcome
}

/** A rule that reads more than one section. It runs only when every section it [reads] is bound. */
public interface CrossSectionRule {
    public val id: String
    public val reads: Set<String>

    public fun evaluate(
        sections: BoundSections,
        stage: DeploymentStage,
    ): RuleOutcome
}

public class BoundSections(
    private val values: Map<String, Any>,
) {
    public operator fun contains(prefix: String): Boolean = values.containsKey(prefix)

    public fun <T : Any> get(
        prefix: String,
        type: KClass<T>,
    ): T = type.cast(requireNotNull(values[prefix]) { "section $prefix is not bound" })
}

/**
 * What a module or application adds to configuration validation. Read from `META-INF/spring.factories`
 * before any bean exists, so every problem in every module is reported by one start-up.
 */
public interface ConfigurationContributor {
    public val sections: List<SectionSpec<*>>

    public val rules: List<CrossSectionRule> get() = emptyList()
}

public object ConfigurationContributors {
    public fun load(classLoader: ClassLoader?): List<ConfigurationContributor> =
        SpringFactoriesLoader
            .forDefaultResourceLocation(classLoader)
            .load(ConfigurationContributor::class.java)
            .sortedBy { it.javaClass.name }
}

/** A problem that can only be found once beans exist, reported through the same model as configuration problems. */
public fun interface ConfigurationCheck {
    public fun problems(): List<ConfigurationProblem>
}
