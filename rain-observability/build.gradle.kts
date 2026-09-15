/*
 * What a rain process answers `/live` and `/ready` from: health contributions probed in parallel
 * against absolute budgets, one shared pass per freshness window, draining on shutdown, the same
 * readings published to Actuator, and the OpenTelemetry Logback bridge when OTLP log export is on.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-boot"))
    api("org.springframework.boot:spring-boot-health")
    implementation("org.slf4j:slf4j-api")

    // The Logback bridge is optional: an application that exports logs over OTLP brings the appender
    // and Logback; the auto-configuration is conditional on both classes.
    compileOnly(libs.otel.logback.appender)
    compileOnly("io.opentelemetry:opentelemetry-api")
    compileOnly("ch.qos.logback:logback-classic")

    testImplementation(libs.otel.logback.appender)
    testImplementation("io.opentelemetry:opentelemetry-sdk")
    testImplementation("ch.qos.logback:logback-classic")
}
