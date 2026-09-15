/*
 * The root of the rain build: the module graph every rain module keeps.
 *
 * A module may depend (in its main scope: api, implementation, compileOnly, runtimeOnly) only on the modules declared
 * for it below, lower in the graph; test scopes and `rain-dependencies` are not part of the graph. A new module is
 * declared here in the same change that adds it, and a sample may use any rain module but `rain-test`.
 */
plugins {
    base
    // The JVM resolution rules, so a Maven BOM resolves as a platform in `toolParity`.
    `jvm-ecosystem`
}

evaluationDependsOnChildren()

val mainScopes = listOf("api", "implementation", "compileOnly", "runtimeOnly")

val declaredGraph: Map<String, Set<String>> =
    mapOf(
        "rain-core" to setOf(),
        // Checks every module in its test scope only; it has no production code.
        "rain-architecture" to setOf(),
        "rain-boot" to setOf("rain-core"),
        "rain-test" to setOf("rain-boot", "rain-core"),
        "rain-observability" to setOf("rain-boot", "rain-core"),
        "rain-persistence" to setOf("rain-boot", "rain-core"),
        "rain-data-jdbc" to setOf("rain-persistence", "rain-boot", "rain-core"),
        "rain-web" to setOf("rain-observability", "rain-boot", "rain-core"),
        "rain-crud" to setOf("rain-web", "rain-persistence", "rain-observability", "rain-boot", "rain-core"),
        "rain-resilience" to setOf("rain-observability", "rain-boot", "rain-core"),
        "rain-audit" to setOf("rain-persistence", "rain-boot", "rain-core"),
        "rain-access" to
            setOf("rain-web", "rain-audit", "rain-jobs", "rain-resilience", "rain-persistence", "rain-observability", "rain-boot", "rain-core"),
        "rain-realtime" to setOf("rain-persistence", "rain-observability", "rain-boot", "rain-core"),
        "rain-jobs" to setOf("rain-persistence", "rain-observability", "rain-boot", "rain-core"),
        "rain-llm" to setOf("rain-resilience", "rain-persistence", "rain-observability", "rain-boot", "rain-core"),
    )

fun Project.mainProjectDependencies(): Set<String> =
    mainScopes
        .flatMap { scope -> configurations.findByName(scope)?.dependencies?.withType<ProjectDependency>().orEmpty() }
        .map { it.path.removePrefix(":") }
        .filterNot { it == "rain-dependencies" }
        .toSortedSet()

val foundModules: Map<String, Set<String>> =
    subprojects
        .filter { it.parent == rootProject && it.name.startsWith("rain-") && it.name != "rain-dependencies" }
        .associate { it.name to it.mainProjectDependencies() }

val foundSamples: Map<String, Set<String>> =
    subprojects
        .filter { it.parent?.name == "samples" }
        .associate { it.path to it.mainProjectDependencies() }

val verifyModuleGraph =
    tasks.register("verifyModuleGraph") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that every rain module depends only on the modules the root build declares for it."
    val modules = foundModules
    val samples = foundSamples
    val declared = declaredGraph
    inputs.property("modules", modules.toString())
    inputs.property("samples", samples.toString())
    inputs.property("declared", declared.toString())
    val report = layout.buildDirectory.file("module-graph.txt")
    outputs.file(report)
    doLast {
        val problems = mutableListOf<String>()
        (modules.keys - declared.keys).sorted().forEach { problems += "$it is a module the graph does not declare" }
        (declared.keys - modules.keys).sorted().forEach { problems += "$it is declared in the graph but is not a module" }
        modules.toSortedMap().forEach { (module, dependencies) ->
            (dependencies - declared[module].orEmpty()).sorted().forEach { problems += "$module depends on $it, which the graph does not allow" }
        }
        samples.toSortedMap().forEach { (sample, dependencies) ->
            dependencies.filter { it !in declared.keys || it == "rain-test" }.sorted().forEach {
                problems += "$sample depends on $it; a sample uses rain modules other than rain-test"
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n", "the module graph has ${problems.size} problems:\n"))
        report.get().asFile.writeText(modules.toSortedMap().entries.joinToString("\n", postfix = "\n") { (m, d) -> "$m -> ${d.joinToString(", ")}" })
    }
}

val toolParity =
    configurations.create("toolParity") {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
    }

dependencies {
    add(toolParity.name, platform(project(":rain-dependencies")))
    listOf(
        "org.jooq:jooq",
        "org.flywaydb:flyway-core",
        "org.flywaydb:flyway-database-postgresql",
        "org.postgresql:postgresql",
        "org.testcontainers:testcontainers-postgresql",
    ).forEach { add(toolParity.name, it) }
}

/*
 * jOOQ codegen, Flyway and the PostgreSQL driver run inside the build (`rain.jooq-schema`) at the catalogue's pinned
 * versions, because a build script resolves no BOM. The modules resolve the same libraries through `rain-dependencies`.
 * The two have to be one version, or generated code and migrations are made by a tool the runtime does not use.
 */
val verifyToolParity =
    tasks.register("verifyToolParity") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Checks that the build-time tool pins are the versions rain's modules resolve."
        val pins =
            mapOf(
                "org.jooq:jooq" to libs.versions.jooq.get(),
                "org.flywaydb:flyway-core" to libs.versions.flyway.get(),
                "org.flywaydb:flyway-database-postgresql" to libs.versions.flyway.get(),
                "org.postgresql:postgresql" to libs.versions.postgresqlDriver.get(),
                "org.testcontainers:testcontainers-postgresql" to libs.versions.testcontainers.get(),
            )
        val resolved = toolParity.incoming.resolutionResult.rootComponent
        inputs.property("pins", pins)
        doLast {
            val selected =
                resolved
                    .get()
                    .dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .mapNotNull { it.selected.moduleVersion }
                    .associate { "${it.group}:${it.name}" to it.version }
            val drifted =
                pins.toSortedMap().mapNotNull { (module, pin) ->
                    val runtime = selected[module]
                    if (runtime == pin) null else "$module is pinned to $pin for the build and resolves to ${runtime ?: "nothing"} in the modules"
                }
            if (drifted.isNotEmpty()) throw GradleException(drifted.joinToString("\n", "the build tools drifted from the runtime:\n"))
        }
    }

/*
 * Every module a consumer uses has a page in docs/modules linked from the README, and every sample has a README. The
 * test-only rain-architecture module and the rain-dependencies platform are not modules a consumer uses.
 */
val verifyDocsCoverage =
    tasks.register("verifyDocsCoverage") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Checks that every consumer module has a docs page linked from the README and every sample a README."
        val modules = foundModules.keys.filterNot { it == "rain-architecture" }.sorted()
        val samples = foundSamples.keys.map { it.removePrefix(":samples:") }.sorted()
        val root = layout.projectDirectory
        inputs.property("modules", modules)
        inputs.property("samples", samples)
        doLast {
            val readme = root.file("README.md").asFile.readText()
            val problems = mutableListOf<String>()
            modules.forEach { module ->
                val page = "docs/modules/${module.removePrefix("rain-")}.md"
                if (!root.file(page).asFile.isFile) problems += "$module has no page $page"
                if ("($page)" !in readme) problems += "README.md does not link $page"
            }
            samples.forEach { sample ->
                if (!root.file("samples/$sample/README.md").asFile.isFile) problems += "sample $sample has no README.md"
            }
            if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n", "the documentation misses ${problems.size} things:\n"))
        }
    }

tasks.named("check") {
    dependsOn(verifyModuleGraph, verifyToolParity, verifyDocsCoverage)
}
