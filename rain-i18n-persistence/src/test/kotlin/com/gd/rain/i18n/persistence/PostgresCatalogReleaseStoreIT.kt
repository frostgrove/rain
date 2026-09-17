package com.gd.rain.i18n.persistence

import com.gd.rain.i18n.CatalogArtifactCodec
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.CatalogTrustPolicy
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.TrustedCatalogLoader
import com.gd.rain.i18n.persistence.postgres.PostgresCatalogReleaseStore
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@Tag("integration")
class PostgresCatalogReleaseStoreIT {
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val scope = CatalogReleaseScope("application")
    private val actor = CatalogReleaseActor("release-bot")

    @Test
    fun `durable catalog lifecycle keeps exact releases through CAS pins and cursor catchup`() {
        val database = RainPostgres.freshDatabase("i18n_catalog_lifecycle")
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
        val store =
            PostgresCatalogReleaseStore(
                dsl,
                transactions,
                TrustedCatalogLoader(),
                CatalogPersistenceLimits(maxRetained = 1),
                clock,
            )
        val template = TransactionTemplate(transactions)
        val firstArtifact = artifact("release-a", "One")
        val secondArtifact = artifact("release-b", "Two")

        val first =
            transaction(template, store) { tx ->
                store.publishAndActivate(tx, publish(null, firstArtifact, "publish-a"))
            } as DurableCatalogTransition.Updated
        val second =
            transaction(template, store) { tx ->
                store.publishAndActivate(tx, publish(first.head, secondArtifact, "publish-b"))
            } as DurableCatalogTransition.Updated

        assertThat(CatalogReleaseStoreSnapshotProvider(store, scope, template).current().reference).isEqualTo(second.head.reference)

        val pin =
            transaction(template, store) { tx ->
                store.pin(
                    tx,
                    CatalogPinCommand(
                        scope,
                        first.head.reference,
                        "mail-outbox",
                        Duration.ofHours(1),
                        actor,
                        CatalogReleaseOperation("pin-a"),
                    ),
                )
            } as CatalogPinResult.Pinned
        val prunedWhilePinned =
            transaction(template, store) { tx ->
                store.prune(tx, scope, actor, CatalogReleaseOperation("prune-pinned"))
            }
        val abaConflict =
            transaction(template, store) { tx ->
                store.publishAndActivate(tx, publish(first.head, firstArtifact, "stale-a"))
            }

        assertThat(second.head.version).isEqualTo(2)
        assertThat(prunedWhilePinned).isEmpty()
        assertThat(abaConflict).isEqualTo(DurableCatalogTransition.Conflict(second.head))
        assertThat(
            transaction(template, store) { tx -> store.load(tx, scope, first.head.reference) },
        ).isInstanceOf(DurableCatalogLoad.Loaded::class.java)

        clock.advance(Duration.ofHours(1))
        val prunedAfterExpiry =
            transaction(template, store) { tx ->
                store.prune(tx, scope, actor, CatalogReleaseOperation("prune-expired"))
            }
        val changes =
            transaction(template, store) { tx ->
                store.readChanges(tx, afterCursor = 0, limit = 16)
            }

        assertThat(prunedAfterExpiry).containsExactly(first.head.reference)
        assertThat(changes.changes.map(CatalogChange::kind))
            .containsExactly(CatalogChangeKind.HEAD_CHANGED, CatalogChangeKind.HEAD_CHANGED, CatalogChangeKind.RELEASE_PRUNED)
        assertThat(
            transaction(template, store) { tx -> store.load(tx, scope, first.head.reference) },
        ).isInstanceOf(DurableCatalogLoad.Loaded::class.java)
    }

    @Test
    fun `bounded pin sweep removes only elapsed pins and audits the expiry`() {
        val database = RainPostgres.freshDatabase("i18n_catalog_pin_sweep")
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
        val store = PostgresCatalogReleaseStore(dsl, transactions, TrustedCatalogLoader(), CatalogPersistenceLimits(maxRetained = 1), clock)
        val template = TransactionTemplate(transactions)
        val firstArtifact = artifact("sweep-a", "One")
        val secondArtifact = artifact("sweep-b", "Two")
        val first =
            transaction(
                template,
                store,
            ) { tx -> store.publishAndActivate(tx, publish(null, firstArtifact, "sweep-publish-a")) } as DurableCatalogTransition.Updated
        transaction(template, store) { tx -> store.publishAndActivate(tx, publish(first.head, secondArtifact, "sweep-publish-b")) }
        transaction(template, store) { tx ->
            store.pin(
                tx,
                CatalogPinCommand(scope, first.head.reference, "job", Duration.ofHours(1), actor, CatalogReleaseOperation("sweep-pin")),
            )
        }

        assertThat(
            transaction(template, store) { tx ->
                store.sweepExpiredPins(
                    tx,
                    CatalogPinSweepCommand(scope, clock.instant(), 10, actor, CatalogReleaseOperation("sweep-expired")),
                )
            },
        ).isZero()
        clock.advance(Duration.ofHours(1))
        assertThat(
            transaction(template, store) { tx ->
                store.sweepExpiredPins(
                    tx,
                    CatalogPinSweepCommand(scope, clock.instant(), 10, actor, CatalogReleaseOperation("sweep-expired")),
                )
            },
        ).isEqualTo(1)
        assertThat(dsl.fetchValue("SELECT count(*)::integer FROM rain_i18n.i18n_audit WHERE kind = 'PIN_EXPIRED'", Int::class.java))
            .isEqualTo(1)
    }

    private fun publish(
        expected: DurableCatalogHead?,
        artifact: ByteArray,
        operation: String,
    ): CatalogReleaseCommand =
        CatalogReleaseCommand(
            scope,
            expected,
            CatalogArtifactSubmission(artifact, CatalogTrustPolicy.LOCAL_BUILD),
            actor,
            CatalogReleaseOperation(operation),
        )

    private fun artifact(
        revision: String,
        text: String,
    ): ByteArray =
        CatalogArtifactCodec().encode(
            CatalogSpec(
                identity = CatalogIdentity(revision, icuClDrTzdbIdentity = "icu4j-78.3"),
                sourceLocale = LocaleTag.parse("en"),
                localePolicy = LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, text, "ready state")),
            ),
        )

    private fun <T> transaction(
        template: TransactionTemplate,
        store: PostgresCatalogReleaseStore,
        block: (CatalogReleaseTransaction) -> T,
    ): T = checkNotNull(template.execute { store.inCallerTransaction(block) })
}
