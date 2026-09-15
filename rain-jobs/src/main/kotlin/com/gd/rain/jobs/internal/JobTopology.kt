package com.gd.rain.jobs.internal

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.JobsProperties
import com.gd.rain.jobs.RecurringWork

/** One profile's scheduler: its definitions, each with its ceiling, and the thread count they sum to. */
internal data class ProfilePlan(
    val profile: JobProfile,
    val ceilings: Map<String, Int>,
) {
    val threads: Int get() = ceilings.values.sum()
}

/**
 * What a worker process runs, derived from the declared beans and `rain.jobs`: one scheduler per profile with
 * Σ ceiling threads, and one scheduler for recurring work with a thread per recurring task (each is a cluster
 * singleton, so that is exactly how many can run at once). Every inconsistency is a problem; nothing is planned
 * from an inconsistent declaration.
 */
internal object JobTopology {
    /** The recurring work rain itself runs on every worker. */
    val BUILT_IN_RECURRING: Set<String> = setOf(REAPER, RETENTION)

    const val REAPER: String = "rain.jobs.reaper"
    const val RETENTION: String = "rain.jobs.retention"

    /** Catalogue and ceilings: checked in every role, since every role enqueues against the same declarations. */
    fun catalogProblems(
        catalog: JobCatalog,
        properties: JobsProperties,
    ): List<ConfigurationProblem> {
        if (catalog.problems.isNotEmpty()) return catalog.problems
        val declared = catalog.definitions.map { it.name }.toSet()
        val found = mutableListOf<ConfigurationProblem>()
        (declared - properties.workers.keys).sorted().forEach {
            found +=
                ConfigurationProblem(
                    "${JobsProperties.PREFIX}.workers.$it",
                    ProblemCode.REQUIRED,
                    "job definition $it is declared and has no worker ceiling",
                )
        }
        (properties.workers.keys - declared).sorted().forEach {
            found +=
                ConfigurationProblem(
                    "${JobsProperties.PREFIX}.workers.$it",
                    ProblemCode.UNKNOWN_KEY,
                    "no job definition named $it is declared",
                )
        }
        properties.workers.filter { (name, ceiling) -> name in declared && ceiling < 1 }.toSortedMap().forEach { (name, ceiling) ->
            found +=
                ConfigurationProblem(
                    "${JobsProperties.PREFIX}.workers.$name",
                    ProblemCode.INVALID,
                    "is $ceiling; a definition with no worker never runs",
                )
        }
        return found
    }

    /** Handlers and recurring work: checked where they run, in the worker role. */
    fun workerProblems(
        catalog: JobCatalog,
        handlers: List<JobHandler<*>>,
        recurring: List<RecurringWork>,
        properties: JobsProperties,
    ): List<ConfigurationProblem> {
        if (catalog.problems.isNotEmpty()) return emptyList()
        val found = mutableListOf<ConfigurationProblem>()
        val byDefinition = handlers.groupBy { it.definition.name }
        byDefinition.filterValues { it.size > 1 }.toSortedMap().forEach { (name, claimants) ->
            found +=
                ConfigurationProblem(
                    JobCatalog.definitionPath(name),
                    ProblemCode.CONTRADICTS,
                    "job definition $name is answered by ${claimants.size} handlers: ${claimants.joinToString(", ") { it.javaClass.name }}",
                )
        }
        byDefinition.values
            .flatten()
            .filter {
                catalog.definition(
                    it.definition.name,
                ) != it.definition
            }.sortedBy { it.definition.name }
            .forEach {
                found +=
                    ConfigurationProblem(
                        JobCatalog.definitionPath(it.definition.name),
                        ProblemCode.INVALID,
                        "handler ${it.javaClass.name} answers for a job definition ${it.definition.name} that is not the declared one",
                    )
            }
        (catalog.definitions.map { it.name }.toSet() - byDefinition.keys).sorted().forEach {
            found += ConfigurationProblem(JobCatalog.definitionPath(it), ProblemCode.REQUIRED, "job definition $it has no handler")
        }
        val names = recurring.map { it.name }
        names.filterNot(JobDefinition.NAME::matches).sorted().forEach {
            found +=
                ConfigurationProblem(
                    recurringPath(it),
                    ProblemCode.INVALID,
                    "recurring work \"$it\" does not match ${JobDefinition.NAME.pattern}",
                )
        }
        names.groupBy { it }.filterValues { it.size > 1 }.keys.sorted().forEach {
            found += ConfigurationProblem(recurringPath(it), ProblemCode.CONTRADICTS, "recurring work $it is declared more than once")
        }
        names.filter { it in BUILT_IN_RECURRING }.sorted().forEach {
            found += ConfigurationProblem(recurringPath(it), ProblemCode.CONTRADICTS, "recurring work $it takes the name of rain's own")
        }
        names.filter { catalog.definition(it) != null }.sorted().forEach {
            found +=
                ConfigurationProblem(
                    recurringPath(it),
                    ProblemCode.CONTRADICTS,
                    "recurring work $it takes the task name of a job definition",
                )
        }
        recurring.filterNot { it.interval.isPositive }.sortedBy { it.name }.forEach {
            found +=
                ConfigurationProblem(
                    recurringPath(it.name),
                    ProblemCode.INVALID,
                    "recurring work ${it.name} has interval ${it.interval}; it has to be positive",
                )
        }
        val required = properties.requiredRecurring.toSet()
        (required - names.toSet()).sorted().forEach {
            found +=
                ConfigurationProblem(
                    "${JobsProperties.PREFIX}.required-recurring",
                    ProblemCode.CONTRADICTS,
                    "names $it, which no RecurringWork bean contributes",
                )
        }
        (names.toSet() - required - BUILT_IN_RECURRING).sorted().forEach {
            found +=
                ConfigurationProblem(
                    "${JobsProperties.PREFIX}.required-recurring",
                    ProblemCode.CONTRADICTS,
                    "does not name $it, which a RecurringWork bean contributes",
                )
        }
        return found
    }

    /** The profile plans of a consistent declaration, in profile-id order. */
    fun profilePlans(
        catalog: JobCatalog,
        properties: JobsProperties,
    ): List<ProfilePlan> =
        catalog.profiles.map { profile ->
            ProfilePlan(
                profile,
                catalog.definitionsOf(profile).associate { definition ->
                    definition.name to checkNotNull(properties.workers[definition.name]) { "definition ${definition.name} has no ceiling" }
                },
            )
        }

    private fun recurringPath(name: String): String = "jobs:recurring:$name"
}
