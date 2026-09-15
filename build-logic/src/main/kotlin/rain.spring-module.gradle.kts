/*
 * A rain module that contributes Spring beans: all-open for Spring proxies, and the Boot
 * auto-configuration API on the compile classpath. Configuration metadata processors are wired in
 * P1 once kapt on K2 / Java 25 is proven; until then nothing here claims to generate metadata.
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
