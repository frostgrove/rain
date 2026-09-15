/*
 * Test support for rain modules and rain applications: a shared PostgreSQL container with a fresh
 * database per test, a movable clock, and query-plan assertions that prove a statement is bounded
 * without depending on how many rows a test inserted.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-boot"))
    api("org.springframework.boot:spring-boot-test")
    api("org.springframework:spring-jdbc")
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.assertj:assertj-core")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.postgresql:postgresql")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("com.zaxxer:HikariCP")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
