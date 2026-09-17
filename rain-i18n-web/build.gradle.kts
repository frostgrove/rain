// Servlet-only magic-first bridge; the rain-i18n kernel remains Spring-free.
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-i18n"))
    api(project(":rain-web"))
    implementation(project(":rain-boot"))

    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.boot:spring-boot-http-converter")
}
