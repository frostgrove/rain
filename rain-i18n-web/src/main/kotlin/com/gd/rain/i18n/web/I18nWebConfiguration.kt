package com.gd.rain.i18n.web

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Optional servlet locale-input policy.
 *
 * Supplying a source name opts the source in; absence preserves protocol-only negotiation. The
 * optional executor decorator is a named application-selected bridge for legacy Spring locale
 * consumers, never a global executor post-processor or a way to transport a mutable i18n runtime.
 */
@ConfigurationProperties(I18nWebProperties.PREFIX)
public data class I18nWebProperties(
    public val enabled: Boolean = true,
    public val querySource: String? = null,
    public val cookieSource: String? = null,
    public val propagateExecutors: Boolean = false,
) {
    public fun problems(): List<ConfigurationProblem> =
        problems {
            querySource?.let { source ->
                expect(SOURCE_NAME.matches(source), QUERY_SOURCE) { "is not a 1..64 character locale input name" }
            }
            cookieSource?.let { source ->
                expect(SOURCE_NAME.matches(source), COOKIE_SOURCE) { "is not a 1..64 character locale input name" }
            }
            if (!enabled) {
                expect(querySource == null && cookieSource == null && !propagateExecutors, PREFIX, ProblemCode.CONTRADICTS) {
                    "locale input or executor settings are present while $ENABLED is false"
                }
            }
        }

    public fun settings(): I18nWebSettings {
        val found = problems()
        if (found.isNotEmpty()) throw ConfigurationProblemsException(found)
        check(enabled) { "$ENABLED is false; there are no i18n servlet settings" }
        return I18nWebSettings(querySource, cookieSource, propagateExecutors)
    }

    public companion object {
        public const val PREFIX: String = "rain.i18n.web"
        public const val ENABLED: String = "$PREFIX.enabled"
        public const val QUERY_SOURCE: String = "$PREFIX.query-source"
        public const val COOKIE_SOURCE: String = "$PREFIX.cookie-source"
        public const val PROPAGATE_EXECUTORS: String = "$PREFIX.propagate-executors"

        internal val SOURCE_NAME: Regex = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    }
}

/** Validated servlet policy, separate from Spring binding and reusable by explicit composition. */
public data class I18nWebSettings(
    public val querySource: String?,
    public val cookieSource: String?,
    public val propagateExecutors: Boolean,
)

/** Declares the optional servlet policy to the pre-bean Rain configuration validator. */
public class I18nWebConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec(I18nWebProperties.PREFIX, I18nWebProperties::class, Presence.OPTIONAL) { properties, _ ->
                properties.problems()
            },
        )
}
