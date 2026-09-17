package com.gd.rain.i18n.jobs.autoconfigure

import com.gd.rain.i18n.I18nObserver
import com.gd.rain.i18n.jobs.I18nDeliveryViews
import com.gd.rain.i18n.jobs.I18nDurableDeliveryPins
import com.gd.rain.i18n.jobs.I18nDurablePinPolicy
import com.gd.rain.i18n.jobs.I18nDurablePinSweepPolicy
import com.gd.rain.i18n.jobs.I18nDurablePinSweepWork
import com.gd.rain.i18n.jobs.I18nJobContextProvider
import com.gd.rain.i18n.jobs.I18nSnapshotRepository
import com.gd.rain.i18n.jobs.persistence.CatalogReleaseStoreI18nDurableDeliveryPins
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.CatalogReleaseStore
import com.gd.rain.jobs.autoconfigure.RainJobsAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Magic-first registration of the independent jobs↔i18n context provider.
 *
 * A product opts in by supplying [I18nSnapshotRepository]. The configuration runs before jobs
 * assembles its provider list; it does not register a tenant/event adapter or choose an implicit
 * i18n delivery mode.
 */
@AutoConfiguration(before = [RainJobsAutoConfiguration::class])
@ConditionalOnBean(I18nSnapshotRepository::class)
public class RainI18nJobsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun i18nDeliveryViews(
        snapshots: I18nSnapshotRepository,
        observers: ObjectProvider<I18nObserver>,
    ): I18nDeliveryViews = I18nDeliveryViews(snapshots, observers.ifAvailable ?: I18nObserver.NONE)

    @Bean
    @ConditionalOnMissingBean
    public fun i18nJobContextProvider(
        views: I18nDeliveryViews,
        pins: ObjectProvider<I18nDurableDeliveryPins>,
        policy: ObjectProvider<I18nDurablePinPolicy>,
    ): I18nJobContextProvider = I18nJobContextProvider(views, pins.ifAvailable, policy.ifAvailable)

    /**
     * Magic default for a product that already selected the durable i18n store and its explicit
     * release scope. Advanced installations can replace this bean with another pin authority.
     */
    @Bean
    @ConditionalOnBean(CatalogReleaseStore::class, CatalogReleaseScope::class)
    @ConditionalOnMissingBean(I18nDurableDeliveryPins::class)
    public fun catalogReleaseStoreI18nDurableDeliveryPins(
        store: CatalogReleaseStore,
        scope: CatalogReleaseScope,
        transactions: PlatformTransactionManager,
    ): I18nDurableDeliveryPins = CatalogReleaseStoreI18nDurableDeliveryPins(store, scope, TransactionTemplate(transactions))

    /** Registers cluster-singleton expiry cleanup only when the product explicitly supplies its retention policy. */
    @Bean
    @ConditionalOnBean(I18nDurableDeliveryPins::class, I18nDurablePinSweepPolicy::class)
    @ConditionalOnMissingBean
    public fun i18nDurablePinSweepWork(
        pins: I18nDurableDeliveryPins,
        policy: I18nDurablePinSweepPolicy,
        clock: Clock,
    ): I18nDurablePinSweepWork = I18nDurablePinSweepWork(pins, policy, clock)
}
