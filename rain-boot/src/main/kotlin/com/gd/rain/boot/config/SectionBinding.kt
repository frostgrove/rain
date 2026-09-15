package com.gd.rain.boot.config

import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.ConfigurationPropertyState
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

internal sealed interface SectionBinding {
    data object Absent : SectionBinding

    data class Bound(
        public val value: Any,
    ) : SectionBinding

    data class Unbindable(
        public val problems: List<ConfigurationProblem>,
    ) : SectionBinding
}

/** Binding, and the three things Spring's binder does not report: missing leaves, unknown keys and file-borne secrets. */
internal class SectionBinder(
    private val environment: ConfigurableEnvironment,
) {
    private val binder = Binder.get(environment)

    fun bind(spec: SectionSpec<*>): SectionBinding {
        if (!present(ConfigurationPropertyName.of(spec.prefix))) return SectionBinding.Absent
        return try {
            SectionBinding.Bound(binder.bindOrCreate(spec.prefix, Bindable.of(spec.type.java)))
        } catch (failure: BindException) {
            val missing = missingLeaves(spec.prefix, spec.type)
            if (missing.isNotEmpty()) {
                SectionBinding.Unbindable(missing.map { ConfigurationProblem(it, ProblemCode.REQUIRED, "no value is provided") })
            } else {
                val property = failure.property
                val said =
                    generateSequence(failure as Throwable, Throwable::cause)
                        .mapNotNull { it.message?.trim()?.ifEmpty { null } }
                        .distinct()
                        .joinToString("; ")
                SectionBinding.Unbindable(
                    listOf(
                        ConfigurationProblem(
                            path = property?.name?.toString() ?: spec.prefix,
                            code = ProblemCode.INVALID,
                            message = said,
                            source = property?.origin?.toString(),
                        ),
                    ),
                )
            }
        }
    }

    /** Leaves declared with no default and not nullable that the environment does not state. */
    fun missingLeaves(
        prefix: String,
        type: KClass<*>,
    ): List<String> =
        type.primaryConstructor
            ?.parameters
            .orEmpty()
            .filter { it.kind == KParameter.Kind.VALUE && !it.isOptional && !it.type.isMarkedNullable }
            .flatMap { parameter ->
                val path = "$prefix.${dashed(parameter.name.orEmpty())}"
                val erased = parameter.type.jvmErasure
                if (isSection(parameter, erased)) {
                    missingLeaves(path, erased)
                } else if (present(ConfigurationPropertyName.of(path))) {
                    emptyList()
                } else {
                    listOf(path)
                }
            }

    /** Leaves annotated [RequiredFromEnvironment] for [stage] whose value was stated anywhere but in an environment variable. */
    fun fileBorneSecrets(
        prefix: String,
        type: KClass<*>,
        stage: DeploymentStage,
    ): List<ConfigurationProblem> =
        type.primaryConstructor
            ?.parameters
            .orEmpty()
            .filter { it.kind == KParameter.Kind.VALUE }
            .flatMap { parameter ->
                val path = "$prefix.${dashed(parameter.name.orEmpty())}"
                val erased = parameter.type.jvmErasure
                val required = parameter.findAnnotation<RequiredFromEnvironment>()
                when {
                    isSection(parameter, erased) -> {
                        fileBorneSecrets(path, erased, stage)
                    }

                    required != null && stage in required.stages -> {
                        val name = ConfigurationPropertyName.of(path)
                        val holder = ConfigurationPropertySources.get(environment).firstOrNull { it.getConfigurationProperty(name) != null }
                        if (holder == null || holder.underlyingSource is SystemEnvironmentPropertySource) {
                            emptyList()
                        } else {
                            listOf(
                                ConfigurationProblem(
                                    path,
                                    ProblemCode.INVALID,
                                    "has to come from an environment variable in the ${stage.wire} stage",
                                    source = holder.getConfigurationProperty(name)?.origin?.toString(),
                                ),
                            )
                        }
                    }

                    else -> {
                        emptyList()
                    }
                }
            }

    /**
     * Keys under `rain.` that no claim accounts for. Only enumerable, non-environment sources are read:
     * environment variable names do not map back to one property path, so they are not judged.
     */
    fun unknownKeys(claims: Map<String, KClass<*>?>): List<ConfigurationProblem> {
        val rain = ConfigurationPropertyName.of("rain")
        val claimed = claims.map { (prefix, type) -> ConfigurationPropertyName.of(prefix) to type }
        val found = sortedMapOf<String, String?>()
        ConfigurationPropertySources.get(environment).forEach { source ->
            if (source !is IterableConfigurationPropertySource) return@forEach
            if (source.underlyingSource is SystemEnvironmentPropertySource) return@forEach
            source.stream().filter { rain.isAncestorOf(it) }.forEach { name ->
                if (claimed.none { (prefix, type) -> claims(prefix, type, name) }) {
                    found.putIfAbsent(name.toString(), source.getConfigurationProperty(name)?.origin?.toString())
                }
            }
        }
        return found.map { (name, origin) ->
            ConfigurationProblem(name, ProblemCode.UNKNOWN_KEY, "no rain section declares this key", origin)
        }
    }

    private fun claims(
        prefix: ConfigurationPropertyName,
        type: KClass<*>?,
        name: ConfigurationPropertyName,
    ): Boolean {
        if (prefix == name) return true
        if (!prefix.isAncestorOf(name)) return false
        if (type == null) return false
        val rest = (prefix.numberOfElements until name.numberOfElements).map { name.getElement(it, ConfigurationPropertyName.Form.UNIFORM) }
        return claimsMembers(type, rest)
    }

    private fun claimsMembers(
        type: KClass<*>,
        rest: List<String>,
    ): Boolean {
        if (rest.isEmpty()) return true
        val parameter =
            type.primaryConstructor
                ?.parameters
                .orEmpty()
                .firstOrNull { uniform(it.name.orEmpty()) == rest.first() } ?: return false
        return claimsValue(parameter, parameter.type, rest.drop(1))
    }

    private fun claimsValue(
        parameter: KParameter?,
        type: KType,
        rest: List<String>,
    ): Boolean {
        if (rest.isEmpty()) return true
        val erased = type.jvmErasure
        return when {
            erased.isSubclassOf(Map::class) -> {
                val value = type.arguments.getOrNull(1)?.type ?: return false
                claimsValue(null, value, rest.drop(1))
            }

            erased.isSubclassOf(Collection::class) -> {
                val element = type.arguments.getOrNull(0)?.type ?: return false
                rest.first().all(Char::isDigit) && claimsValue(null, element, rest.drop(1))
            }

            isSection(parameter, erased) -> {
                claimsMembers(erased, rest)
            }

            else -> {
                false
            }
        }
    }

    private fun present(name: ConfigurationPropertyName): Boolean =
        ConfigurationPropertySources.get(environment).any { source ->
            source.getConfigurationProperty(name) != null || source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT
        }

    private fun isSection(
        parameter: KParameter?,
        erased: KClass<*>,
    ): Boolean =
        erased.isSubclassOf(ConfigurationSection::class) ||
            parameter?.findAnnotation<NestedConfigurationProperty>() != null

    internal companion object {
        fun dashed(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

        fun uniform(name: String): String = dashed(name).replace("-", "")
    }
}
