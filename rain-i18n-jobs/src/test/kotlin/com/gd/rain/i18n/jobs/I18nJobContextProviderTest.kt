package com.gd.rain.i18n.jobs

import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.DeferredMessage
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
import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.context.DurableJobContextCapture
import com.gd.rain.jobs.context.DurableJobContextRequest
import com.gd.rain.jobs.context.DurableJobContextTerminalRequest
import com.gd.rain.jobs.context.JobPayloadDigest
import com.gd.rain.jobs.context.TenantBindingMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class I18nJobContextProviderTest {
    private val locale = LocaleTag.parse("en")

    @Test
    fun `provider captures an explicit pinned context restores magic runtime and clears the worker`() {
        val first = snapshot("first", "one")
        val second = snapshot("second", "two")
        val repository = Repository(first, mapOf(first.reference to first, second.reference to second))
        val pins = RecordingDurablePins()
        val provider = I18nJobContextProvider(I18nDeliveryViews(repository), pins, I18nDurablePinPolicy(java.time.Duration.ofDays(7)))
        val message = bind(first)
        val request = request()
        val captured =
            I18nJobContext.with(I18nJobContext.pinnedSnapshot(view(first))) {
                provider.capture(request)
            } as DurableJobContextCapture.Captured

        repository.current = second
        val binding = provider.restore(restore(captured.fragment, request))

        assertThat(I18nJobContext.current()).isNull()
        assertThat(
            I18nJobRuntime
                .require()
                .view
                .render(message)
                .text,
        ).isEqualTo("one")
        binding.close()
        assertThat(I18nJobRuntime.current()).isNull()
        assertThat(pins.acquired).containsExactly(first.reference)
    }

    @Test
    fun `current-at-render context deliberately observes the later catalog rather than a captured snapshot`() {
        val first = snapshot("first", "one")
        val second = snapshot("second", "two")
        val repository = Repository(first, mapOf(first.reference to first, second.reference to second))
        val provider = I18nJobContextProvider(I18nDeliveryViews(repository))
        val captured =
            I18nJobContext.with(I18nJobContext.currentAtRender(view(first))) {
                provider.capture(request())
            } as DurableJobContextCapture.Captured

        repository.current = second
        provider.restore(restore(captured.fragment)).use {
            assertThat(
                I18nJobRuntime
                    .require()
                    .view
                    .render(bind(second))
                    .text,
            ).isEqualTo("two")
        }
        assertThat(I18nJobRuntime.current()).isNull()
    }

    @Test
    fun `pinned context acquires inside capture and releases only when jobs reports a terminal receipt`() {
        val snapshot = snapshot("pinned", "one")
        val pins = RecordingDurablePins()
        val provider =
            I18nJobContextProvider(
                I18nDeliveryViews(Repository(snapshot, mapOf(snapshot.reference to snapshot))),
                pins,
                I18nDurablePinPolicy(java.time.Duration.ofDays(7)),
            )
        val request = request()
        val captured =
            I18nJobContext.with(I18nJobContext.pinnedSnapshot(view(snapshot))) {
                provider.capture(request)
            } as DurableJobContextCapture.Captured

        assertThat(pins.released).isEmpty()
        provider.onTerminal(DurableJobContextTerminalRequest(request, captured.fragment, JobState.SUCCEEDED))

        assertThat(pins.released).containsExactlyElementsOf(pins.pins)
    }

    @Test
    fun `pin sweep work uses an explicit clock and bounded policy`() {
        val pins = RecordingDurablePins()
        val now = Instant.parse("2026-09-17T00:00:00Z")

        I18nDurablePinSweepWork(
            pins,
            I18nDurablePinSweepPolicy(java.time.Duration.ofMinutes(5), 40),
            Clock.fixed(now, ZoneId.of("UTC")),
        ).run()

        assertThat(pins.sweeps).containsExactly(I18nDurablePinSweepRequest(now, 40))
    }

    @Test
    fun `fragment decoder refuses trailing or malformed bytes before it can install runtime state`() {
        assertThatIllegalArgumentException()
            .isThrownBy { I18nJobContextCodec.decode(byteArrayOf(1, 2, 3)) }
            .withMessage("a durable i18n job context is malformed")
    }

    private fun request(): DurableJobContextRequest =
        DurableJobContextRequest(
            DurableJobContextRequest.JOBS_NAMESPACE,
            "email.deliver",
            UUID.randomUUID(),
            JobPayloadDigest.of("payload".toByteArray()),
            TenantBindingMode.INHERIT,
        )

    private fun restore(
        fragment: com.gd.rain.jobs.context.DurableJobContextFragment,
        request: DurableJobContextRequest = request(),
    ) = com.gd.rain.jobs.context
        .DurableJobContextRestoreRequest(request, fragment, null)

    private fun view(snapshot: CatalogSnapshot) =
        snapshot.view(
            ViewSpec(
                snapshot.resolve(listOf(LocaleChoice(LocaleSource.APPLICATION, locale))) as LocaleResolution.Resolved,
                ZoneId.of("UTC"),
                Presentation.AUTOMATIC_ISOLATION,
            ),
        )

    private fun bind(snapshot: CatalogSnapshot): DeferredMessage =
        snapshot.bind(snapshot.contract(MessageKey("app", "message"))!!, MessageArguments.of())

    private fun snapshot(
        revision: String,
        text: String,
    ): CatalogSnapshot =
        (
            CatalogCompiler.compile(
                CatalogSpec(
                    identity = CatalogIdentity(revision, icuClDrTzdbIdentity = "icu4j-78.3"),
                    sourceLocale = locale,
                    localePolicy = LocalePolicy(setOf(locale), locale),
                    defaultZone = ZoneId.of("UTC"),
                    messages = listOf(MessageSpec(MessageKey("app", "message"), 1, text, "job context fixture")),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot

    private class Repository(
        initial: CatalogSnapshot,
        private val snapshots: Map<CatalogRef, CatalogSnapshot>,
    ) : I18nSnapshotRepository {
        var current: CatalogSnapshot = initial

        override fun current(): CatalogSnapshot = current

        override fun snapshot(reference: CatalogRef): CatalogSnapshot? = snapshots[reference]

        override fun overlay(reference: com.gd.rain.i18n.CatalogOverlayRef): com.gd.rain.i18n.CatalogOverlay? = null
    }

    private class RecordingDurablePins : I18nDurableDeliveryPins {
        val acquired: MutableList<CatalogRef> = mutableListOf()
        val pins: MutableList<I18nDurableDeliveryPin> = mutableListOf()
        val released: MutableList<I18nDurableDeliveryPin> = mutableListOf()
        val sweeps: MutableList<I18nDurablePinSweepRequest> = mutableListOf()

        override fun acquire(request: I18nDurableDeliveryPinRequest): I18nDurableDeliveryPinResult {
            acquired += request.catalog
            return I18nDurableDeliveryPin(
                UUID.randomUUID(),
                request.invocation,
                request.catalog,
                java.time.Instant.EPOCH
                    .plus(request.retention),
            ).also(pins::add)
                .let(I18nDurableDeliveryPinResult::Pinned)
        }

        override fun release(pin: I18nDurableDeliveryPin): Boolean {
            released += pin
            return true
        }

        override fun sweep(request: I18nDurablePinSweepRequest): Int {
            sweeps += request
            return 0
        }
    }
}
