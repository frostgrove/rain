/*
 * The durable job queue: db-scheduler core polls and delivers, rain's own ledger in schema `rain_jobs`
 * owns the attempt lease, the fence, deduplicating intents, retries into dead letters, and retention.
 */
plugins {
    id("rain.jooq-schema")
}

rainSchema {
    module.set("jobs")
    // db-scheduler owns scheduled_tasks; no typed rain statement may name it.
    excludedTables.set(listOf("scheduled_tasks"))
}

dependencies {
    api(project(":rain-persistence"))
    api(project(":rain-observability"))
    implementation(libs.db.scheduler)
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.micrometer:micrometer-core")
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":rain-test"))
    testImplementation("com.zaxxer:HikariCP")
    testImplementation(libs.archunit.junit5)
}
