/*
 * PostgreSQL persistence on Spring Boot's JDBC, jOOQ and Flyway: application-minted ids, SQLState
 * classification, transaction retry, advisory locks, statement timeouts and schema-per-module
 * migrations.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-boot"))
    api("org.springframework.boot:spring-boot-jdbc")
    api("org.springframework.boot:spring-boot-jooq")
    api("org.springframework.boot:spring-boot-flyway")
    api("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation(libs.jug)

    testImplementation(project(":rain-test"))
    testImplementation("com.zaxxer:HikariCP")
}
