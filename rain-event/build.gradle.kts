/*
 * Explicit aggregate/fact event kernel and the optional PostgreSQL event log. There is no command
 * bus or reflection discovery: applications declare catalogues and own their decision functions.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-jobs"))
    api(project(":rain-realtime"))
    api(project(":rain-persistence"))
    api(project(":rain-observability"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":rain-test"))
    testImplementation(project(":rain-event-test"))
}
