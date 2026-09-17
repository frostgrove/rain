package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocaleResolverTest {
    private val en = LocaleTag.parse("en")
    private val enGb = LocaleTag.parse("en-GB")
    private val fr = LocaleTag.parse("fr")
    private val zhHant = LocaleTag.parse("zh-Hant")

    private val resolver =
        LocaleResolver(
            LocalePolicy(
                supported = setOf(en, enGb, fr, zhHant),
                defaultLocale = en,
                parents = mapOf(enGb to en),
            ),
        )

    @Test
    fun `canonicalizes bcp47 without reading a default locale`() {
        assertThat(LocaleTag.parse("ZH-hant").value).isEqualTo("zh-Hant")
    }

    @Test
    fun `uses ordered candidates then structural lookup`() {
        val answer = resolver.resolve(listOf(LocaleChoice(LocaleSource.USER, LocaleTag.parse("en-GB-oxendict"))))

        assertThat(answer)
            .isEqualTo(LocaleResolution.Resolved(enGb, LocaleTag.parse("en-GB-oxendict"), LocaleSource.USER, LocaleResolutionReason.LOOKUP))
    }

    @Test
    fun `does not expand generic language to arbitrary script`() {
        val answer = resolver.resolve(listOf(LocaleChoice(LocaleSource.USER, LocaleTag.parse("zh"))))

        assertThat(answer)
            .isEqualTo(LocaleResolution.Resolved(en, null, LocaleSource.APPLICATION, LocaleResolutionReason.POLICY_DEFAULT))
    }

    @Test
    fun `accept language honors quality exclusions and repeated headers`() {
        val answer = resolver.resolveAcceptLanguage(listOf("fr;q=0, en-GB;q=0.7", "en;q=0.8"))

        assertThat(answer).isEqualTo(LocaleResolution.Resolved(en, en, LocaleSource.PROTOCOL, LocaleResolutionReason.EXACT))
    }

    @Test
    fun `wildcard resolves a declared locale and malformed input refuses`() {
        assertThat(resolver.resolveAcceptLanguage(listOf("*;q=0.5")))
            .isEqualTo(LocaleResolution.Resolved(en, null, LocaleSource.PROTOCOL, LocaleResolutionReason.WILDCARD))
        assertThat(resolver.resolveAcceptLanguage(listOf("en;q=1.0000")))
            .isEqualTo(LocaleResolution.Refused(LocaleResolutionReason.MALFORMED))
        assertThat(resolver.resolveAcceptLanguage(listOf("en;q=1;q=0")))
            .isEqualTo(LocaleResolution.Refused(LocaleResolutionReason.MALFORMED))
    }

    @Test
    fun `q zero exclusion blocks matching child locales and the policy default`() {
        val enUs = LocaleTag.parse("en-US")
        val configured = LocaleResolver(LocalePolicy(setOf(en, enUs, fr), en))

        val broad = configured.resolveAcceptLanguage(listOf("en;q=0, en-US;q=1"))
        val specific = configured.resolveAcceptLanguage(listOf("en-US;q=0, en;q=1"))
        val wildcard = configured.resolveAcceptLanguage(listOf("*;q=0"))

        assertThat(broad).isEqualTo(LocaleResolution.Refused(LocaleResolutionReason.EXCLUDED))
        assertThat(specific).isEqualTo(LocaleResolution.Resolved(en, en, LocaleSource.PROTOCOL, LocaleResolutionReason.EXACT))
        assertThat(wildcard).isEqualTo(LocaleResolution.Refused(LocaleResolutionReason.EXCLUDED))
    }

    @Test
    fun `route can refuse a miss instead of falling back`() {
        val strict = LocaleResolver(resolver.policy.copy(defaultOnMiss = DefaultOnMiss.REFUSE))

        assertThat(strict.resolve(emptyList())).isEqualTo(LocaleResolution.Refused(LocaleResolutionReason.UNSUPPORTED))
    }
}
