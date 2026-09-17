/*
 * The one platform a consumer imports: Spring Boot, Spring AI and OpenTelemetry BOMs, plus the
 * explicitly versioned third-party artefacts rain modules depend on.
 */
plugins {
    `java-platform`
    id("rain.publishing")
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
        api(libs.icu4j)

        // rain's own modules, so an application imports one platform and names modules without versions.
        api(project(":rain-core"))
        api(project(":rain-i18n"))
        api(project(":rain-i18n-test"))
        api(project(":rain-i18n-web"))
        api(project(":rain-i18n-jobs"))
        api(project(":rain-i18n-persistence"))
        api(project(":rain-i18n-integration"))
        api(project(":rain-i18n-tool"))
        api(project(":rain-tenancy-i18n"))
        api(project(":rain-boot"))
        api(project(":rain-test"))
        api(project(":rain-observability"))
        api(project(":rain-persistence"))
        api(project(":rain-event"))
        api(project(":rain-event-test"))
        api(project(":rain-data-jdbc"))
        api(project(":rain-web"))
        api(project(":rain-crud"))
        api(project(":rain-resilience"))
        api(project(":rain-audit"))
        api(project(":rain-access"))
        api(project(":rain-realtime"))
        api(project(":rain-jobs"))
        api(project(":rain-tenancy"))
        api(project(":rain-tenancy-event"))
        api(project(":rain-llm"))
    }
}
