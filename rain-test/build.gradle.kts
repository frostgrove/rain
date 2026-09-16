/*
 * Test support for rain modules and rain applications: a shared PostgreSQL container with a fresh
 * database per test, Redis servers by eviction policy, a real application start with a plain HTTP client,
 * a movable clock, query-plan assertions that prove a statement is bounded without depending on how many
 * rows a test inserted, and the architecture rules rain's code keeps.
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
    api("org.testcontainers:testcontainers")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.postgresql:postgresql")
    implementation("tools.jackson.core:jackson-databind")
    // The architecture rules rain holds its modules to, for applications to hold their own code to as well.
    api(libs.archunit.junit5)

    testImplementation("com.zaxxer:HikariCP")
    // A servlet application started through RainApplication, for the port and the HTTP client.
    testImplementation("org.springframework.boot:spring-boot-webmvc")
    testImplementation("org.springframework.boot:spring-boot-tomcat")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
