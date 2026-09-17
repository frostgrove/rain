plugins {
    id("rain.kotlin-library")
}

dependencies {
    api(project(":rain-i18n"))

    testImplementation(project(":rain-test"))
}
