/*
 * Circuit breakers for the dependencies a rain application calls, on Resilience4j's Spring Boot 4 starter.
 *
 * The starter's auto-configuration is used on purpose, not excluded: its `CircuitBreakerRegistry` bean and
 * its `resilience4j.circuitbreaker.instances.<name>` properties are the battery. rain adds breaker
 * declarations, atomic admission with one probe per cooldown, readiness contributions, and a bean-time
 * check that every declared breaker is configured explicitly.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-observability"))
    api(libs.resilience4j.spring.boot4)

    testImplementation(project(":rain-test"))
}
