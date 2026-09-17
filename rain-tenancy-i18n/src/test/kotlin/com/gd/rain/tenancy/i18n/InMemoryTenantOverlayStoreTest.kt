package com.gd.rain.tenancy.i18n

import com.gd.rain.i18n.CatalogCompilation
import com.gd.rain.i18n.CatalogCompiler
import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogOverlaySpec
import com.gd.rain.i18n.CatalogSnapshot
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
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class InMemoryTenantOverlayStoreTest {
    private val clock: MutableClock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
    private val actor: TenantOverlayActor = TenantOverlayActor("operator:elena")

    @Test
    fun `candidate becomes visible only after review and activation with immutable audit and change evidence`() {
        val base = catalog("catalog_v1")
        val store = InMemoryTenantOverlayStore(TenantOverlayCatalogs { reference -> base.takeIf { it.reference == reference } }, clock)
        val scope = scope(TenantEpoch(1))
        val candidate = candidate(base, "brand_v1", "Acme")

        val staged = store.execute(set(scope, 0, candidate, "00000000-0000-0000-0000-000000000001"))
        assertThat(staged).isEqualTo(TenantOverlayOutcome.Changed(1, null))
        assertThat(store.current(scope, base)).isEqualTo(TenantOverlayCurrent(1, null))
        val draft = store.revisions(scope).single()
        assertThat(draft.state).isEqualTo(TenantOverlayRevisionState.DRAFT)
        assertThat(draft.reference).isNull()

        val reviewed = store.execute(review(scope, 1, candidate.revision, "00000000-0000-0000-0000-000000000002"))
        assertThat(reviewed).isEqualTo(TenantOverlayOutcome.Changed(2, null))
        val activated = store.execute(activate(scope, 2, candidate.revision, "00000000-0000-0000-0000-000000000003"))
        val active = (activated as TenantOverlayOutcome.Changed).active
        assertThat(active).isNotNull()
        assertThat(store.current(scope, base).active?.reference).isEqualTo(active)

        assertThat(store.audits(scope).map(TenantOverlayAudit::kind)).containsExactly(
            TenantOverlayAuditKind.STAGED,
            TenantOverlayAuditKind.REVIEWED,
            TenantOverlayAuditKind.ACTIVATED,
        )
        assertThat(store.audits(scope).joinToString()).doesNotContain("Acme")
        val firstPage = store.readChanges(0, 2)
        assertThat(firstPage.changes).hasSize(2)
        assertThat(firstPage.hasMore).isTrue()
        val finalChange = store.readChanges(firstPage.changes.last().cursor, 2).changes.single()
        assertThat(finalChange.active).isEqualTo(active)
        assertThat(finalChange.version).isEqualTo(3)
    }

    @Test
    fun `operation replay is exact and a restore epoch cannot read a prior tenant overlay`() {
        val base = catalog("catalog_v1")
        val store = InMemoryTenantOverlayStore(TenantOverlayCatalogs { reference -> base.takeIf { it.reference == reference } }, clock)
        val firstEpoch = scope(TenantEpoch(1))
        val command = set(firstEpoch, 0, candidate(base, "brand_v1", "Acme"), "00000000-0000-0000-0000-000000000011")

        val original = store.execute(command)
        assertThat(store.execute(command)).isEqualTo(original)
        assertThat(store.audits(firstEpoch)).hasSize(1)

        val reused = command.copy(candidate = candidate(base, "brand_v2", "Other"))
        assertThat(store.execute(reused)).isInstanceOf(TenantOverlayOutcome.Invalid::class.java)
        assertThat(store.audits(firstEpoch)).hasSize(1)

        val restoredEpoch = scope(TenantEpoch(2))
        assertThat(restoredEpoch).isNotEqualTo(firstEpoch)
        assertThat(store.current(restoredEpoch, base)).isEqualTo(TenantOverlayCurrent(0, null))
        assertThat(store.revisions(restoredEpoch)).isEmpty()
    }

    @Test
    fun `active overlay is never reused for a different catalog release`() {
        val first = catalog("catalog_v1")
        val second = catalog("catalog_v2")
        val store = InMemoryTenantOverlayStore(TenantOverlayCatalogs { reference -> first.takeIf { it.reference == reference } }, clock)
        val scope = scope(TenantEpoch(1))
        val candidate = candidate(first, "brand_v1", "Acme")

        store.execute(set(scope, 0, candidate, "00000000-0000-0000-0000-000000000021"))
        store.execute(review(scope, 1, candidate.revision, "00000000-0000-0000-0000-000000000022"))
        store.execute(activate(scope, 2, candidate.revision, "00000000-0000-0000-0000-000000000023"))

        assertThat(store.current(scope, second)).isEqualTo(TenantOverlayCurrent(3, null))
    }

    private fun set(
        scope: TenantOverlayScope,
        expectedVersion: Long,
        candidate: CatalogOverlaySpec,
        operation: String,
    ): SetTenantOverlayCommand =
        SetTenantOverlayCommand(scope, expectedVersion, actor, TenantOverlayOperation(UUID.fromString(operation)), candidate)

    private fun review(
        scope: TenantOverlayScope,
        expectedVersion: Long,
        revision: String,
        operation: String,
    ): ReviewTenantOverlayCommand =
        ReviewTenantOverlayCommand(scope, expectedVersion, actor, TenantOverlayOperation(UUID.fromString(operation)), revision)

    private fun activate(
        scope: TenantOverlayScope,
        expectedVersion: Long,
        revision: String,
        operation: String,
    ): ActivateTenantOverlayCommand =
        ActivateTenantOverlayCommand(scope, expectedVersion, actor, TenantOverlayOperation(UUID.fromString(operation)), revision)

    private fun candidate(
        snapshot: CatalogSnapshot,
        revision: String,
        text: String,
    ): CatalogOverlaySpec {
        val record = checkNotNull(snapshot.message(MessageKey("app", "title")))
        val ru = LocaleTag.parse("ru")
        return CatalogOverlaySpec(
            snapshot.reference,
            OverlayLayer.TENANT,
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
    }

    private fun catalog(revision: String): CatalogSnapshot {
        val en = LocaleTag.parse("en")
        val ru = LocaleTag.parse("ru")
        val source = "Base"
        val sourceDigest = CatalogDigests.source("rain-mf2/v1", en, source, "title")
        return (
            CatalogCompiler.compile(
                CatalogSpec(
                    CatalogIdentity(revision, icuClDrTzdbIdentity = "icu4j-78.3"),
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

    private fun scope(epoch: TenantEpoch): TenantOverlayScope =
        TenantOverlayScope.from(authority(epoch).lookup(TenantRef.of("acme"), TenantOperation.ADMIN))

    private fun authority(epoch: TenantEpoch): TenantAuthority {
        val ref = TenantRef.of("acme")
        val resolution = TenantResolution(ref, TenantLifecycle.ACTIVE, epoch, 1)
        return HmacTenantAuthority(
            "test",
            object : TenantResolver {
                override fun resolveCurrent(context: TenantRequestContext): TenantCandidate = TenantCandidate.Present(resolution, "test")

                override fun lookup(ref: TenantRef): TenantResolution? = resolution.takeIf { it.ref == ref }
            },
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            identityKey = ByteArray(32) { 2 },
            clock = clock,
            random = SecureRandom(),
        )
    }
}
