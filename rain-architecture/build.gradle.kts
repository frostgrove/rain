/*
 * The architecture rules (rain-test's RainArchRules) checked over every rain module and sample at once: production
 * classes from the modules' classpath, test classes from each module's compiled test output. Nothing here is published.
 */
plugins {
    id("rain.kotlin-library")
}

val ruled =
    rootProject.subprojects.filter { project ->
        (
            project.parent == rootProject && project.name.startsWith("rain-") &&
                project.name !in setOf("rain-dependencies", "rain-architecture")
        ) ||
            project.parent?.name == "samples"
    }

dependencies {
    ruled.forEach { testImplementation(project(it.path)) }
}

tasks.withType<Test>().configureEach {
    ruled.forEach { dependsOn("${it.path}:testClasses") }
    systemProperty(
        "rain.architecture.testClasses",
        ruled.joinToString(File.pathSeparator) {
            it.layout.buildDirectory
                .dir("classes/kotlin/test")
                .get()
                .asFile.path
        },
    )
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    enabled = false
}
