/*
 * For applications that use Spring Data JDBC: application-minted ids on insert and the column
 * conversions rain's persistence markers promise. rain itself registers no repository.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-persistence"))
    api("org.springframework.boot:spring-boot-data-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.postgresql:postgresql")

    testImplementation(project(":rain-test"))
    testImplementation("com.zaxxer:HikariCP")
}
