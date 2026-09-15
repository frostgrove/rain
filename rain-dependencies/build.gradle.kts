/*
 * The one platform a consumer imports: Spring Boot, Spring AI and OpenTelemetry BOMs, plus the
 * explicitly versioned third-party artefacts rain modules depend on.
 */
plugins {
    `java-platform`
}

group = "com.gd.rain"
version = "0.1.0-SNAPSHOT"

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.ai.bom))
    // Above Boot's OpenTelemetry BOM on purpose: the logback appender needs the matching SDK train.
    api(platform(libs.otel.bom))

    constraints {
        api(libs.resilience4j.spring.boot4)
        api(libs.db.scheduler)
        api(libs.bcprov)
        api(libs.jug)
        api(libs.jooq)
        api(libs.otel.logback.appender)
        api(libs.archunit.junit5)
        api(libs.mockk)
    }
}
