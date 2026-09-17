package com.gd.rain.tenancy.i18n

import com.gd.rain.i18n.CatalogOverlaySpec
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantScope

/**
 * Magic-first administration facade for the tenant↔i18n bridge.
 *
 * It rechecks a minted scope for `ADMIN`, derives the opaque epoch-fenced partition itself, and
 * then delegates to the complete [TenantOverlayStore] SDK. The store still owns transaction
 * placement: a PostgreSQL caller explicitly wraps this facade in its caller-owned transaction.
 */
public class TenantOverlayAdministration(
    private val authority: TenantAuthority,
    private val store: TenantOverlayStore,
) {
    public fun stage(
        scope: TenantScope,
        expectedVersion: Long,
        actor: TenantOverlayActor,
        operation: TenantOverlayOperation,
        candidate: CatalogOverlaySpec,
    ): TenantOverlayOutcome =
        store.execute(
            SetTenantOverlayCommand(admitted(scope), expectedVersion, actor, operation, candidate),
        )

    public fun review(
        scope: TenantScope,
        expectedVersion: Long,
        actor: TenantOverlayActor,
        operation: TenantOverlayOperation,
        revision: String,
    ): TenantOverlayOutcome =
        store.execute(
            ReviewTenantOverlayCommand(admitted(scope), expectedVersion, actor, operation, revision),
        )

    public fun activate(
        scope: TenantScope,
        expectedVersion: Long,
        actor: TenantOverlayActor,
        operation: TenantOverlayOperation,
        revision: String,
    ): TenantOverlayOutcome =
        store.execute(
            ActivateTenantOverlayCommand(admitted(scope), expectedVersion, actor, operation, revision),
        )

    public fun rollback(
        scope: TenantScope,
        expectedVersion: Long,
        actor: TenantOverlayActor,
        operation: TenantOverlayOperation,
        targetRevision: String,
    ): TenantOverlayOutcome =
        store.execute(
            RollbackTenantOverlayCommand(admitted(scope), expectedVersion, actor, operation, targetRevision),
        )

    private fun admitted(scope: TenantScope): TenantOverlayScope = TenantOverlayScope.from(authority.current(scope, TenantOperation.ADMIN))
}
