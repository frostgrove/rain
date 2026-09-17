package com.gd.rain.tenancy.event

import com.gd.rain.event.EventLimits
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.postgres.EventTransactionPlacement
import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.persistence.tx.TransactionPlacement
import com.gd.rain.tenancy.TenantUnit

/** One event-store binding for the exact tenant unit currently in progress. */
public data class TenantEventStore(
    public val namespace: EventNamespace,
    public val store: PostgresEventStore,
)

/**
 * The only adapter that composes tenancy and event persistence. It borrows neither a datasource
 * nor a connection: [TenantUnit] already owns the scope, transaction, backing and authority.
 */
public class TenantEventStoreFactory(
    private val limits: EventLimits = EventLimits(),
) {
    public fun forUnit(unit: TenantUnit): TenantEventStore {
        val store =
            PostgresEventStore(
                unit.dsl,
                EventTransactionPlacement { TransactionPlacement(unit.backing, unit.requireCurrentTransaction()) },
                limits,
            )
        return TenantEventStore(TenantEventNamespace.of(unit.scope), store)
    }
}
