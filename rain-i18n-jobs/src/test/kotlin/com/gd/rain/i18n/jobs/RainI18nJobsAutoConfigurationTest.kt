package com.gd.rain.i18n.jobs

import com.gd.rain.i18n.CatalogOverlay
import com.gd.rain.i18n.CatalogOverlayRef
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.jobs.autoconfigure.RainI18nJobsAutoConfiguration
import com.gd.rain.i18n.jobs.persistence.CatalogReleaseStoreI18nDurableDeliveryPins
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.CatalogReleaseStore
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RainI18nJobsAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(RainI18nJobsAutoConfiguration::class.java))

    @Test
    fun `i18n provider stays absent until an application supplies the explicit snapshot repository`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(I18nJobContextProvider::class.java)
        }
    }

    @Test
    fun `snapshot repository opts into the magic jobs provider without changing its low level API`() {
        runner
            .withBean(I18nSnapshotRepository::class.java, { EmptyRepository })
            .run { context ->
                assertThat(context).hasSingleBean(I18nDeliveryViews::class.java).hasSingleBean(I18nJobContextProvider::class.java)
            }
    }

    @Test
    fun `durable pin authority and explicit sweep policy opt into only the paired sweep work`() {
        runner
            .withBean(I18nSnapshotRepository::class.java, { EmptyRepository })
            .withBean(I18nDurableDeliveryPins::class.java, { EmptyPins })
            .withBean(I18nDurablePinPolicy::class.java, { I18nDurablePinPolicy(java.time.Duration.ofDays(1)) })
            .withBean(I18nDurablePinSweepPolicy::class.java, { I18nDurablePinSweepPolicy(java.time.Duration.ofMinutes(5), 20) })
            .withBean(Clock::class.java, { Clock.fixed(Instant.EPOCH, ZoneOffset.UTC) })
            .run { context ->
                assertThat(context).hasSingleBean(I18nDurablePinSweepWork::class.java)
            }
    }

    @Test
    fun `durable catalog store and explicit scope install the replaceable magic pin authority`() {
        runner
            .withBean(I18nSnapshotRepository::class.java, { EmptyRepository })
            .withBean(CatalogReleaseStore::class.java, { mockk() })
            .withBean(CatalogReleaseScope::class.java, { CatalogReleaseScope("application") })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(DriverManagerDataSource()) })
            .run { context ->
                assertThat(context).hasSingleBean(I18nDurableDeliveryPins::class.java)
                assertThat(context.getBean(I18nDurableDeliveryPins::class.java))
                    .isInstanceOf(CatalogReleaseStoreI18nDurableDeliveryPins::class.java)
            }
    }

    private data object EmptyRepository : I18nSnapshotRepository {
        override fun current(): CatalogSnapshot = error("the auto-configuration does not resolve a catalog during startup")

        override fun snapshot(reference: CatalogRef): CatalogSnapshot? = null

        override fun overlay(reference: CatalogOverlayRef): CatalogOverlay? = null
    }

    private data object EmptyPins : I18nDurableDeliveryPins {
        override fun acquire(request: I18nDurableDeliveryPinRequest): I18nDurableDeliveryPinResult =
            I18nDurableDeliveryPinResult.Missing(request.catalog)

        override fun release(pin: I18nDurableDeliveryPin): Boolean = false

        override fun sweep(request: I18nDurablePinSweepRequest): Int = 0
    }
}
