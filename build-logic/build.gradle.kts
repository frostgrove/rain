plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.spring.boot.gradle.plugin)
}
