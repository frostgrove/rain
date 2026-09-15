/*
 * The smallest rain application: rain-web over rain-boot and rain-observability, and nothing that stores data.
 * It proves the persistence modules are optional and shows what every rain HTTP application states.
 */
plugins {
    id("rain.sample-app")
}

dependencies {
    implementation(project(":rain-web"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
