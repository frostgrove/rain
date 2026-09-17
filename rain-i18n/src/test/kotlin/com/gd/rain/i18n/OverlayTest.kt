package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.ZoneId

class OverlayTest {
    private val en: LocaleTag = LocaleTag.parse("en")
    private val ru: LocaleTag = LocaleTag.parse("ru")

    @Test
    fun `an approved application whole-message overlay wins, records provenance, and changes cache identity`() {
        val snapshot = catalog(OverridePolicy.APPLICATION)
        val contract = checkNotNull(snapshot.contract(MessageKey("app", "title")))
        val overlay = overlay(snapshot, OverlayLayer.APPLICATION, "Приложение")
        val base =
            snapshot
                .view(
                    ViewSpec(resolution(snapshot), ZoneId.of("UTC")),
                ).render(snapshot.bind(contract, MessageArguments.build {}))
        val view = snapshot.view(ViewSpec(resolution(snapshot), ZoneId.of("UTC"), overlays = listOf(overlay)))

        val rendered = view.render(snapshot.bind(contract, MessageArguments.build {}))

        assertThat(rendered.text).isEqualTo("Приложение")
        assertThat(rendered.winningLayer).isEqualTo(RenderLayer.APPLICATION)
        assertThat(rendered.overlay).isEqualTo(overlay.reference)
        assertThat(view.explain(snapshot.bind(contract, MessageArguments.build {})).winningLayer).isEqualTo(RenderLayer.APPLICATION)
        assertThat(rendered.renderKey).isNotEqualTo(base.renderKey)
    }

    @Test
    fun `an overlay refuses a foreign base, stale review, or a layer the descriptor does not permit`() {
        val snapshot = catalog(OverridePolicy.APPLICATION)
        val record = checkNotNull(snapshot.message(MessageKey("app", "title")))
        val stale =
            OverlayTranslationSpec(
                record.spec.key,
                ru,
                "Устарело",
                record.contract,
                record.sourceDigest,
                CatalogDigests.review(record.sourceDigest, ru, "другой текст"),
            )
        val candidate =
            CatalogOverlaySpec(
                CatalogRef("foreign", Digest.sha256(byteArrayOf(1))),
                OverlayLayer.TENANT,
                "candidate",
                listOf(stale),
            )

        val result = CatalogOverlayCompiler.compile(snapshot, candidate)

        assertThat(result).isInstanceOf(CatalogOverlayCompilation.Refused::class.java)
        assertThat((result as CatalogOverlayCompilation.Refused).problems.map(CatalogOverlayProblem::path))
            .contains("base", "entries[0].key", "entries[0].reviewDigest")
    }

    @Test
    fun `a view refuses two overlays of one layer even when both candidates are valid`() {
        val snapshot = catalog(OverridePolicy.APPLICATION)
        val first = overlay(snapshot, OverlayLayer.APPLICATION, "Первый", "one")
        val second = overlay(snapshot, OverlayLayer.APPLICATION, "Второй", "two")

        assertThatThrownBy {
            snapshot.view(ViewSpec(resolution(snapshot), ZoneId.of("UTC"), overlays = listOf(first, second)))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun catalog(policy: OverridePolicy): CatalogSnapshot {
        val source = "Base"
        val sourceDigest = CatalogDigests.source(Mf2Profile.ID, en, source, "title")
        return (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("base", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en, ru), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("app", "title"),
                                1,
                                source,
                                "title",
                                overridePolicy = policy,
                                translations =
                                    listOf(
                                        TranslationSpec(
                                            ru,
                                            "Основа",
                                            TranslationReview.APPROVED,
                                            sourceDigest,
                                            CatalogDigests.review(sourceDigest, ru, "Основа"),
                                        ),
                                    ),
                            ),
                        ),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot
    }

    private fun overlay(
        snapshot: CatalogSnapshot,
        layer: OverlayLayer,
        text: String,
        revision: String = "overlay",
    ): CatalogOverlay {
        val record = checkNotNull(snapshot.message(MessageKey("app", "title")))
        val candidate =
            CatalogOverlaySpec(
                snapshot.reference,
                layer,
                revision,
                listOf(
                    OverlayTranslationSpec(
                        record.spec.key,
                        ru,
                        text,
                        record.contract,
                        record.sourceDigest,
                        CatalogDigests.review(record.sourceDigest, ru, text),
                    ),
                ),
            )
        return (CatalogOverlayCompiler.compile(snapshot, candidate) as CatalogOverlayCompilation.Compiled).overlay
    }

    private fun resolution(snapshot: CatalogSnapshot): LocaleResolution.Resolved =
        snapshot.resolve(listOf(LocaleChoice(LocaleSource.APPLICATION, ru))) as LocaleResolution.Resolved
}
