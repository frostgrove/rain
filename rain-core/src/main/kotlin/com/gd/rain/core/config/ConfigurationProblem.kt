package com.gd.rain.core.config

/** Why a configuration was refused, or why a rule about it could not be evaluated. */
public enum class ProblemCode(
    /** Whether the problem stops the application from starting. */
    public val fatal: Boolean,
) {
    /** A value the deployment has to state and did not. */
    REQUIRED(true),

    /** A value that is stated but not acceptable. */
    INVALID(true),

    /** Two stated values that cannot both hold. */
    CONTRADICTS(true),

    /** A key under a claimed prefix that nothing declares. */
    UNKNOWN_KEY(true),

    /** Two settings of which exactly one may be stated. */
    EXCLUSIVE(true),

    /**
     * A rule that reads a section this application does not have. Reported so the gap is visible, never
     * silently skipped, and not fatal: an application that leaves a module out is not misconfigured.
     */
    NOT_EVALUATED(false),
}

/**
 * One problem with a configuration.
 *
 * [path] is the property path a person edits (`rain.web.body-limit`), [source] is where the offending
 * value came from when the environment knows it, and [message] never quotes a secret value.
 */
public data class ConfigurationProblem(
    public val path: String,
    public val code: ProblemCode,
    public val message: String,
    public val source: String? = null,
)

/** Every fatal problem found in one pass, reported together so one start-up names all of them. */
public class ConfigurationProblemsException(
    public val problems: List<ConfigurationProblem>,
) : RuntimeException(render(problems)) {
    init {
        require(problems.isNotEmpty()) { "a refusal names at least one problem" }
    }

    public companion object {
        public fun render(problems: List<ConfigurationProblem>): String {
            val noun = if (problems.size == 1) "problem" else "problems"
            return problems.joinToString(
                prefix = "the configuration has ${problems.size} $noun:\n  - ",
                separator = "\n  - ",
            ) { problem ->
                buildString {
                    append(problem.path)
                        .append(" [")
                        .append(problem.code.name.lowercase())
                        .append("]: ")
                        .append(problem.message)
                    problem.source?.let { append(" (from ").append(it).append(')') }
                }
            }
        }
    }
}

/** Collects problems in the order they are found. */
public class ProblemCollector {
    private val found = mutableListOf<ConfigurationProblem>()

    public val problems: List<ConfigurationProblem> get() = found.toList()

    public fun add(problem: ConfigurationProblem) {
        found += problem
    }

    public fun addAll(problems: Collection<ConfigurationProblem>) {
        found += problems
    }

    /** Records a problem at [path] unless [holds]. */
    public fun expect(
        holds: Boolean,
        path: String,
        code: ProblemCode = ProblemCode.INVALID,
        message: () -> String,
    ) {
        if (!holds) found += ConfigurationProblem(path, code, message())
    }
}

public inline fun problems(block: ProblemCollector.() -> Unit): List<ConfigurationProblem> = ProblemCollector().apply(block).problems
