/*
 * A gateway over any Spring AI `ChatModel` bean: cluster-wide admission (slot rows per pool in schema
 * `rain_llm`, ceilings per request class), breaker accounting of model calls only, an output token budget
 * from a declared token counter, and the `smoke-llm` command.
 */
plugins {
    id("rain.jooq-schema")
    `java-test-fixtures`
}

rainSchema {
    module.set("llm")
}

dependencies {
    api(project(":rain-resilience"))
    api(project(":rain-persistence"))
    api("org.springframework.ai:spring-ai-model")
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":rain-test"))
    testImplementation("com.zaxxer:HikariCP")
}
