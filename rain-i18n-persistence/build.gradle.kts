/*
 * Optional durable catalog lifecycle adapter. It bridges i18n to the persistence plane only;
 * jobs, events and tenancy compose through their own two-context adapters.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-i18n"))
    api(project(":rain-persistence"))
    implementation(project(":rain-boot"))

    testImplementation(project(":rain-test"))
}
