plugins {
    id("rain.kotlin-library")
    `java-gradle-plugin`
    application
}

application {
    mainClass = "com.gd.rain.i18n.tool.RainI18nCli"
}

dependencies {
    api(project(":rain-i18n"))

    compileOnly(gradleApi())
    compileOnly(libs.kotlin.compiler.embeddable)
    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.compiler.embeddable)
}

gradlePlugin {
    plugins {
        create("rainI18n") {
            id = "com.gd.rain.i18n"
            implementationClass = "com.gd.rain.i18n.tool.gradle.RainI18nPlugin"
            displayName = "Rain i18n"
            description = "Deterministic Rain i18n catalog tasks and generated contracts."
        }
    }
}
