package com.gd.rain.i18n.jobs

import com.gd.rain.i18n.CatalogOverlay
import com.gd.rain.i18n.CatalogOverlayRef
import com.gd.rain.i18n.CatalogPin
import com.gd.rain.i18n.CatalogPinResult
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.DeferredMessage
import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.I18nView
import com.gd.rain.i18n.LocaleChoice
import com.gd.rain.i18n.LocaleResolution
import com.gd.rain.i18n.LocaleSource
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.Presentation
import com.gd.rain.i18n.ViewSpec
import com.gd.rain.i18n.view
import com.gd.rain.jobs.RecurringWork
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The recipient presentation preference that crosses a durable boundary without an ambient locale. */
public data class I18nRecipientPresentation(
    public val locale: LocaleTag,
    public val zone: ZoneId,
    public val presentation: Presentation = Presentation.AUTOMATIC_ISOLATION,
)

/** Delivery chooses reproducible pinned wording or an explicitly non-reproducible current catalog. */
public sealed interface I18nDeliverySelection {
    public data object CurrentAtRender : I18nDeliverySelection

    public data class PinnedSnapshot(
        public val catalog: CatalogRef,
        public val overlays: List<CatalogOverlayRef> = emptyList(),
    ) : I18nDeliverySelection {
        init {
            require(overlays.map(CatalogOverlayRef::layer).distinct().size == overlays.size) {
                "a pinned delivery contains at most one overlay per layer"
            }
        }
    }
}

/** The presentation contract that can cross a jobs boundary independently of a particular message payload. */
public data class I18nJobDeliveryContext(
    public val recipient: I18nRecipientPresentation,
    public val selection: I18nDeliverySelection,
)

/** A job/outbox payload carries typed message intent and declared presentation, never rendered wording. */
public data class I18nDeliveryIntent(
    public val message: DeferredMessage,
    public val recipient: I18nRecipientPresentation,
    public val selection: I18nDeliverySelection,
) {
    public val context: I18nJobDeliveryContext get() = I18nJobDeliveryContext(recipient, selection)
}

/** The durable repository boundary; implementations must never substitute another release or overlay. */
public interface I18nSnapshotRepository {
    public fun current(): CatalogSnapshot

    public fun snapshot(reference: CatalogRef): CatalogSnapshot?

    public fun overlay(reference: CatalogOverlayRef): CatalogOverlay?
}

/** Durable enqueue code acquires this lease in the same transaction as the job/outbox receipt. */
public interface I18nDeliveryPins {
    public fun pin(
        catalog: CatalogRef,
        lifetime: Duration,
    ): CatalogPinResult

    public fun release(pin: CatalogPin): Boolean
}

/**
 * A durable, provider-owned lease identity stored with a jobs i18n fragment.
 *
 * Unlike the process-local [CatalogPin], this is reconstructible after a worker restart. Its
 * opaque id is safe to persist, while the exact catalog reference prevents a release from being
 * redirected to a newer release.
 */
public data class I18nDurableDeliveryPin(
    public val id: UUID,
    /** The jobs receipt that is the only authority allowed to release this pin. */
    public val invocation: UUID,
    public val catalog: CatalogRef,
    /** Retained so a restarted adapter can reconstruct an exact audited persistence release command. */
    public val expiresAt: Instant,
)

/** The explicit retention horizon that covers retry, dead-letter and redrive for pinned wording. */
public data class I18nDurablePinPolicy(
    public val retention: Duration,
) {
    init {
        require(retention > Duration.ZERO && retention <= Duration.ofDays(3650)) {
            "a durable i18n pin retention is positive and at most 3650 days"
        }
    }
}

/** A pin request is scoped to one jobs invocation and must join its caller-owned enqueue transaction. */
public data class I18nDurableDeliveryPinRequest(
    public val invocation: UUID,
    public val catalog: CatalogRef,
    public val retention: Duration,
)

/** The durable pin store never silently changes a pinned release into the current release. */
public sealed interface I18nDurableDeliveryPinResult {
    public data class Pinned(
        public val pin: I18nDurableDeliveryPin,
    ) : I18nDurableDeliveryPinResult

    public data class Missing(
        public val catalog: CatalogRef,
    ) : I18nDurableDeliveryPinResult

    public data class Limit(
        public val reason: I18nDurableDeliveryPinLimitReason,
    ) : I18nDurableDeliveryPinResult
}

/** A durable pin authority exposes a finite, transport-safe capacity outcome. */
public enum class I18nDurableDeliveryPinLimitReason {
    PIN_COUNT,
    PIN_LIFETIME,
    STORAGE_CAPACITY,
}

/**
 * Low-level durable pin SDK for the independent `jobs ↔ i18n` adapter.
 *
 * `acquire` runs while the jobs enqueue transaction is active. `release` is called only after
 * jobs wrote a terminal receipt and must be idempotent. Implementations retain an expiry-based
 * sweep so a crash between those two durable transitions cannot make a pinned release removable.
 */
public interface I18nDurableDeliveryPins {
    public fun acquire(request: I18nDurableDeliveryPinRequest): I18nDurableDeliveryPinResult

    public fun release(pin: I18nDurableDeliveryPin): Boolean

    /** Bounded expiry cleanup for the crash window after a jobs terminal receipt. */
    public fun sweep(request: I18nDurablePinSweepRequest): Int
}

/** A bounded, explicit expiry-sweep request; it has no tenant, event, request or ambient state. */
public data class I18nDurablePinSweepRequest(
    public val expiredAtOrBefore: Instant,
    public val limit: Int,
) {
    init {
        require(limit in 1..10_000) { "a durable i18n pin sweep limit is 1..10000" }
    }
}

