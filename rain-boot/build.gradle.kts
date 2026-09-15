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

    // The console appender rain-boot ships for a logback configuration, configured by a test as an application would.
    testImplementation("ch.qos.logback:logback-classic")
}
