package com.gd.rain.i18n.persistence.autoconfigure

import com.gd.rain.boot.runtime.ConditionalOnRainCommand
import com.gd.rain.i18n.CatalogArtifactCodec
import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.TrustedCatalogLoader
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.CatalogReleaseStore
import com.gd.rain.i18n.persistence.CatalogReleaseStoreSnapshotProvider
import com.gd.rain.i18n.persistence.I18nPersistenceProperties
import com.gd.rain.i18n.persistence.I18nPersistenceSettings
import com.gd.rain.i18n.persistence.command.I18nActivateCommand
import com.gd.rain.i18n.persistence.command.I18nActivateCommandDeclaration
import com.gd.rain.i18n.persistence.command.I18nPruneCommand
import com.gd.rain.i18n.persistence.command.I18nPruneCommandDeclaration
import com.gd.rain.i18n.persistence.command.I18nRollbackCommand
import com.gd.rain.i18n.persistence.command.I18nRollbackCommandDeclaration
import com.gd.rain.i18n.persistence.command.I18nStatusCommand
import com.gd.rain.i18n.persistence.command.I18nStatusCommandDeclaration
import com.gd.rain.i18n.persistence.postgres.PostgresCatalogReleaseStore
import com.gd.rain.persistence.autoconfigure.RainPersistenceAutoConfiguration
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Default durable i18n store. Operators opt in with explicit finite limits and runtime identity;
 * applications can replace either the trust loader or the store without disabling the rest of i18n.
 */
@AutoConfiguration(after = [RainPersistenceAutoConfiguration::class])
@ConditionalOnBooleanProperty(I18nPersistenceProperties.ENABLED)
@EnableConfigurationProperties(I18nPersistenceProperties::class)
@ConditionalOnBean(DSLContext::class, PlatformTransactionManager::class)
public class RainI18nPersistenceAutoConfiguration {
    @Bean
    public fun i18nPersistenceSettings(properties: I18nPersistenceProperties): I18nPersistenceSettings =
        I18nPersistenceSettings.of(properties)

    /** The magic default admits only locally built artifacts; signed remote release support supplies its own loader/keyring. */
    @Bean
    @ConditionalOnMissingBean
    public fun trustedCatalogLoader(settings: I18nPersistenceSettings): TrustedCatalogLoader =
        TrustedCatalogLoader(CatalogArtifactCodec(runtimeIdentity = settings.runtimeIdentity))

    @Bean
    @ConditionalOnMissingBean(CatalogReleaseStore::class)
    public fun catalogReleaseStore(
        dsl: DSLContext,
        transactions: PlatformTransactionManager,
        loader: TrustedCatalogLoader,
        settings: I18nPersistenceSettings,
        clock: Clock,
    ): CatalogReleaseStore = PostgresCatalogReleaseStore(dsl, transactions, loader, settings.limits, clock)

    /**
     * Magic read path once an application deliberately names its durable catalog namespace.
     *
     * A product can replace this with a cache/change-feed aware provider; no release scope is
     * guessed from a tenant, request, queue or event.
     */
    @Bean
    @ConditionalOnBean(CatalogReleaseScope::class)
    @ConditionalOnMissingBean(CatalogSnapshotProvider::class)
    public fun catalogReleaseStoreSnapshotProvider(
        store: CatalogReleaseStore,
        scope: CatalogReleaseScope,
        transactions: PlatformTransactionManager,
    ): CatalogSnapshotProvider = CatalogReleaseStoreSnapshotProvider(store, scope, TransactionTemplate(transactions))

    @Bean
    @ConditionalOnRainCommand(I18nStatusCommandDeclaration.NAME)
    @ConditionalOnBean(CatalogReleaseScope::class)
    public fun i18nStatusCommand(
        store: CatalogReleaseStore,
        scope: CatalogReleaseScope,
        transactions: PlatformTransactionManager,
    ): I18nStatusCommand = I18nStatusCommand(store, scope, TransactionTemplate(transactions))

    @Bean
    @ConditionalOnRainCommand(I18nActivateCommandDeclaration.NAME)
    @ConditionalOnBean(CatalogReleaseScope::class)
    public fun i18nActivateCommand(
        store: CatalogReleaseStore,
        scope: CatalogReleaseScope,
        transactions: PlatformTransactionManager,
    ): I18nActivateCommand = I18nActivateCommand(store, scope, TransactionTemplate(transactions))

    @Bean
    @ConditionalOnRainCommand(I18nRollbackCommandDeclaration.NAME)
    @ConditionalOnBean(CatalogReleaseScope::class)
    public fun i18nRollbackCommand(
        store: CatalogReleaseStore,
        scope: CatalogReleaseScope,
        transactions: PlatformTransactionManager,
    ): I18nRollbackCommand = I18nRollbackCommand(store, scope, TransactionTemplate(transactions))

    @Bean
    @ConditionalOnRainCommand(I18nPruneCommandDeclaration.NAME)
    @ConditionalOnBean(CatalogReleaseScope::class)
    public fun i18nPruneCommand(
        store: CatalogReleaseStore,
        scope: CatalogReleaseScope,
        transactions: PlatformTransactionManager,
    ): I18nPruneCommand = I18nPruneCommand(store, scope, TransactionTemplate(transactions))
}
