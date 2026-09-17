package com.gd.rain.i18n.jobs.persistence

import com.gd.rain.i18n.CatalogArtifactCodec
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.CatalogTrustPolicy
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import com.gd.rain.i18n.TrustedCatalogLoader
import com.gd.rain.i18n.jobs.I18nDurableDeliveryPinRequest
import com.gd.rain.i18n.jobs.I18nDurableDeliveryPinResult
import com.gd.rain.i18n.jobs.I18nDurablePinSweepRequest
import com.gd.rain.i18n.persistence.CatalogArtifactSubmission
import com.gd.rain.i18n.persistence.CatalogPersistenceLimits
import com.gd.rain.i18n.persistence.CatalogReleaseActor
import com.gd.rain.i18n.persistence.CatalogReleaseCommand
import com.gd.rain.i18n.persistence.CatalogReleaseOperation
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.DurableCatalogTransition
import com.gd.rain.i18n.persistence.postgres.PostgresCatalogReleaseStore
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
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
import java.util.UUID

@Tag("integration")
class CatalogReleaseStoreI18nDurableDeliveryPinsIT {
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val scope = CatalogReleaseScope("application")
    private val actor = CatalogReleaseActor("release-bot")

    @Test
    fun `production pinner joins enqueue then releases and sweeps through durable store`() {
        val database = RainPostgres.freshDatabase("i18n_jobs_pin")
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
        val store = PostgresCatalogReleaseStore(dsl, transactions, TrustedCatalogLoader(), CatalogPersistenceLimits(), clock)
        val template = TransactionTemplate(transactions)
        val pinner = CatalogReleaseStoreI18nDurableDeliveryPins(store, scope, template)
        val release =
            transaction(template, store) { transaction ->
                store.publishAndActivate(
                    transaction,
                    CatalogReleaseCommand(
                        scope,
                        null,
                        CatalogArtifactSubmission(artifact("release-a", "ready"), CatalogTrustPolicy.LOCAL_BUILD),
                        actor,
                        CatalogReleaseOperation("publish-a"),
                    ),
                )
            } as DurableCatalogTransition.Updated
        val firstRequest = I18nDurableDeliveryPinRequest(UUID.randomUUID(), release.head.reference, Duration.ofHours(1))

        assertThatIllegalStateException().isThrownBy { pinner.acquire(firstRequest) }
        assertThat(
            template.execute {
                pinner.acquire(firstRequest)
            },
        ).isInstanceOf(I18nDurableDeliveryPinResult.Pinned::class.java)
        val firstPin =
            checkNotNull(
                template.execute {
                    pinner.acquire(I18nDurableDeliveryPinRequest(UUID.randomUUID(), release.head.reference, Duration.ofHours(1)))
                },
            )
                as I18nDurableDeliveryPinResult.Pinned

        assertThat(firstPin.pin.invocation).isNotEqualTo(firstRequest.invocation)
        assertThat(firstPin.pin.catalog).isEqualTo(release.head.reference)
        assertThat(dsl.fetchValue("SELECT count(*)::integer FROM rain_i18n.i18n_pin", Int::class.java)).isEqualTo(2)
        assertThat(pinner.release(firstPin.pin)).isTrue()
        assertThat(pinner.release(firstPin.pin)).isFalse()

        clock.advance(Duration.ofHours(1))
        assertThat(pinner.sweep(I18nDurablePinSweepRequest(clock.instant(), 10))).isEqualTo(1)
        assertThat(dsl.fetchValue("SELECT count(*)::integer FROM rain_i18n.i18n_pin", Int::class.java)).isEqualTo(0)
        assertThat(dsl.fetchValue("SELECT count(*)::integer FROM rain_i18n.i18n_audit WHERE kind = 'PIN_EXPIRED'", Int::class.java))
            .isEqualTo(1)
    }

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
        block: (com.gd.rain.i18n.persistence.CatalogReleaseTransaction) -> T,
    ): T = checkNotNull(template.execute { store.inCallerTransaction(block) })
}
