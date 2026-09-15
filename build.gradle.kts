/*
 * The root of the rain build: the module graph every rain module keeps.
 *
 * A module may depend (in its main scope: api, implementation, compileOnly, runtimeOnly) only on the modules declared
 * for it below, lower in the graph; test scopes and `rain-dependencies` are not part of the graph. A new module is
 * declared here in the same change that adds it, and a sample may use any rain module but `rain-test`.
 */
plugins {
    base
}

evaluationDependsOnChildren()

val mainScopes = listOf("api", "implementation", "compileOnly", "runtimeOnly")

val declaredGraph: Map<String, Set<String>> =
    mapOf(
        "rain-core" to setOf(),
        "rain-boot" to setOf("rain-core"),
        "rain-test" to setOf("rain-boot", "rain-core"),
        "rain-observability" to setOf("rain-boot", "rain-core"),
        "rain-persistence" to setOf("rain-boot", "rain-core"),
        "rain-data-jdbc" to setOf("rain-persistence", "rain-boot", "rain-core"),
        "rain-web" to setOf("rain-observability", "rain-boot", "rain-core"),
        "rain-crud" to setOf("rain-web", "rain-persistence", "rain-observability", "rain-boot", "rain-core"),
        "rain-resilience" to setOf("rain-observability", "rain-boot", "rain-core"),
        "rain-audit" to setOf("rain-persistence", "rain-boot", "rain-core"),
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

val verifyModuleGraph by tasks.registering {
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

tasks.named("check") {
    dependsOn(verifyModuleGraph)
}
