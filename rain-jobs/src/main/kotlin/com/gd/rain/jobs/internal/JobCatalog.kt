package com.gd.rain.jobs.internal

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobProfile

/**
 * The declared profiles and definitions. A name declared twice, or a definition naming an undeclared profile, is
 * a problem the bean-time check reports; a catalogue with problems answers no lookup, so nothing ever runs against
 * a first-wins choice.
 */
public class JobCatalog internal constructor(
    profiles: List<JobProfile>,
    definitions: List<JobDefinition<*>>,
) {
    internal val problems: List<ConfigurationProblem>
    private val profilesById: Map<String, JobProfile>
    private val definitionsByName: Map<String, JobDefinition<*>>

    init {
        val found = mutableListOf<ConfigurationProblem>()
        profiles.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted().forEach {
            found += ConfigurationProblem(profilePath(it), ProblemCode.CONTRADICTS, "job profile $it is declared more than once")
        }
        definitions.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted().forEach {
            found += ConfigurationProblem(definitionPath(it), ProblemCode.CONTRADICTS, "job definition $it is declared more than once")
        }
        val declaredProfiles = profiles.map { it.id }.toSet()
        definitions.filterNot { it.profile in declaredProfiles }.distinctBy { it.name }.sortedBy { it.name }.forEach {
            found +=
                ConfigurationProblem(
                    definitionPath(it.name),
                    ProblemCode.INVALID,
                    "job definition ${it.name} names profile ${it.profile}, which is not declared",
                )
        }
        problems = found
        profilesById =
            profiles
                .groupBy { it.id }
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
                .toSortedMap()
        definitionsByName =
            definitions
                .groupBy { it.name }
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
                .toSortedMap()
    }

    internal val profiles: List<JobProfile> get() = valid().profilesById.values.toList()

    internal val definitions: List<JobDefinition<*>> get() = valid().definitionsByName.values.toList()

    internal fun definition(name: String): JobDefinition<*>? = valid().definitionsByName[name]

    internal fun profile(id: String): JobProfile? = valid().profilesById[id]

    /** The profile of a definition this catalogue declared; the catalogue guarantees it exists. */
    internal fun profileOf(definition: JobDefinition<*>): JobProfile =
        checkNotNull(profile(definition.profile)) { "definition ${definition.name} names an undeclared profile" }

    internal fun definitionsOf(profile: JobProfile): List<JobDefinition<*>> = definitions.filter { it.profile == profile.id }

    private fun valid(): JobCatalog {
        if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
        return this
    }

    internal companion object {
        fun profilePath(id: String): String = "jobs:profile:$id"

        fun definitionPath(name: String): String = "jobs:definition:$name"
    }
}
