// Durable i18n intent for rain-jobs; persistence and tenant adapters remain separate.
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-i18n"))
    api(project(":rain-jobs"))
    implementation(project(":rain-boot"))
    implementation(project(":rain-i18n-persistence"))

    testImplementation(project(":rain-test"))
    testImplementation("org.springframework.boot:spring-boot-test")
}
