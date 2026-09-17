package com.gd.rain.i18n.jobs

import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogController
import com.gd.rain.i18n.CatalogControllerSpec
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogOverlay
import com.gd.rain.i18n.CatalogOverlayRef
import com.gd.rain.i18n.CatalogPinResult
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.DeferredMessage
import com.gd.rain.i18n.I18nObservation
import com.gd.rain.i18n.I18nObservationOperation
import com.gd.rain.i18n.I18nObservationOutcome
import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageArguments
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.bind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZoneId

class I18nDeliveryViewsTest {
    private val en: LocaleTag = LocaleTag.parse("en")

    @Test
    fun `current and pinned delivery semantics are intentionally different`() {
        val first = catalog("one", "first")
        val second = catalog("two", "second")
        val message = bind(first)
        val repository = MemoryRepository(second, mapOf(first.reference to first, second.reference to second))
        val views = I18nDeliveryViews(repository)

        val current = views.resolve(intent(message, I18nDeliverySelection.CurrentAtRender)) as I18nDeliveryView.Resolved
        val pinned =
            views.resolve(intent(message, I18nDeliverySelection.PinnedSnapshot(first.reference))) as I18nDeliveryView.Resolved

        assertThat(current.view.render(message).text).isEqualTo("two")
        assertThat(pinned.view.render(message).text).isEqualTo("one")
    }

    @Test
    fun `a missing pinned snapshot is closed failure rather than fallback to current`() {
        val first = catalog("one", "first")
        val current = catalog("two", "current")
        val missing =
            I18nDeliveryViews(MemoryRepository(current, emptyMap())).resolve(
                intent(bind(first), I18nDeliverySelection.PinnedSnapshot(first.reference)),
            )

        assertThat(missing).isEqualTo(I18nDeliveryView.SnapshotUnavailable(first.reference))
    }

    @Test
    fun `an optional observer sees a job view resolution and render without changing pinned selection`() {
        val snapshot = catalog("one", "first")
        val observed = mutableListOf<I18nObservation>()
        val views = I18nDeliveryViews(MemoryRepository(snapshot, mapOf(snapshot.reference to snapshot)), I18nObserver(observed::add))

        val resolved = views.resolve(intent(bind(snapshot), I18nDeliverySelection.PinnedSnapshot(snapshot.reference))) as I18nDeliveryView.Resolved
        assertThat(resolved.view.render(bind(snapshot)).text).isEqualTo("one")

        assertThat(observed.map { event -> event.operation to event.outcome }).containsExactly(
            I18nObservationOperation.LOCALE_RESOLUTION to I18nObservationOutcome.RESOLVED,
            I18nObservationOperation.RENDER to I18nObservationOutcome.RENDERED,
        )
    }

    @Test
    fun `pin factory leases precisely the selected pinned catalog and never upgrades a current intent`() {
        val snapshot = catalog("one", "first")
        val pins = RecordingPins(snapshot)
        val factory = I18nPinnedDeliveryFactory(pins)

        val current = factory.pin(intent(bind(snapshot), I18nDeliverySelection.CurrentAtRender), Duration.ofHours(1))
        val pinned = factory.pin(intent(bind(snapshot), I18nDeliverySelection.PinnedSnapshot(snapshot.reference)), Duration.ofHours(1))

        assertThat(current).isEqualTo(I18nPinnedDeliveryResult.NotPinned)
        assertThat(pins.requested).containsExactly(snapshot.reference)
        assertThat(pinned).isInstanceOf(I18nPinnedDeliveryResult.Pinned::class.java)
    }

    private fun intent(
        message: DeferredMessage,
        selection: I18nDeliverySelection,
    ): I18nDeliveryIntent = I18nDeliveryIntent(message, I18nRecipientPresentation(en, ZoneId.of("UTC")), selection)

    private fun bind(snapshot: CatalogSnapshot): DeferredMessage =
        snapshot.bind(checkNotNull(snapshot.contract(MessageKey("jobs", "message"))), MessageArguments.build {})

    private fun catalog(
        text: String,
        revision: String,
    ): CatalogSnapshot =
        (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity(revision, icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages = listOf(MessageSpec(MessageKey("jobs", "message"), 1, text, "job fixture")),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot

    private class MemoryRepository(
        private val current: CatalogSnapshot,
        private val snapshots: Map<CatalogRef, CatalogSnapshot>,
        private val overlays: Map<CatalogOverlayRef, CatalogOverlay> = emptyMap(),
    ) : I18nSnapshotRepository {
        override fun current(): CatalogSnapshot = current

        override fun snapshot(reference: CatalogRef): CatalogSnapshot? = snapshots[reference]

        override fun overlay(reference: CatalogOverlayRef): CatalogOverlay? = overlays[reference]
    }

    private class RecordingPins(
        snapshot: CatalogSnapshot,
    ) : I18nDeliveryPins {
        private val controller = CatalogController(CatalogControllerSpec(snapshot))
        val requested: MutableList<CatalogRef> = mutableListOf()

        override fun pin(
            catalog: CatalogRef,
            lifetime: Duration,
        ): CatalogPinResult {
            requested += catalog
            return controller.pin(catalog, lifetime)
        }

        override fun release(pin: com.gd.rain.i18n.CatalogPin): Boolean = controller.release(pin)
    }
}
