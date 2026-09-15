/*
 * Who a caller is and what it may do: password sign-in with database sessions and rotating refresh
 * credentials, an HS256 access token, a revocation list on the application's Redis, role and permission
 * grants over a catalogue synchronised at start-up, a start-up verification that every mounted route
 * declares its access, and the gate in front of the credential surface.
 */
plugins {
    id("rain.jooq-schema")
}

rainSchema {
    module.set("access")
}

dependencies {
    api(project(":rain-web"))
    api(project(":rain-audit"))
    api(project(":rain-jobs"))
    api(project(":rain-resilience"))
    api("org.springframework.boot:spring-boot-security")
    // Nimbus JOSE and Spring Security's JWT encoder and decoder.
    implementation("org.springframework.boot:spring-boot-security-oauth2-resource-server")
    // Argon2id; an optional dependency of spring-security-crypto that no BOM manages.
    implementation(libs.bcprov)
    implementation("org.slf4j:slf4j-api")

    // The servlet API is the container's, and Redis is the application's: an application whose revocation
    // list and attempt counters are not on Redis does not carry the client.
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.boot:spring-boot-data-redis")

    testImplementation(project(":rain-test"))
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework.boot:spring-boot-data-redis")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.boot:spring-boot-http-converter")
    testImplementation("org.springframework.boot:spring-boot-tomcat")
    testImplementation("com.zaxxer:HikariCP")
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.testcontainers:testcontainers")
    // A configuration file a test application imports, as a deployment writes one.
    testImplementation("org.yaml:snakeyaml")
}
