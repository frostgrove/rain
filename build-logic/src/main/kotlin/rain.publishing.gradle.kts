/*
 * Publishes a rain module (or the rain-dependencies platform) as `com.gd.rain:<module>`.
 *
 * `publishToMavenLocal` needs nothing. A remote repository is used only when the build states it:
 * `-Prain.publish.url=<url>`, with `rain.publish.username` and `rain.publish.password` when it needs credentials.
 */
plugins {
    `maven-publish`
}

publishing {
    repositories {
        providers.gradleProperty("rain.publish.url").orNull?.let { location ->
            maven {
                name = "rain"
                url = uri(location)
                providers.gradleProperty("rain.publish.username").orNull?.let { user ->
                    credentials {
                        username = user
                        password = providers.gradleProperty("rain.publish.password").get()
                    }
                }
            }
        }
    }
}

pluginManager.withPlugin("java-platform") {
    publishing.publications.register<MavenPublication>("rain") { from(components["javaPlatform"]) }
}

pluginManager.withPlugin("java-library") {
    publishing.publications.register<MavenPublication>("rain") { from(components["java"]) }
}
