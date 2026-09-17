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
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
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
import com.gd.rain.tenancy.i18n.postgres.PostgresTenantOverlayStore
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Tag("integration")
class PostgresTenantOverlayStoreIT {
    private val clock: MutableClock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
    private val actor: TenantOverlayActor = TenantOverlayActor("operator:elena")

    @Test
    fun `durable overlay lifecycle atomically fences epoch CAS receipt and cursor evidence`() {
        val database = RainPostgres.freshDatabase("tenant_i18n_overlay")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val catalog = catalog("catalog_v1")
        val store =
            PostgresTenantOverlayStore(
                dsl,
                transactions,
                TenantOverlayCatalogs { reference -> catalog.takeIf { it.reference == reference } },
                clock,
            )
        val transaction = TransactionTemplate(transactions)
        val firstEpoch = scope(TenantEpoch(1))
        val candidate = candidate(catalog, "brand_v1", "Acme")

        assertThat(
            inTransaction(transaction, store) { store.execute(set(firstEpoch, 0, candidate, "00000000-0000-0000-0000-000000000101")) },
        ).isEqualTo(TenantOverlayOutcome.Changed(1, null))
        assertThat(
            inTransaction(transaction, store) {
                store.execute(review(firstEpoch, 1, candidate.revision, "00000000-0000-0000-0000-000000000102"))
            },
        ).isEqualTo(TenantOverlayOutcome.Changed(2, null))
        val activated =
            inTransaction(transaction, store) {
                store.execute(activate(firstEpoch, 2, candidate.revision, "00000000-0000-0000-0000-000000000103"))
            } as TenantOverlayOutcome.Changed

        val replay =
            inTransaction(transaction, store) {
                store.execute(activate(firstEpoch, 2, candidate.revision, "00000000-0000-0000-0000-000000000103"))
            }
        val current = inTransaction(transaction, store) { store.current(firstEpoch, catalog) }
        val changes = inTransaction(transaction, store) { store.readChanges(0, 16) }
        val restoredEpoch = scope(TenantEpoch(2))

        assertThat(activated.active).isNotNull()
        assertThat(replay).isEqualTo(activated)
        assertThat(current.active?.reference).isEqualTo(activated.active)
        assertThat(inTransaction(transaction, store) { store.current(restoredEpoch, catalog) }).isEqualTo(TenantOverlayCurrent(0, null))
        assertThat(changes.changes.map(TenantOverlayChange::kind)).containsExactly(
            TenantOverlayAuditKind.STAGED,
            TenantOverlayAuditKind.REVIEWED,
            TenantOverlayAuditKind.ACTIVATED,
        )
        assertThat(inTransaction(transaction, store) { store.audits(firstEpoch).joinToString() }).doesNotContain("Acme")
        assertThatThrownBy {
            checkNotNull(
                transaction.execute {
                    dsl.execute(
                        "UPDATE rain_tenancy_i18n.tenant_overlay_revision SET state = 'DRAFT' WHERE revision = 'brand_v1'",
                    )
                },
            )
        }.hasMessageContaining("tenant overlay revision is immutable")
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

    private fun <T> inTransaction(
        template: TransactionTemplate,
        store: PostgresTenantOverlayStore,
        block: () -> T,
    ): T = checkNotNull(template.execute { store.inCallerTransaction(block) })
}
