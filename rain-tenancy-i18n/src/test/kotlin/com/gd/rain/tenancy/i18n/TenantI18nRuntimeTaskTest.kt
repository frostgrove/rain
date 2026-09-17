package com.gd.rain.tenancy.i18n

import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogOverlayCompilation
import com.gd.rain.i18n.CatalogOverlayCompiler
import com.gd.rain.i18n.CatalogOverlaySpec
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.OverlayLayer
import com.gd.rain.i18n.OverlayTranslationSpec
import com.gd.rain.i18n.OverridePolicy
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantCandidate
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantRequestContext
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolver
import com.gd.rain.tenancy.TenantRuntimeManager
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId

class TenantI18nRuntimeTaskTest {
    @Test
    fun `tenant task builds one epoch-fenced overlay view and clears it after service code`() {
        val snapshot = catalog()
        val overlay = overlay(snapshot)
        val authority = authority()
        val manager =
            TenantRuntimeManager(
                authority,
                listOf(
                    TenantI18nRuntimeTask(
                        CatalogSnapshotProvider { snapshot },
                        TenantI18nPresentationProvider { _, _ ->
                            TenantI18nPresentation(LocaleTag.parse("ru"), ZoneId.of("Asia/Almaty"), overlay)
                        },
                    ),
                ),
            )
        val scope = authority.lookup(TenantRef.of("acme"), TenantOperation.READ)

        val runtime = manager.with(scope, TenantOperation.READ) { TenantI18nRuntime.current() }

        assertThat(runtime.epoch).isEqualTo(TenantEpoch(1))
        assertThat(runtime.catalog).isEqualTo(snapshot.reference)
        assertThat(runtime.overlay).isSameAs(overlay)
        assertThat(runtime.view.spec.resolution.locale).isEqualTo(LocaleTag.parse("ru"))
        assertThat(runtime.view.spec.zone).isEqualTo(ZoneId.of("Asia/Almaty"))
        assertThatThrownBy(TenantI18nRuntime::current).isInstanceOf(IllegalStateException::class.java)
    }

    private fun catalog(): CatalogSnapshot {
        val en = LocaleTag.parse("en")
        val ru = LocaleTag.parse("ru")
        val source = "Base"
        val sourceDigest = CatalogDigests.source("rain-mf2/v1", en, source, "title")
        return (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("tenant", icuClDrTzdbIdentity = "icu4j-78.3"),
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
                                overridePolicy = OverridePolicy.TENANT,
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

    private fun overlay(snapshot: CatalogSnapshot): com.gd.rain.i18n.CatalogOverlay {
        val record = checkNotNull(snapshot.message(MessageKey("app", "title")))
        return (
            CatalogOverlayCompiler.compile(
                snapshot,
                CatalogOverlaySpec(
                    snapshot.reference,
                    OverlayLayer.TENANT,
                    "brand_v1",
                    listOf(
                        OverlayTranslationSpec(
                            record.spec.key,
                            LocaleTag.parse("ru"),
                            "Acme",
                            record.contract,
                            record.sourceDigest,
                            CatalogDigests.review(record.sourceDigest, LocaleTag.parse("ru"), "Acme"),
                        ),
                    ),
                ),
            ) as CatalogOverlayCompilation.Compiled
        ).overlay
    }

    private fun authority(): TenantAuthority {
        val ref = TenantRef.of("acme")
        val resolution = TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
        return HmacTenantAuthority(
            "test",
            object : TenantResolver {
                override fun resolveCurrent(context: TenantRequestContext): TenantCandidate = TenantCandidate.Present(resolution, "test")

                override fun lookup(ref: TenantRef): TenantResolution? = resolution.takeIf { it.ref == ref }
            },
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            identityKey = ByteArray(32) { 2 },
            clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            random = SecureRandom(),
        )
    }
}
