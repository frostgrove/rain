package com.gd.rain.i18n.persistence

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import com.gd.rain.i18n.CatalogRuntimeIdentity
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Optional operational configuration for the `i18n ↔ persistence` bridge.
 *
 * Disabled configuration carries no operational settings. Enabling it deliberately states the
 * grammar/Unicode runtime identity and each retention bound; artifact source, signing keys and
 * release scheduling remain replaceable application adapters rather than hidden background work.
 */
@ConfigurationProperties(I18nPersistenceProperties.PREFIX)
public data class I18nPersistenceProperties(
    public val enabled: Boolean,
    public val runtime: Runtime? = null,
    public val maxRetained: Int? = null,
    public val maxPins: Int? = null,
    public val maxPinLifetime: Duration? = null,
    public val changeFeedBatch: Int? = null,
) {
    public fun problems(): List<ConfigurationProblem> =
        problems {
            runtime?.let { addAll(it.problems("$PREFIX.runtime")) }
            maxRetained?.let { expect(it in 1..4_096, "$PREFIX.max-retained") { "is $it; it is 1..4096" } }
            maxPins?.let { expect(it in 1..1_048_576, "$PREFIX.max-pins") { "is $it; it is 1..1048576" } }
            maxPinLifetime?.let {
                expect(it > Duration.ZERO && it <= Duration.ofDays(3650), "$PREFIX.max-pin-lifetime") {
                    "is $it; it is positive and at most 3650 days"
                }
            }
            changeFeedBatch?.let { expect(it in 1..16_384, "$PREFIX.change-feed-batch") { "is $it; it is 1..16384" } }
            if (!enabled) {
                expect(
                    runtime == null && maxRetained == null && maxPins == null && maxPinLifetime == null && changeFeedBatch == null,
                    PREFIX,
                    ProblemCode.CONTRADICTS,
                ) { "operational settings are present while $ENABLED is false" }
                return@problems
            }
            expect(
                runtime != null,
                "$PREFIX.runtime",
                ProblemCode.REQUIRED,
            ) { "no runtime identity is provided while i18n persistence is enabled" }
            expect(maxRetained != null, "$PREFIX.max-retained", ProblemCode.REQUIRED) { "no retained-release ceiling is provided" }
            expect(maxPins != null, "$PREFIX.max-pins", ProblemCode.REQUIRED) { "no pin ceiling is provided" }
            expect(maxPinLifetime != null, "$PREFIX.max-pin-lifetime", ProblemCode.REQUIRED) { "no pin lifetime is provided" }
            expect(changeFeedBatch != null, "$PREFIX.change-feed-batch", ProblemCode.REQUIRED) { "no change-feed page ceiling is provided" }
        }

    public companion object {
        public const val PREFIX: String = "rain.i18n.persistence"
        public const val ENABLED: String = "$PREFIX.enabled"
    }

    /** Exact formatter/parser data identity: a durable node never accepts an approximate runtime. */
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
                profile?.let { expect(printable(it), "$path.profile") { "is not 1..128 printable ASCII characters" } }
                engine?.let { expect(printable(it), "$path.engine") { "is not 1..128 printable ASCII characters" } }
                icuClDrTzdbIdentity?.let {
                    expect(printable(it), "$path.icu-cl-dr-tzdb-identity") { "is not 1..128 printable ASCII characters" }
                }
            }

        internal fun identity(): CatalogRuntimeIdentity =
            CatalogRuntimeIdentity(checkNotNull(profile), checkNotNull(engine), checkNotNull(icuClDrTzdbIdentity))

        private fun printable(value: String): Boolean =
            value.isNotEmpty() && value.length <= 128 && value.all { character -> character.code in 0x21..0x7e }
    }
}

/** Validated enabled configuration separated from Spring binding and usable by low-level composition. */
public data class I18nPersistenceSettings(
    public val runtimeIdentity: CatalogRuntimeIdentity,
    public val limits: CatalogPersistenceLimits,
) {
    public companion object {
        public fun of(properties: I18nPersistenceProperties): I18nPersistenceSettings {
            val found = properties.problems()
            if (found.isNotEmpty()) throw ConfigurationProblemsException(found)
            check(properties.enabled) { "${I18nPersistenceProperties.ENABLED} is false; no i18n persistence settings exist" }
            return I18nPersistenceSettings(
                checkNotNull(properties.runtime).identity(),
                CatalogPersistenceLimits(
                    checkNotNull(properties.maxRetained),
                    checkNotNull(properties.maxPins),
                    checkNotNull(properties.maxPinLifetime),
                    checkNotNull(properties.changeFeedBatch),
                ),
            )
        }
    }
}

/** Declares the optional persistence subsection to Rain's pre-bean configuration validator. */
public class I18nPersistenceConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec(I18nPersistenceProperties.PREFIX, I18nPersistenceProperties::class, Presence.OPTIONAL) { properties, _ ->
                properties.problems()
            },
        )
}
