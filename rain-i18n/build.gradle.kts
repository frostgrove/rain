/*
 * Spring-free internationalization kernel. It owns typed deferred messages, immutable catalog
 * snapshots and deterministic locale/formatting contracts; servlet, JDBC and job integration live
 * in separate optional modules.
 */
plugins {
    id("rain.kotlin-library")
}

dependencies {
    api(project(":rain-core"))
    implementation(libs.icu4j)
    implementation("tools.jackson.core:jackson-core")

    testImplementation(project(":rain-test"))
}
