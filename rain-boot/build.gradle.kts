/*
 * What every rain application runs on: runtime roles and one-shot commands, the deployment stage,
 * configuration validation that reports every problem in one start-up, and seeding.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-core"))
    implementation(libs.kotlin.reflect)
    implementation("org.slf4j:slf4j-api")
}
