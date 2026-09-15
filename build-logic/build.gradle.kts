plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.spring.boot.gradle.plugin)

    // `rain.jooq-schema` migrates and introspects a throwaway PostgreSQL inside the build. The versions are
    // the catalogue's pins because a build script resolves no BOM.
    implementation(libs.jooq.codegen.tool)
    implementation(libs.jooq.meta.tool)
    implementation(libs.flyway.core.tool)
    implementation(libs.flyway.postgresql.tool)
    implementation(libs.postgresql.tool)
    implementation(libs.testcontainers.postgresql.tool)
}
