package com.gd.rain.i18n.persistence

import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.persistence.autoconfigure.RainI18nPersistenceAutoConfiguration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager

class RainI18nPersistenceAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainI18nPersistenceAutoConfiguration::class.java))
            .withPropertyValues(
                "rain.i18n.persistence.enabled=true",
                "rain.i18n.persistence.runtime.profile=rain-mf2/v1",
                "rain.i18n.persistence.runtime.engine=rain-i18n/1",
                "rain.i18n.persistence.runtime.icu-cl-dr-tzdb-identity=icu4j-78.3",
                "rain.i18n.persistence.max-retained=4",
                "rain.i18n.persistence.max-pins=20",
                "rain.i18n.persistence.max-pin-lifetime=PT2H",
                "rain.i18n.persistence.change-feed-batch=50",
                "rain.runtime.roles=api",
            ).withBean(DSLContext::class.java, { mockk() })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(DriverManagerDataSource()) })
            .withBean(CatalogReleaseStore::class.java, { mockk() })

    @Test
    fun `one explicit durable scope installs the replaceable snapshot provider magic path`() {
        runner
            .withBean(CatalogReleaseScope::class.java, { CatalogReleaseScope("application") })
            .run { context ->
                assertThat(context).hasSingleBean(CatalogSnapshotProvider::class.java)
                assertThat(context.getBean(CatalogSnapshotProvider::class.java))
                    .isInstanceOf(CatalogReleaseStoreSnapshotProvider::class.java)
            }
    }

    @Test
    fun `an application snapshot provider wins over the durable store convenience path`() {
        val application = CatalogSnapshotProvider { error("the application provider is not read during startup") }

        runner
            .withBean(CatalogReleaseScope::class.java, { CatalogReleaseScope("application") })
            .withBean(CatalogSnapshotProvider::class.java, { application })
            .run { context ->
                assertThat(context).hasSingleBean(CatalogSnapshotProvider::class.java)
                assertThat(context.getBean(CatalogSnapshotProvider::class.java)).isSameAs(application)
            }
    }
}
