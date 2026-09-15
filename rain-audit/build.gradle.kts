/*
 * The append-only audit trail: evidence written in the transaction that made the change, in a schema
 * of its own, with no foreign key into any subject table.
 */
plugins {
    id("rain.jooq-schema")
}

rainSchema {
    module.set("audit")
}

dependencies {
    api(project(":rain-persistence"))
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":rain-test"))
    testImplementation("com.zaxxer:HikariCP")
}
