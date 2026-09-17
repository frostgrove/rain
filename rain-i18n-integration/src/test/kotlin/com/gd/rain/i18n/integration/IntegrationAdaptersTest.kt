package com.gd.rain.i18n.integration

import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.OverridePolicy
import com.gd.rain.i18n.TranslationReview
import com.gd.rain.i18n.TranslationSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.UUID

class IntegrationAdaptersTest {
    private val en: LocaleTag = LocaleTag.parse("en")
    private val ru: LocaleTag = LocaleTag.parse("ru")

    @Test
    fun `client manifest exports only public structural contracts and makes parity explicit`() {
        val snapshot = catalog()

        val manifest = PublicClientCatalogContract.manifest(snapshot)

        assertThat(manifest.catalog).isEqualTo(snapshot.reference)
        assertThat(manifest.digest).isEqualTo(snapshot.digest)
        assertThat(manifest.formattingParity).isFalse()
        assertThat(manifest.messages.map { it.key.value }).containsExactly("shop.title")
        assertThat(manifest.messages.single().markup).isEmpty()
    }

    @Test
    fun `in-memory tms preserves source provenance and returns only recorded matching candidates`() {
        val snapshot = catalog()
        val record = checkNotNull(snapshot.message(MessageKey("shop", "title")))
        val connector = InMemoryTranslationManagementConnector()
        val source =
            TranslationSourceEntry(
                record.spec.key,
                record.contract,
                record.sourceDigest,
                record.spec.source,
                record.spec.description,
            )
        val candidate = TranslationCandidate(record.spec.key, ru, record.contract, record.sourceDigest, "Витрина")

        assertThat(connector.upload(TranslationUpload(snapshot.reference, listOf(source))))
            .isInstanceOf(TranslationUploadOutcome.Accepted::class.java)
        assertThat(connector.record(snapshot.reference, candidate)).isTrue()
        assertThat(
            connector.record(
                snapshot.reference,
                candidate.copy(sourceDigest = CatalogDigests.source("rain-mf2/v1", en, "changed", "title")),
            ),
        ).isFalse()

        val downloaded = connector.download(TranslationDownload(snapshot.reference, setOf(ru), null, limit = 10))

        assertThat(downloaded).isEqualTo(TranslationDownloadOutcome.Downloaded(TranslationDownloadPage(listOf(candidate), null)))
    }

    @Test
    fun `tenant administration reviews activates and deduplicates exact operation receipts`() {
        val snapshot = catalog()
        val record = checkNotNull(snapshot.message(MessageKey("shop", "title")))
        val tenant = TenantCatalogId("acme")
        val administration =
            InMemoryTenantCatalogAdministration(
                snapshots = CatalogSnapshotLookup { reference -> snapshot.takeIf { it.reference == reference } },
                authorizer = TenantCatalogAuthorizer { TenantCatalogAuthorization.GRANTED },
            )
        val set =
            SetTenantOverlay(
                tenant,
                TenantCatalogOperationId(UUID.randomUUID()),
                expectedVersion = 0,
                TenantOverlayDraft(
                    snapshot.reference,
                    "brand_v1",
                    listOf(TenantOverlayDraftEntry(record.spec.key, ru, "Acme", record.contract, record.sourceDigest)),
                ),
            )

        val staged = administration.execute(set)
        val reviewed = administration.execute(ReviewTenantOverlay(tenant, TenantCatalogOperationId(UUID.randomUUID()), 1, "brand_v1"))
        val activated = administration.execute(ActivateTenantOverlay(tenant, TenantCatalogOperationId(UUID.randomUUID()), 2, "brand_v1"))

        assertThat(staged).isEqualTo(TenantCatalogAdminOutcome.Changed(1, null))
        assertThat(administration.execute(set)).isEqualTo(staged)
        assertThat(reviewed).isEqualTo(TenantCatalogAdminOutcome.Changed(2, null))
        assertThat(activated).isInstanceOf(TenantCatalogAdminOutcome.Changed::class.java)
        assertThat(administration.active(tenant)?.reference?.revision).isEqualTo("brand_v1")
    }

    private fun catalog(): CatalogSnapshot {
        val source = "Store"
        val sourceDigest = CatalogDigests.source("rain-mf2/v1", en, source, "title")
        return (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity("integration", icuClDrTzdbIdentity = "icu4j-78.3"),
                    en,
                    LocalePolicy(setOf(en, ru), en),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("shop", "title"),
                                1,
                                source,
                                "title",
                                overridePolicy = OverridePolicy.TENANT,
                                public = true,
                                translations =
                                    listOf(
                                        TranslationSpec(
                                            ru,
                                            "Магазин",
                                            TranslationReview.APPROVED,
                                            sourceDigest,
                                            CatalogDigests.review(sourceDigest, ru, "Магазин"),
                                        ),
                                    ),
                            ),
                            MessageSpec(MessageKey("shop", "private_note"), 1, "Private", "private"),
                        ),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot
    }
}
