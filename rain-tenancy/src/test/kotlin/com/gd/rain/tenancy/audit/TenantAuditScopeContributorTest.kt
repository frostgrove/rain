package com.gd.rain.tenancy.audit

import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantCandidate
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantRequestContext
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolver
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant

class TenantAuditScopeContributorTest {
    @Test
    fun `audit scope uses the authority digest and epoch while a minted tenant scope is bound`() {
        val ref = TenantRef.of("acme")
        val resolution = TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(7), 3)
        val authority =
            HmacTenantAuthority(
                "test",
                object : TenantResolver {
                    override fun resolveCurrent(context: TenantRequestContext): TenantCandidate =
                        TenantCandidate.Present(resolution, "test")

                    override fun lookup(ref: TenantRef): TenantResolution? = resolution.takeIf { it.ref == ref }
                },
                TenantAdmission.DEFAULT,
                ByteArray(32) { 1 },
                identityKey = ByteArray(32) { 2 },
                clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
                random = SecureRandom(),
            )
        val contributor = TenantAuditScopeContributor()
        val scope = authority.lookup(ref, TenantOperation.READ)

        assertThat(contributor.current()).isNull()
        TenantContext.bind(scope).use {
            val audit = checkNotNull(contributor.current())
            assertThat(audit.kind).isEqualTo("tenant")
            assertThat(audit.epoch).isEqualTo(7)
            assertThat(audit.digest()).containsExactly(*scope.namespaceSeed())
        }
        assertThat(contributor.current()).isNull()
    }
}
