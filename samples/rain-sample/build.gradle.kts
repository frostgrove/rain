import org.springframework.boot.gradle.tasks.bundling.BootJar

/*
 * The reference application: a helpdesk that uses every rain module the way a product would — access, CRUD with a
 * row scope, audit, jobs with a long step and a fenced effect, the LLM gateway behind a breaker, realtime over
 * server-sent events, Spring Data JDBC beside jOOQ — started for real by its integration tests and runnable by hand
 * with `compose.yaml`. It reaches rain through public API only (`SampleArchitectureTest`).
 */
plugins {
    id("rain.sample-app")
}

dependencies {
    implementation(project(":rain-core"))
    implementation(project(":rain-boot"))
    implementation(project(":rain-observability"))
    implementation(project(":rain-persistence"))
    implementation(project(":rain-data-jdbc"))
    implementation(project(":rain-web"))
    implementation(project(":rain-crud"))
    implementation(project(":rain-audit"))
    implementation(project(":rain-access"))
    implementation(project(":rain-resilience"))
    implementation(project(":rain-jobs"))
    implementation(project(":rain-llm"))
    implementation(project(":rain-realtime"))
    implementation(project(":rain-i18n"))
    implementation(project(":rain-i18n-web"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // rain-access keeps its revocation list and attempt counters on the application's Redis.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(project(":rain-test"))
    testImplementation(testFixtures(project(":rain-crud")))
    testImplementation(libs.archunit.junit5)
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

val bootJar =
    tasks.named<BootJar>("bootJar") {
        // The image and the subprocess tests name the jar without a version.
        archiveFileName.set("rain-sample.jar")
    }

// `CommandModeExitCodesE2E` runs the jar a deployment runs, as a subprocess.
tasks.named<Test>("integrationTest") {
    val jar = bootJar.flatMap { it.archiveFile }
    inputs.file(jar).withPropertyName("bootJar")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dsample.boot-jar=${jar.get().asFile.absolutePath}") })
}
