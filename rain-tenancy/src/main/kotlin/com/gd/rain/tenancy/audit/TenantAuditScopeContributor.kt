package com.gd.rain.tenancy.audit

import com.gd.rain.audit.scope.AuditScope
import com.gd.rain.audit.scope.AuditScopeContributor
import com.gd.rain.tenancy.TenantContext

/** The tenancy-owned audit projection of an already minted scope; it cannot expose a tenant ref. */
public class TenantAuditScopeContributor : AuditScopeContributor {
    override fun current(): AuditScope? =
        try {
            TenantContext.requireScope().let { scope -> AuditScope.of(KIND, scope.namespaceSeed(), scope.epoch.value) }
        } catch (_: IllegalStateException) {
            null
        }

    private companion object {
        const val KIND: String = "tenant"
    }
}
