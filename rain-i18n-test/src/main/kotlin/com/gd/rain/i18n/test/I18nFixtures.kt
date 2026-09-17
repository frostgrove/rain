package com.gd.rain.i18n.test

import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.DeferredMessage
import com.gd.rain.i18n.I18nView
import com.gd.rain.i18n.LocaleChoice
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleResolution
import com.gd.rain.i18n.LocaleSource
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageArguments
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.Presentation
import com.gd.rain.i18n.ViewSpec
import com.gd.rain.i18n.bind
import com.gd.rain.i18n.view
import java.time.ZoneId

/** One explicit test declaration; it has no ambient module discovery or default locale input. */
public data class FixtureMessage(
    public val key: MessageKey,
    public val source: String,
    public val description: String = "test fixture",
)

/** Pure deterministic fixtures shared by kernel, adapter and application tests. */
public object I18nFixtures {
    public val EN: LocaleTag = LocaleTag.parse("en")

    /** Compiles a finite fixture catalog with stated runtime identity, locales and UTC-default zone. */
    public fun snapshot(
        revision: String = "test-catalog",
        messages: List<FixtureMessage> = listOf(FixtureMessage(MessageKey("test", "message"), "Test message")),
        sourceLocale: LocaleTag = EN,
        supported: Set<LocaleTag> = setOf(sourceLocale),
        defaultLocale: LocaleTag = sourceLocale,
        defaultZone: ZoneId = ZoneId.of("UTC"),
        icuClDrTzdbIdentity: String = "icu4j-78.3",
    ): CatalogSnapshot {
        require(messages.isNotEmpty()) { "a fixture catalog has at least one message" }
        val compilation =
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity(revision, icuClDrTzdbIdentity = icuClDrTzdbIdentity),
                    sourceLocale,
                    LocalePolicy(supported, defaultLocale),
                    defaultZone = defaultZone,
                    messages = messages.map { message -> MessageSpec(message.key, 1, message.source, message.description) },
                ),
            )
        return when (compilation) {
            is CatalogCompilation.Compiled -> compilation.snapshot

            is CatalogCompilation.Refused -> throw AssertionError(
                compilation.problems.joinToString { problem ->
                    "${problem.path}: ${problem.message}"
                },
            )
        }
    }

    /** Creates a view through the normal locale resolver so tests retain real resolver semantics. */
    public fun view(
        snapshot: CatalogSnapshot,
        locale: LocaleTag = snapshot.sourceLocale,
        zone: ZoneId = snapshot.defaultZone,
        presentation: Presentation = Presentation.AUTOMATIC_ISOLATION,
    ): I18nView {
        val resolution = snapshot.resolve(listOf(LocaleChoice(LocaleSource.APPLICATION, locale)))
        check(resolution is LocaleResolution.Resolved && resolution.locale == locale) {
            "fixture locale ${locale.value} is not exactly supported by this snapshot"
        }
        return snapshot.view(ViewSpec(resolution, zone, presentation))
    }

    /** Binds through the same manual typed low-level API exposed to dynamic production integrations. */
    public fun message(
        snapshot: CatalogSnapshot,
        key: MessageKey = MessageKey("test", "message"),
        arguments: MessageArguments = MessageArguments.of(),
    ): DeferredMessage = snapshot.bind(checkNotNull(snapshot.contract(key)) { "fixture key ${key.value} is absent" }, arguments)
}
