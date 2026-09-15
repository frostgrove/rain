/*
 * A rain module that contributes Spring beans: all-open for Spring proxies, and the Boot
 * auto-configuration API on the compile classpath. No Spring Boot configuration metadata is generated:
 * the processors read Java sources only. Every `rain.*` property is validated at start-up by its
 * `ConfigurationContributor` and documented in `docs/modules`.
 */
plugins {
    id("rain.kotlin-library")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(libs.findLibrary("spring-boot-autoconfigure").get())
    "testImplementation"(libs.findLibrary("spring-boot-test").get())
}
