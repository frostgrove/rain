package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

class I18nObserverTest {
    @Test
    fun `observer sees low-cardinality resolution and render events but cannot affect the result`() {
        val snapshot =
            (
                CatalogCompiler.compile(
                    CatalogSpec(
                        CatalogIdentity("observer", icuClDrTzdbIdentity = "icu4j-78.3"),
                        LocaleTag.parse("en"),
                        LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                        defaultZone = ZoneId.of("UTC"),
                        messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, "Ready", "ready")),
                    ),
                ) as CatalogCompilation.Compiled
            ).snapshot
        val observed = mutableListOf<I18nObservation>()
        val observer = I18nObserver { observed += it }

        val resolution = snapshot.resolve(listOf(LocaleChoice(LocaleSource.EXPLICIT, LocaleTag.parse("en"))), observer)
        val view = snapshot.view(ViewSpec(resolution as LocaleResolution.Resolved, ZoneId.of("UTC"), observer = observer))
        val contract = checkNotNull(snapshot.contract(MessageKey("app", "ready")))

        assertThat(view.render(snapshot.bind(contract, MessageArguments.of())).text).isEqualTo("Ready")
        assertThat(observed)
            .containsExactly(
                I18nObservation(I18nObservationOperation.LOCALE_RESOLUTION, I18nObservationOutcome.RESOLVED, LocaleResolutionReason.EXACT),
                I18nObservation(
                    I18nObservationOperation.RENDER,
                    I18nObservationOutcome.RENDERED,
                    LocaleResolutionReason.EXACT,
                    RenderLayer.CATALOG,
                ),
            )
    }

    @Test
    fun `observer failure cannot turn a successful render into a transport failure`() {
        val snapshot =
            (
                CatalogCompiler.compile(
                    CatalogSpec(
                        CatalogIdentity("observer-failure", icuClDrTzdbIdentity = "icu4j-78.3"),
                        LocaleTag.parse("en"),
                        LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                        defaultZone = ZoneId.of("UTC"),
                        messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, "Ready", "ready")),
                    ),
                ) as CatalogCompilation.Compiled
            ).snapshot
        val resolution = snapshot.resolve(listOf(LocaleChoice(LocaleSource.EXPLICIT, LocaleTag.parse("en")))) as LocaleResolution.Resolved
        val view = snapshot.view(ViewSpec(resolution, ZoneId.of("UTC"), observer = I18nObserver { error("observer") }))
        val contract = checkNotNull(snapshot.contract(MessageKey("app", "ready")))

        assertThat(view.render(snapshot.bind(contract, MessageArguments.of())).text).isEqualTo("Ready")
    }
}
