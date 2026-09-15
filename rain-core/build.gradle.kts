/*
 * Spring-free contracts every other module and every consumer's domain code can depend on.
 */
plugins {
    id("rain.kotlin-library")
}

dependencies {
    testImplementation(libs.archunit.junit5)
}
