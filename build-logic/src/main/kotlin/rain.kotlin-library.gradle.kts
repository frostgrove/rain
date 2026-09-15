import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/*
 * Every rain module: Kotlin on the Java 25 toolchain, explicit API, warnings as errors, ktlint, Kover,
 * publishing, and two test tiers split by JUnit tag. `test` is the unit tier: none of its tests needs Docker
 * (compiling a module that owns tables does — see `rain.jooq-schema`). `integrationTest` runs exactly the classes
 * tagged `integration` and needs Docker. `check` runs both.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    id("com.diffplug.spotless")
    id("org.jetbrains.kotlinx.kover")
    id("rain.publishing")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val integrationTag = "integration"

group = "com.gd.rain"
version = "0.1.0-SNAPSHOT"

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        freeCompilerArgs.addAll(
            "-Xjspecify-annotations=strict",
            "-Xconsistent-data-class-copy-visibility",
            "-Werror",
        )
    }
}

dependencies {
    "api"(platform(project(":rain-dependencies")))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testImplementation"(libs.findLibrary("mockk").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags(integrationTag)
    }
}

val testSourceSet = the<SourceSetContainer>()["test"]

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs the @Tag(\"$integrationTag\") classes; needs a Docker daemon."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags(integrationTag)
        }
        shouldRunAfter(tasks.named("test"))
    }

tasks.named("check") {
    dependsOn(integrationTest)
}

/*
 * Every module declares its coverage bounds in `coverage-bounds.properties`: `line` and `branch`, whole percent of the
 * module's code (generated jOOQ code excluded), measured over both test tiers. `koverVerify` (part of `check`) refuses a
 * change that drops below them; a bound is raised when coverage rises.
 */
val coverageBoundsFile = layout.projectDirectory.file("coverage-bounds.properties").asFile
val coverageBounds =
    Properties().also { bounds ->
        check(coverageBoundsFile.isFile) { "${project.path} declares no coverage bounds: add ${coverageBoundsFile.name} with line= and branch=" }
        coverageBoundsFile.inputStream().use(bounds::load)
    }

fun coverageBound(unit: String): Int =
    coverageBounds.getProperty(unit)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
        ?: error("${project.path}: ${coverageBoundsFile.name} states no whole percent between 0 and 100 for `$unit`")

kover {
    reports {
        filters {
            excludes {
                packages("com.gd.rain.*.jooq")
            }
        }
        verify {
            rule {
                minBound(coverageBound("line"), CoverageUnit.LINE)
                minBound(coverageBound("branch"), CoverageUnit.BRANCH)
            }
        }
    }
}

spotless {
    kotlin {
        target("src/*/kotlin/**/*.kt")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}
