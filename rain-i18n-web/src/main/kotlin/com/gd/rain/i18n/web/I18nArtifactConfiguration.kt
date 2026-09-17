package com.gd.rain.i18n.web

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import com.gd.rain.i18n.CatalogArtifactCodec
import com.gd.rain.i18n.CatalogArtifactDecoding
import com.gd.rain.i18n.CatalogRuntimeIdentity
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSnapshotProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.ResourceLoader

/**
 * Magic-first local-artifact bootstrap configuration.
 *
 * Remote releases and durable heads deliberately do not pass through this path: applications use
 * their `CatalogSnapshotProvider`/durable store instead. When enabled, no JDK locale default or
 * classpath discovery chooses a catalog; the one canonical artifact location is stated.
 */
@ConfigurationProperties(I18nArtifactProperties.PREFIX)
public data class I18nArtifactProperties(
    public val enabled: Boolean = false,
    public val artifactLocation: String? = null,
    public val runtime: Runtime? = null,
) {
    public fun problems(): List<ConfigurationProblem> =
        problems {
            artifactLocation?.let { location ->
                expect(location.length in 1..MAX_LOCATION_CHARS, "$PREFIX.artifact-location") {
                    "is not 1..$MAX_LOCATION_CHARS characters"
                }
                expect(location.startsWith("classpath:") || location.startsWith("file:"), "$PREFIX.artifact-location") {
                    "is a classpath: or file: resource, not a remote URL"
                }
            }
            runtime?.let { addAll(it.problems("$PREFIX.runtime")) }
            if (!enabled) {
                expect(artifactLocation == null && runtime == null, PREFIX, ProblemCode.CONTRADICTS) {
                    "local artifact settings are present while $ENABLED is false"
                }
                return@problems
            }
            expect(artifactLocation != null, "$PREFIX.artifact-location", ProblemCode.REQUIRED) {
                "no local canonical artifact location is provided"
            }
            expect(runtime != null, "$PREFIX.runtime", ProblemCode.REQUIRED) {
                "no exact runtime identity is provided"
            }
        }

    public fun settings(): I18nArtifactSettings {
        val found = problems()
        if (found.isNotEmpty()) throw ConfigurationProblemsException(found)
        check(enabled) { "$ENABLED is false; there are no local i18n artifact settings" }
        return I18nArtifactSettings(checkNotNull(artifactLocation), checkNotNull(runtime).identity())
    }

    public companion object {
        public const val PREFIX: String = "rain.i18n"
        public const val ENABLED: String = "$PREFIX.enabled"
        private const val MAX_LOCATION_CHARS: Int = 2_048
    }

    /** Identity is explicit because an artifact compiled with another ICU/grammar data set is unsafe. */
    public data class Runtime(
        public val profile: String? = null,
        public val engine: String? = null,
        public val icuClDrTzdbIdentity: String? = null,
    ) : ConfigurationSection {
        internal fun problems(path: String): List<ConfigurationProblem> =
            problems {
                expect(profile != null, "$path.profile", ProblemCode.REQUIRED) { "no profile is provided" }
                expect(engine != null, "$path.engine", ProblemCode.REQUIRED) { "no engine is provided" }
                expect(icuClDrTzdbIdentity != null, "$path.icu-cl-dr-tzdb-identity", ProblemCode.REQUIRED) {
                    "no ICU/CLDR/tzdb identity is provided"
                }
                profile?.let { value -> expect(identity(value), "$path.profile") { "is not 1..128 printable ASCII characters" } }
                engine?.let { value -> expect(identity(value), "$path.engine") { "is not 1..128 printable ASCII characters" } }
                icuClDrTzdbIdentity?.let { value ->
                    expect(identity(value), "$path.icu-cl-dr-tzdb-identity") { "is not 1..128 printable ASCII characters" }
                }
            }

        internal fun identity(): CatalogRuntimeIdentity =
            CatalogRuntimeIdentity(
                checkNotNull(profile) { "rain.i18n.runtime.profile is required when i18n is enabled" },
                checkNotNull(engine) { "rain.i18n.runtime.engine is required when i18n is enabled" },
                checkNotNull(icuClDrTzdbIdentity) { "rain.i18n.runtime.icu-cl-dr-tzdb-identity is required when i18n is enabled" },
            )

        private fun identity(value: String): Boolean =
            value.isNotEmpty() && value.length <= 128 && value.all { character -> character.code in 0x21..0x7e }
    }
}

/** Validated local bootstrap inputs, usable by applications that do not use Spring auto-configuration. */
public data class I18nArtifactSettings(
    public val artifactLocation: String,
    public val runtimeIdentity: CatalogRuntimeIdentity,
)

/** Declares the optional local artifact bootstrap before web auto-configuration creates a bean. */
public class I18nArtifactConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec(I18nArtifactProperties.PREFIX, I18nArtifactProperties::class, Presence.OPTIONAL) { properties, _ ->
                properties.problems()
            },
        )
}

/** Immutable local provider: artifact bytes are loaded and validated fully before this bean becomes visible. */
public class ArtifactCatalogSnapshotProvider(
    settings: I18nArtifactSettings,
    resources: ResourceLoader,
) : CatalogSnapshotProvider {
    private val snapshot: CatalogSnapshot = load(settings, resources)

    override fun current(): CatalogSnapshot = snapshot

    private fun load(
        settings: I18nArtifactSettings,
        resources: ResourceLoader,
    ): CatalogSnapshot {
        val resource = resources.getResource(settings.artifactLocation)
        require(resource.exists() && resource.isReadable) { "i18n artifact ${settings.artifactLocation} is not readable" }
        val bytes = resource.inputStream.use { input -> input.readBounded(MAX_ARTIFACT_BYTES) }
        return when (val decoded = CatalogArtifactCodec(runtimeIdentity = settings.runtimeIdentity).decode(bytes)) {
            is CatalogArtifactDecoding.Decoded -> decoded.snapshot

            is CatalogArtifactDecoding.Refused -> throw IllegalArgumentException(
                "i18n artifact is invalid: ${decoded.problems.joinToString { problem -> "${problem.path}: ${problem.message}" }}",
            )
        }
    }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() > limit - read) throw IllegalArgumentException("i18n artifact exceeds $limit bytes")
            output.write(buffer, 0, read)
        }
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES: Int = 16 * 1024 * 1024
    }
}
