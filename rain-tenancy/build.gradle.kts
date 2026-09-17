// Optional tenant authority, control plane and scoped data-plane contracts.
plugins {
    id("rain.jooq-schema")
}

rainSchema {
    module.set("tenancy")
}

dependencies {
    api(project(":rain-web"))
    api(project(":rain-audit"))
    api(project(":rain-jobs"))
    api(project(":rain-persistence"))
    api(project(":rain-observability"))
    implementation("org.aspectj:aspectjweaver")
    implementation("com.zaxxer:HikariCP")
    implementation("org.postgresql:postgresql")

    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation(project(":rain-test"))
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework:spring-test")
}
