package com.gd.rain.i18n.persistence

import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogSnapshotProvider
import org.springframework.transaction.support.TransactionOperations

/**
 * Read-only magic adapter from one explicit durable release scope to the core snapshot provider.
 *
 * It opens one short read transaction per lookup and reads the authoritative head; callers never
 * retain a mutable map or infer a scope. Products with a cache/change-feed consumer replace this
 * provider with their own `CatalogSnapshotProvider` while preserving the same core/web contracts.
 */
public class CatalogReleaseStoreSnapshotProvider(
    private val store: CatalogReleaseStore,
    private val scope: CatalogReleaseScope,
    private val transactions: TransactionOperations,
) : CatalogSnapshotProvider {
    override fun current(): CatalogSnapshot =
        checkNotNull(
            transactions.execute {
                store.inCallerTransaction { transaction ->
                    when (val loaded = store.current(transaction, scope)) {
                        is DurableCatalogCurrentLoad.Loaded -> loaded.current.snapshot
                        DurableCatalogCurrentLoad.Missing -> throw CatalogReleaseSnapshotUnavailableException()
                        is DurableCatalogCurrentLoad.Refused -> throw CatalogReleaseSnapshotUnavailableException(loaded.reason.name)
                    }
                }
            },
        )
}

/** A durable catalog head is absent or cannot pass the configured trust/identity gate. */
public class CatalogReleaseSnapshotUnavailableException(
    reason: String? = null,
) : IllegalStateException(
        reason?.let { "the durable i18n catalog snapshot is unavailable: $it" }
            ?: "the durable i18n catalog snapshot is unavailable",
    )
