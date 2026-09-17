// Deterministic test fixtures for the pure i18n kernel; no servlet, JDBC or hidden locale defaults.
plugins {
    id("rain.kotlin-library")
}

dependencies {
    api(project(":rain-i18n"))
    api(project(":rain-test"))
}
