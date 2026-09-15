/*
 * Declarative resources over PostgreSQL: a strict wire query dialect, row-level policy, keyset-first
 * pagination with bounded counting, and problem+json refusals. The query model and the store are plain
 * Kotlin; only the `web` package knows about servlets.
 */
plugins {
    id("rain.spring-module")
    `java-test-fixtures`
}

dependencies {
    api(project(":rain-web"))
    api(project(":rain-persistence"))

    // The servlet API is the container's; rain-crud does not choose one for the application.
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // The plan proof an application runs against its own database: rain-crud's statements, explained by rain-test.
    testFixturesApi(project(":rain-test"))

    testImplementation(project(":rain-test"))
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.boot:spring-boot-http-converter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
