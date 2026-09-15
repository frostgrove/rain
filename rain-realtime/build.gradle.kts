/*
 * PostgreSQL LISTEN/NOTIFY as an in-process bus: a publisher that notifies on the caller's
 * transaction, and a listener that parks one dedicated connection on LISTEN and fans notifications
 * out to bounded subscriptions.
 */
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-persistence"))
    implementation("com.zaxxer:HikariCP")
    implementation("org.postgresql:postgresql")
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":rain-test"))
    testImplementation("ch.qos.logback:logback-classic")
}
