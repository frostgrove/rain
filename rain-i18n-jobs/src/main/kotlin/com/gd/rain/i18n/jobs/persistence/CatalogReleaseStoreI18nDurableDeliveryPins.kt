package com.gd.rain.i18n.jobs.persistence

import com.gd.rain.i18n.jobs.I18nDurableDeliveryPin
import com.gd.rain.i18n.jobs.I18nDurableDeliveryPinLimitReason
import com.gd.rain.i18n.jobs.I18nDurableDeliveryPinRequest
import com.gd.rain.i18n.jobs.I18nDurableDeliveryPinResult
import com.gd.rain.i18n.jobs.I18nDurableDeliveryPins
import com.gd.rain.i18n.jobs.I18nDurablePinSweepRequest
import com.gd.rain.i18n.persistence.CatalogPersistenceLimitReason
import com.gd.rain.i18n.persistence.CatalogPinCommand
import com.gd.rain.i18n.persistence.CatalogPinReleaseCommand
import com.gd.rain.i18n.persistence.CatalogPinResult
import com.gd.rain.i18n.persistence.CatalogPinSweepCommand
import com.gd.rain.i18n.persistence.CatalogReleaseActor
import com.gd.rain.i18n.persistence.CatalogReleaseOperation
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.CatalogReleaseStore
import com.gd.rain.i18n.persistence.DurableCatalogPin
import org.springframework.transaction.support.TransactionOperations

/**
 * Production durable-pin adapter over the i18n release store.
 *
 * `acquire` joins the jobs enqueue transaction through [CatalogReleaseStore.inCallerTransaction],
 * rather than opening a second transaction. Release and expiry sweep are separate, idempotent
 * committed operations after a jobs terminal receipt. The only cross-context fields are the
 * opaque jobs invocation and exact catalog reference; this adapter models neither events nor
 * tenancy.
 */
public class CatalogReleaseStoreI18nDurableDeliveryPins(
    private val store: CatalogReleaseStore,
    private val scope: CatalogReleaseScope,
    private val terminalTransactions: TransactionOperations,
    private val actor: CatalogReleaseActor = CatalogReleaseActor("rain-i18n-jobs"),
) : I18nDurableDeliveryPins {
    override fun acquire(request: I18nDurableDeliveryPinRequest): I18nDurableDeliveryPinResult =
        store.inCallerTransaction { transaction ->
            when (
                val result =
                    store.pin(
                        transaction,
                        CatalogPinCommand(
                            scope,
                            request.catalog,
                            owner(request.invocation),
                            request.retention,
                            actor,
                            operation(request.invocation),
                        ),
                    )
            ) {
                is CatalogPinResult.Pinned -> {
                    I18nDurableDeliveryPinResult.Pinned(
                        I18nDurableDeliveryPin(
                            result.pin.id,
                            request.invocation,
                            result.pin.reference,
                            result.pin.expiresAt,
                        ),
                    )
                }

                is CatalogPinResult.Missing -> {
                    I18nDurableDeliveryPinResult.Missing(result.reference)
                }

                is CatalogPinResult.Limit -> {
                    I18nDurableDeliveryPinResult.Limit(result.reason.toJobsReason())
                }
            }
        }

    override fun release(pin: I18nDurableDeliveryPin): Boolean =
        checkNotNull(
            terminalTransactions.execute {
                store.inCallerTransaction { transaction ->
                    store.release(
                        transaction,
                        CatalogPinReleaseCommand(
                            DurableCatalogPin(pin.id, scope, pin.catalog, owner(pin.invocation), pin.expiresAt),
                            actor,
                            operation(pin.invocation),
                        ),
                    )
                }
            },
        )

    override fun sweep(request: I18nDurablePinSweepRequest): Int =
        checkNotNull(
            terminalTransactions.execute {
                store.inCallerTransaction { transaction ->
                    store.sweepExpiredPins(
                        transaction,
                        CatalogPinSweepCommand(
                            scope,
                            request.expiredAtOrBefore,
                            request.limit,
                            actor,
                            CatalogReleaseOperation("i18n-job-pin-sweep"),
                        ),
                    )
                }
            },
        )

    private fun owner(invocation: java.util.UUID): String = "rain-i18n-job:$invocation"

    private fun operation(invocation: java.util.UUID): CatalogReleaseOperation = CatalogReleaseOperation("i18n-job-pin-$invocation")

    private fun CatalogPersistenceLimitReason.toJobsReason(): I18nDurableDeliveryPinLimitReason =
        when (this) {
            CatalogPersistenceLimitReason.PIN_COUNT -> I18nDurableDeliveryPinLimitReason.PIN_COUNT
            CatalogPersistenceLimitReason.PIN_LIFETIME -> I18nDurableDeliveryPinLimitReason.PIN_LIFETIME
            CatalogPersistenceLimitReason.RETENTION_HELD_BY_PINS -> I18nDurableDeliveryPinLimitReason.STORAGE_CAPACITY
        }
}
