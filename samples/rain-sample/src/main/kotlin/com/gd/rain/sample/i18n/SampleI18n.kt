package com.gd.rain.sample.i18n

import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.i18n.ArgumentSpec
import com.gd.rain.i18n.ArgumentType
import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageArguments
import com.gd.rain.i18n.MessageDefinition
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.Mf2Profile
import com.gd.rain.i18n.OutputKind
import com.gd.rain.i18n.RichPart
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec
import com.gd.rain.i18n.definition
import com.gd.rain.i18n.web.I18nRuntime
import com.gd.rain.web.route.Access
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId

/** A static catalog is the sample's explicit low-level bootstrap; products may replace this provider with durable release authority. */
@Configuration(proxyBeanMethods = false)
class SampleI18nConfiguration {
    @Bean
    fun sampleCatalogSnapshotProvider(): CatalogSnapshotProvider {
        val snapshot = sampleCatalog()
        return CatalogSnapshotProvider { snapshot }
    }

    @Bean
    fun sampleI18nMessages(catalogs: CatalogSnapshotProvider): SampleI18nMessages = SampleI18nMessages(catalogs.current())
}

/** Shows the magic-first servlet path while retaining the exact low-level snapshot/message seams in one visible place. */
@RestController
@RequestMapping("/v1/i18n")
@ConditionalOnRainRole(RuntimeRole.API)
class SampleI18nController(
    private val i18n: I18nRuntime,
    private val messages: SampleI18nMessages,
) {
    @GetMapping("/preview")
    @Access(authenticated = true, why = "the preview is part of the signed-in helpdesk surface")
    fun preview(): SampleI18nPreview {
        val rendered = i18n.render(messages.openTickets(12))
        return SampleI18nPreview(rendered.text, rendered.templateLocale.value, rendered.parts)
    }
}

data class SampleI18nPreview(
    val text: String,
    val templateLocale: String,
    val parts: List<RichPart>,
)

private val KEY: MessageKey = MessageKey("helpdesk", "open_tickets")

/** The sample's low-level typed escape hatch; the servlet controller only needs the magic [I18nRuntime]. */
class SampleI18nMessages(
    snapshot: CatalogSnapshot,
) {
    private val openTicketsDefinition: MessageDefinition<OpenTickets> =
        snapshot.definition(
            checkNotNull(snapshot.contract(KEY)) { "the sample catalog declares $KEY" },
        ) { arguments ->
            MessageArguments.build { integer("open", arguments.open) }
        }

    fun openTickets(open: Long) = openTicketsDefinition.bind(OpenTickets(open))
}

data class OpenTickets(
    val open: Long,
)

private fun sampleCatalog(): CatalogSnapshot {
    val en = LocaleTag.parse("en")
    val source = "{#strong}Open tickets{/strong}: {${'$'}open :number}"
    val description = "Count shown in the signed-in helpdesk localization preview"
    val sourceDigest = CatalogDigests.source(Mf2Profile.ID, en, source, description)

    fun translation(
        locale: String,
        text: String,
    ): TranslationSpec {
        val tag = LocaleTag.parse(locale)
        return TranslationSpec(tag, text, TranslationReview.APPROVED, sourceDigest, CatalogDigests.review(sourceDigest, tag, text))
    }
    val compilation =
        CatalogCompiler.compile(
            CatalogSpec(
                CatalogIdentity("sample-i18n-1", icuClDrTzdbIdentity = "icu4j-78.3"),
                en,
                LocalePolicy(setOf(en, LocaleTag.parse("ru"), LocaleTag.parse("kk")), en),
                defaultZone = ZoneId.of("Asia/Almaty"),
                messages =
                    listOf(
                        MessageSpec(
                            KEY,
                            1,
                            source,
                            description,
                            arguments = listOf(ArgumentSpec("open", ArgumentType.INTEGER)),
                            output = OutputKind.RICH,
                            markup = setOf("strong"),
                            translations =
                                listOf(
                                    translation("ru", "{#strong}Открытые заявки{/strong}: {${'$'}open :number}"),
                                    translation("kk", "{#strong}Ашық өтінімдер{/strong}: {${'$'}open :number}"),
                                ),
                        ),
                    ),
            ),
        )
    return (compilation as? CatalogCompilation.Compiled)?.snapshot
        ?: error("the sample's static i18n catalog is invalid: ${(compilation as CatalogCompilation.Refused).problems}")
}
