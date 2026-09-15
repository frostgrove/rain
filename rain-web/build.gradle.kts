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
    implementation("org.slf4j:slf4j-api")

    // The servlet API is the container's; rain-web does not choose one for the application.
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.boot:spring-boot-http-converter")
    testImplementation("org.springframework.boot:spring-boot-tomcat")
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
