/*
 * Optional i18n ↔ observability bridge. The pure catalog kernel stays unaware of metrics,
 * dashboards and Spring; applications can replace this bridge with any I18nObserver.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-i18n"))
    api(project(":rain-observability"))
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":rain-test"))
}
