pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // The build declares a Java 25 toolchain; Gradle provisions it rather than depending on the host JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rain"

include(
    "rain-dependencies",
    "rain-core",
    "rain-boot",
    "rain-test",
    "rain-observability",
    "rain-persistence",
    "rain-data-jdbc",
    "rain-web",
    "rain-crud",
    "rain-audit",
    "rain-resilience",
    "rain-realtime",
    "rain-jobs",
    "rain-llm",
    "samples:rain-sample-minimal",
)
