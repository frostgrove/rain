package com.gd.rain.tenancy.i18n

import com.gd.rain.i18n.CatalogOverlay
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.I18nView
import com.gd.rain.i18n.LocaleChoice
import com.gd.rain.i18n.LocaleResolution
import com.gd.rain.i18n.LocaleSource
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.OverlayLayer
import com.gd.rain.i18n.Presentation
import com.gd.rain.i18n.ViewSpec
import com.gd.rain.i18n.view
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantRuntime
import com.gd.rain.tenancy.TenantRuntimeLease
import com.gd.rain.tenancy.TenantRuntimeTask
import com.gd.rain.tenancy.TenantScope
import java.time.ZoneId

/**
 * Tenant-owned presentation settings returned only for an already minted authority scope.
 *
 * The provider deliberately cannot receive or return a raw tenant id. An implementation may load
 * settings or a reviewed overlay from its tenant-owned store, but it cannot affect resolution,
 * lifecycle admission, routing, or the global Spring locale.
 */
public data class TenantI18nPresentation(
    public val defaultLocale: LocaleTag? = null,
    public val defaultZone: ZoneId? = null,
    public val overlay: CatalogOverlay? = null,
) {
    init {
        require(overlay?.layer != OverlayLayer.APPLICATION) { "tenant presentation cannot install an application overlay" }
    }
}

/** The one tenant-scoped policy lookup between tenancy and the i18n bounded context. */
public fun interface TenantI18nPresentationProvider {
    public fun presentation(
        scope: TenantScope,
        snapshot: CatalogSnapshot,
    ): TenantI18nPresentation
}

/**
 * Magic-first tenant i18n state. It is installed only while a TenantRuntime task is active; the
 * exact [view] is also exposed for a direct low-level render path.
 */
public class TenantI18nRuntime internal constructor(
    public val view: I18nView,
    public val epoch: TenantEpoch,
    public val catalog: CatalogRef,
    public val overlay: CatalogOverlay?,
) {
    public companion object {
        private val current: ThreadLocal<TenantI18nRuntime?> = ThreadLocal.withInitial { null }

        /** Returns the runtime entered by [TenantI18nRuntimeTask], never a fabricated tenant view. */
        public fun current(): TenantI18nRuntime = checkNotNull(current.get()) { "no tenant i18n runtime is active" }

        internal fun install(runtime: TenantI18nRuntime?): TenantI18nRuntime? {
            val previous = current.get()
            if (runtime == null) current.remove() else current.set(runtime)
            return previous
        }
    }
}

/**
 * Tenant runtime bridge. It runs after tenant authority admission and before service code, creates
 * one explicit view, and restores its scoped magic state in the paired lease.
 */
public class TenantI18nRuntimeTask(
    private val catalogs: CatalogSnapshotProvider,
    private val presentations: TenantI18nPresentationProvider,
    private val observer: I18nObserver = I18nObserver.NONE,
) : TenantRuntimeTask {
    override val id: String = "i18n"

    override fun enter(runtime: TenantRuntime): TenantRuntimeLease {
        val snapshot = catalogs.current()
        val presentation = presentations.presentation(runtime.scope, snapshot)
        val resolution = resolve(snapshot, presentation)
        val overlay = presentation.overlay
        require(overlay == null || overlay.base == snapshot.reference) { "tenant overlay belongs to another catalog snapshot" }
        val view =
            snapshot.view(
                ViewSpec(
                    resolution,
                    presentation.defaultZone ?: snapshot.defaultZone,
                    Presentation.AUTOMATIC_ISOLATION,
                    listOfNotNull(overlay),
                    observer,
                ),
            )
        val installed = TenantI18nRuntime(view, runtime.scope.epoch, snapshot.reference, overlay)
        val previous = TenantI18nRuntime.install(installed)
        return TenantRuntimeLease { TenantI18nRuntime.install(previous) }
    }

    private fun resolve(
        snapshot: CatalogSnapshot,
        presentation: TenantI18nPresentation,
    ): LocaleResolution.Resolved {
        val choices = presentation.defaultLocale?.let { listOf(LocaleChoice(LocaleSource.TENANT, it)) } ?: emptyList()
        return snapshot.resolve(choices, observer) as? LocaleResolution.Resolved
            ?: throw IllegalStateException("tenant locale is not acceptable under the catalog policy")
    }
}