/** Scheduler policy is explicit because it must match the product's retry/redrive retention horizon. */
public data class I18nDurablePinSweepPolicy(
    public val interval: Duration,
    public val batchSize: Int,
) {
    init {
        require(interval >= Duration.ofSeconds(1) && interval <= Duration.ofDays(7)) {
            "a durable i18n pin sweep interval is 1 second..7 days"
        }
        require(batchSize in 1..10_000) { "a durable i18n pin sweep batch is 1..10000" }
    }
}

/**
 * Magic scheduler contribution for a durable pin authority.
 *
 * Jobs runs this work cluster-wide. The authority decides which expired pins can be released; it
 * must never remove a release that still has a live durable pin.
 */
public class I18nDurablePinSweepWork(
    private val pins: I18nDurableDeliveryPins,
    private val policy: I18nDurablePinSweepPolicy,
    private val clock: Clock,
) : RecurringWork {
    override val name: String = "rain-i18n-pin-sweep"
    override val interval: Duration = policy.interval

    override fun run() {
        pins.sweep(I18nDurablePinSweepRequest(clock.instant(), policy.batchSize))
    }
}

/** The exact release lease attached to a pinned delivery; release it only after a terminal receipt. */
public data class I18nPinnedDelivery(
    public val intent: I18nDeliveryIntent,
    public val pin: CatalogPin,
) {
    init {
        require(intent.selection is I18nDeliverySelection.PinnedSnapshot) {
            "only a pinned i18n delivery carries a catalog pin"
        }
        require(intent.selection.catalog == pin.reference) {
            "a pinned delivery and its catalog pin identify the same release"
        }
    }
}

/** Acquires the exact catalog lease before durable code writes the pinned intent. */
public class I18nPinnedDeliveryFactory(
    private val pins: I18nDeliveryPins,
) {
    public fun pin(
        intent: I18nDeliveryIntent,
        lifetime: Duration,
    ): I18nPinnedDeliveryResult {
        val selection =
            intent.selection as? I18nDeliverySelection.PinnedSnapshot
                ?: return I18nPinnedDeliveryResult.NotPinned
        return when (val result = pins.pin(selection.catalog, lifetime)) {
            is CatalogPinResult.Pinned -> I18nPinnedDeliveryResult.Pinned(I18nPinnedDelivery(intent, result.pin))
            is CatalogPinResult.Missing -> I18nPinnedDeliveryResult.Unavailable(result.reference)
            is CatalogPinResult.Limit -> I18nPinnedDeliveryResult.Limit(result)
        }
    }
}

/** Pin acquisition is closed so an enqueue transaction cannot accidentally downgrade its semantics. */
public sealed interface I18nPinnedDeliveryResult {
    public data class Pinned(
        public val delivery: I18nPinnedDelivery,
    ) : I18nPinnedDeliveryResult

    public data class Unavailable(
        public val catalog: CatalogRef,
    ) : I18nPinnedDeliveryResult

    public data class Limit(
        public val result: CatalogPinResult.Limit,
    ) : I18nPinnedDeliveryResult

    public data object NotPinned : I18nPinnedDeliveryResult
}

/** Resolving a delivery view is closed: a missing pinned release is not a request to use the current one. */
public sealed interface I18nDeliveryView {
    public data class Resolved(
        public val view: I18nView,
    ) : I18nDeliveryView

    public data class SnapshotUnavailable(
        public val catalog: CatalogRef,
        public val overlay: CatalogOverlayRef? = null,
    ) : I18nDeliveryView

    public data class LocaleUnavailable(
        public val locale: LocaleTag,
    ) : I18nDeliveryView
}

/** Builds the exact view used by a job handler immediately before it renders the deferred message. */
public class I18nDeliveryViews(
    private val snapshots: I18nSnapshotRepository,
    private val observer: I18nObserver = I18nObserver.NONE,
) {
    public fun resolve(intent: I18nDeliveryIntent): I18nDeliveryView = resolve(intent.context)

    /** Resolves a restored jobs context without inspecting or serializing the handler's message payload. */
    public fun resolve(context: I18nJobDeliveryContext): I18nDeliveryView {
        val snapshot =
            when (val selection = context.selection) {
                I18nDeliverySelection.CurrentAtRender -> {
                    snapshots.current()
                }

                is I18nDeliverySelection.PinnedSnapshot -> {
                    snapshots.snapshot(selection.catalog)
                        ?: return I18nDeliveryView.SnapshotUnavailable(selection.catalog)
                }
            }
        val overlays =
            when (val selection = context.selection) {
                I18nDeliverySelection.CurrentAtRender -> {
                    emptyList()
                }

                is I18nDeliverySelection.PinnedSnapshot -> {
                    selection.overlays.map { reference ->
                        snapshots
                            .overlay(reference)
                            ?.takeIf { it.base == snapshot.reference }
                            ?: return I18nDeliveryView.SnapshotUnavailable(snapshot.reference, reference)
                    }
                }
            }
        val resolution = snapshot.resolve(listOf(LocaleChoice(LocaleSource.APPLICATION, context.recipient.locale)), observer)
        if (resolution !is LocaleResolution.Resolved || resolution.locale != context.recipient.locale) {
            return I18nDeliveryView.LocaleUnavailable(context.recipient.locale)
        }
        return I18nDeliveryView.Resolved(
            snapshot.view(ViewSpec(resolution, context.recipient.zone, context.recipient.presentation, overlays, observer)),
        )
    }
}
