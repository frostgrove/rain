package com.gd.rain.boot.config

import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.CommandDeclarations
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.boot.runtime.RuntimeSelection
import com.gd.rain.boot.runtime.StageResolution
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.event.ApplicationPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/** Everything one validation pass found. */
public data class ValidationReport(
    public val problems: List<ConfigurationProblem>,
) {
    public val fatal: List<ConfigurationProblem> get() = problems.filter { it.code.fatal }
    public val notEvaluated: List<ConfigurationProblem> get() = problems.filterNot { it.code.fatal }
}

/**
 * Validates the whole configuration before any bean exists, and reports every problem at once.
 *
 * One pass: the runtime selection and the stage, `spring.application.name`, every contributed section
 * (bound, its own problems, file-borne secrets), every cross-section rule (never run when a section it
 * reads is absent — reported as not evaluated instead), and keys under `rain.` that nothing declares.
 */
public class RainConfigurationValidator :
    ApplicationListener<ApplicationPreparedEvent>,
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 40

    override fun onApplicationEvent(event: ApplicationPreparedEvent) {
        val context = event.applicationContext
        val report =
            validate(
                context.environment,
                ConfigurationContributors.load(context.classLoader),
                CommandDeclarations.load(context.classLoader),
            )
        report.notEvaluated.forEach { log.warn("configuration rule not evaluated: {} — {}", it.path, it.message) }
        if (report.fatal.isNotEmpty()) throw ConfigurationProblemsException(report.fatal)
    }

    public companion object {
        private val log = LoggerFactory.getLogger(RainConfigurationValidator::class.java)

        public const val APPLICATION_NAME: String = "spring.application.name"

        public fun validate(
            environment: ConfigurableEnvironment,
            contributors: List<ConfigurationContributor>,
            declarations: List<CommandDeclaration>,
        ): ValidationReport {
            val found = mutableListOf<ConfigurationProblem>()
            found += CommandDeclarations.problems(declarations)

            val selection = RuntimeSelection.resolve(environment, declarations)
            if (selection is RuntimeSelection.Invalid) found += selection.problems

            val stage =
                when (val resolution = DeploymentStage.resolve(environment)) {
                    is StageResolution.Resolved -> {
                        resolution.stage
                    }

                    is StageResolution.Invalid -> {
                        found += resolution.problem
                        null
                    }
                }

            if (environment.getProperty(APPLICATION_NAME).isNullOrBlank()) {
                found +=
                    ConfigurationProblem(
                        APPLICATION_NAME,
                        ProblemCode.REQUIRED,
                        "no value is provided; logs, metrics and probes are named after it",
                    )
            }

            val specs = contributors.flatMap { it.sections }
            specs.groupBy { it.prefix }.filterValues { it.size > 1 }.keys.sorted().forEach {
                found += ConfigurationProblem(it, ProblemCode.CONTRADICTS, "the section is declared more than once")
            }

            val binder = SectionBinder(environment)
            val bound = linkedMapOf<String, Any>()
            specs.distinctBy { it.prefix }.forEach { spec ->
                when (val binding = binder.bind(spec)) {
                    SectionBinding.Absent -> {
                        if (spec.presence == Presence.REQUIRED) {
                            found +=
                                ConfigurationProblem(
                                    spec.prefix,
                                    ProblemCode.REQUIRED,
                                    "the section is required and no key under it is stated",
                                )
                        }
                    }

                    is SectionBinding.Unbindable -> {
                        found += binding.problems
                    }

                    is SectionBinding.Bound -> {
                        bound[spec.prefix] = binding.value
                        if (stage != null) {
                            found += spec.problems(binding.value, stage)
                            found += binder.fileBorneSecrets(spec.prefix, spec.type, stage)
                        }
                    }
                }
            }

            val sections = BoundSections(bound)
            contributors.flatMap { it.rules }.sortedBy { it.id }.forEach { rule ->
                val absent = rule.reads.filterNot(sections::contains).sorted()
                when {
                    absent.isNotEmpty() -> {
                        found +=
                            ConfigurationProblem(
                                rule.id,
                                ProblemCode.NOT_EVALUATED,
                                "reads ${absent.joinToString(", ")}, which this application does not bind",
                            )
                    }

                    stage == null -> {
                        found += ConfigurationProblem(rule.id, ProblemCode.NOT_EVALUATED, "the deployment stage is not known")
                    }

                    else -> {
                        val outcome = rule.evaluate(sections, stage)
                        if (outcome is RuleOutcome.Violated) found += outcome.problems
                    }
                }
            }

            val claims =
                linkedMapOf<String, kotlin.reflect.KClass<*>?>(
                    RuntimeSelection.ROLES to null,
                    RuntimeSelection.COMMAND to null,
                    DeploymentStage.PROPERTY to null,
                )
            specs.forEach { claims[it.prefix] = it.type }
            found += binder.unknownKeys(claims)

            return ValidationReport(found)
        }
    }
}

/** Runs every [ConfigurationCheck] once all singletons exist and before the application starts serving. */
public class RainBeanTimeValidator(
    private val checks: ObjectProvider<ConfigurationCheck>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val found = checks.orderedStream().toList().flatMap { it.problems() }
        found.filterNot { it.code.fatal }.forEach { log.warn("check not evaluated: {} — {}", it.path, it.message) }
        val fatal = found.filter { it.code.fatal }
        if (fatal.isNotEmpty()) throw ConfigurationProblemsException(fatal)
    }

    private companion object {
        val log = LoggerFactory.getLogger(RainBeanTimeValidator::class.java)
    }
}
