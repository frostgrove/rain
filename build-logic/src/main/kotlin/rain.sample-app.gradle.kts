import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/*
 * A rain sample application: rain's modules consumed the way an application consumes them, built as an
 * executable jar. A sample is not a library, so explicit API mode is off; everything else — toolchain,
 * warnings as errors, ktlint, the two test tiers — is the same as for rain's modules.
 */
plugins {
    id("rain.kotlin-library")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
}

extensions.configure<KotlinJvmProjectExtension> {
    explicitApi = ExplicitApiMode.Disabled
}
