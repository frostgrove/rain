/*
 * The universal servlet web layer: RFC 9457 problem responses for every refusal, the transport
 * filters (security headers, request log, request budget, CORS, body limit, cross-site guard), the
 * probes, role gating of the web surface, and generic throttle primitives.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-observability"))
    api("org.springframework.boot:spring-boot-webmvc")
    api("org.springframework.boot:spring-boot-jackson")
    implementation("org.springframework.boot:spring-boot-servlet")
    implementation("org.slf4j:slf4j-api")
    implementation("jakarta.validation:jakarta.validation-api")

    // The servlet API is the container's; rain-web does not choose one for the application.
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.boot:spring-boot-http-converter")
    testImplementation("org.springframework.boot:spring-boot-tomcat")
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

val testSourceSet = the<SourceSetContainer>()["test"]

tasks.register<Test>("writeProblemGolden") {
    description = "Rewrites src/test/resources/problem-format-v1.golden.json from the renderer; never part of check."
    group = "rain"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.gd.rain.web.problem.ProblemFormatGoldenTest") }
    systemProperty("rain.writeGolden", "true")
    outputs.upToDateWhen { false }
}
