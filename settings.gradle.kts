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
    "rain-i18n",
    "rain-i18n-test",
    "rain-i18n-observability",
    "rain-i18n-web",
    "rain-i18n-jobs",
    "rain-i18n-persistence",
    "rain-i18n-integration",
    "rain-i18n-tool",
    "rain-tenancy-i18n",
    "rain-boot",
    "rain-test",
    "rain-observability",
    "rain-persistence",
    "rain-event",
    "rain-event-test",
    "rain-data-jdbc",
    "rain-web",
    "rain-crud",
    "rain-audit",
    "rain-access",
    "rain-resilience",
    "rain-realtime",
    "rain-jobs",
    "rain-tenancy",
    "rain-tenancy-event",
    "rain-llm",
    "rain-architecture",
    "samples:rain-sample",
    "samples:rain-sample-minimal",
)
