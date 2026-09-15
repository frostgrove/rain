package com.gd.rain.access.internal.usecase

import com.gd.rain.access.MountedSubject
import com.gd.rain.access.SubjectDirectory
import com.gd.rain.access.SubjectRegistrar
import com.gd.rain.access.SubjectType
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode

/** One served kind of subject: how it is mounted, who answers for it, and who registers it when anyone does. */
public data class ServedSubject(
    public val mounted: MountedSubject,
    public val directory: SubjectDirectory,
    public val registrar: SubjectRegistrar?,
) {
    public val type: SubjectType get() = mounted.type
}

/**
 * The subject types this application serves. A type is served when exactly one [MountedSubject] and exactly one
 * [SubjectDirectory] name it; anything else is a start-up refusal ([problems]) and serves nothing.
 */
public class SubjectRegistry(
    private val mounted: List<MountedSubject>,
    private val directories: List<SubjectDirectory>,
    private val registrars: List<SubjectRegistrar>,
) {
    private val served: Map<SubjectType, ServedSubject> =
        mounted
            .groupBy(MountedSubject::type)
            .filterValues { it.size == 1 }
            .mapNotNull { (type, mounts) ->
                val directory = directories.filter { it.subjectType == type }.singleOrNull() ?: return@mapNotNull null
                val registrar = registrars.filter { it.subjectType == type }.singleOrNull()
                type to ServedSubject(mounts.single(), directory, registrar)
            }.toMap()

    public fun served(type: SubjectType): ServedSubject? = served[type]

    public fun types(): Set<SubjectType> = served.keys

    /** The served subject named by request text, or `400 unknown_subject_type` naming [field]. */
    public fun resolve(
        text: String,
        field: String,
    ): ServedSubject {
        val type = if (SubjectType.isWellFormed(text)) SubjectType(text) else null
        return type?.let(served::get) ?: throw AccessFaults.unknownSubjectType(text, field)
    }

    public fun problems(): List<ConfigurationProblem> {
        val found = mutableListOf<ConfigurationProblem>()
        if (mounted.isEmpty()) {
            found +=
                ConfigurationProblem(
                    PATH,
                    ProblemCode.REQUIRED,
                    "no MountedSubject bean is declared; rain-access serves no kind of subject",
                )
        }
        mounted.groupBy(MountedSubject::type).filterValues { it.size > 1 }.keys.sortedBy { it.name }.forEach {
            found += ConfigurationProblem("$PATH:$it", ProblemCode.CONTRADICTS, "subject type $it is mounted more than once")
        }
        directories.groupBy(SubjectDirectory::subjectType).toSortedMap(compareBy { it.name }).forEach { (type, owners) ->
            if (owners.size > 1) {
                found +=
                    ConfigurationProblem(
                        "$PATH:$type",
                        ProblemCode.CONTRADICTS,
                        "subject type $type has ${owners.size} directories: ${owners.joinToString(", ") { it.javaClass.name }}",
                    )
            }
            if (mounted.none { it.type == type }) {
                found +=
                    ConfigurationProblem(
                        "$PATH:$type",
                        ProblemCode.CONTRADICTS,
                        "a directory serves subject type $type, which nothing mounts",
                    )
            }
        }
        mounted.map(MountedSubject::type).distinct().sortedBy { it.name }.forEach { type ->
            if (directories.none { it.subjectType == type }) {
                found +=
                    ConfigurationProblem(
                        "$PATH:$type",
                        ProblemCode.REQUIRED,
                        "subject type $type is mounted and no SubjectDirectory serves it",
                    )
            }
        }
        registrars.groupBy(SubjectRegistrar::subjectType).toSortedMap(compareBy { it.name }).forEach { (type, owners) ->
            if (owners.size > 1) {
                found += ConfigurationProblem("$PATH:$type", ProblemCode.CONTRADICTS, "subject type $type has ${owners.size} registrars")
            }
            if (mounted.none { it.type == type }) {
                found +=
                    ConfigurationProblem(
                        "$PATH:$type",
                        ProblemCode.CONTRADICTS,
                        "a registrar registers subject type $type, which nothing mounts",
                    )
            }
        }
        return found
    }

    private companion object {
        const val PATH = "access.subject"
    }
}

public class SubjectRegistryCheck(
    private val registry: SubjectRegistry,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> = registry.problems()
}
