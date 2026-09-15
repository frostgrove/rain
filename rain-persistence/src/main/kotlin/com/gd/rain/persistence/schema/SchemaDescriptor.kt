package com.gd.rain.persistence.schema

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import java.util.Properties

/**
 * A rain module's claim on its own database schema.
 *
 * Every module that owns tables ships `META-INF/rain/schemas/<module>.properties` naming the module,
 * its schema `rain_<module>` and its migrations at `classpath:db/rain/<module>`. The three are one
 * convention, checked rather than configured, so a module cannot migrate into another's schema.
 */
public data class SchemaDescriptor(
    public val module: String,
    public val schema: String,
    public val location: String,
) {
    public companion object {
        public const val RESOURCE_PATTERN: String = "classpath*:META-INF/rain/schemas/*.properties"

        private val MODULE = Regex("^[a-z][a-z0-9-]{0,40}$")

        public fun schemaOf(module: String): String = "rain_" + module.replace('-', '_')

        public fun locationOf(module: String): String = "classpath:db/rain/$module"

        public fun load(resolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()): SchemaDescriptors {
            val problems = mutableListOf<ConfigurationProblem>()
            val descriptors =
                resolver.getResources(RESOURCE_PATTERN).mapNotNull { resource ->
                    val properties = Properties().apply { resource.inputStream.use(::load) }
                    val module = properties.getProperty("module")
                    val schema = properties.getProperty("schema")
                    val location = properties.getProperty("location")
                    val where = resource.description
                    when {
                        module == null || !MODULE.matches(module) -> {
                            problems +=
                                ConfigurationProblem(where, ProblemCode.INVALID, "module \"$module\" does not match ${MODULE.pattern}")
                            null
                        }

                        schema != schemaOf(module) -> {
                            problems +=
                                ConfigurationProblem(
                                    where,
                                    ProblemCode.INVALID,
                                    "module \"$module\" owns schema ${schemaOf(module)}, not \"$schema\"",
                                )
                            null
                        }

                        location != locationOf(module) -> {
                            problems +=
                                ConfigurationProblem(
                                    where,
                                    ProblemCode.INVALID,
                                    "module \"$module\" migrates from ${locationOf(module)}, not \"$location\"",
                                )
                            null
                        }

                        else -> {
                            SchemaDescriptor(module, schema, location)
                        }
                    }
                }
            descriptors.groupBy { it.module }.filterValues { it.size > 1 }.keys.sorted().forEach {
                problems +=
                    ConfigurationProblem(
                        "META-INF/rain/schemas/$it.properties",
                        ProblemCode.CONTRADICTS,
                        "module \"$it\" is described more than once",
                    )
            }
            return SchemaDescriptors(descriptors.distinctBy { it.module }.sortedBy { it.module }, problems)
        }
    }
}

public data class SchemaDescriptors(
    public val descriptors: List<SchemaDescriptor>,
    public val problems: List<ConfigurationProblem>,
)
