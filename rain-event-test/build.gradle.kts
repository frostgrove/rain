// Deterministic event-store fixtures and conformance support; applications may use it only in tests.
plugins {
    id("rain.kotlin-library")
}

dependencies {
    api(project(":rain-event"))
    api(project(":rain-test"))
}
