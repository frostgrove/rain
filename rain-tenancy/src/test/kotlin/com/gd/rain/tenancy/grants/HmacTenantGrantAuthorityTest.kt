package com.gd.rain.tenancy.grants

import com.gd.rain.tenancy.CompositeTenantResolver
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class HmacTenantGrantAuthorityTest {
    private val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
    private val acme = TenantRef.of("acme")
    private val other = TenantRef.of("other")
    private val authority =
        HmacTenantAuthority(
            "test",
            CompositeTenantResolver(emptyList()) {
                mapOf(
                    acme to TenantResolution(acme, TenantLifecycle.ACTIVE, TenantEpoch(1), 1),
                    other to TenantResolution(other, TenantLifecycle.ACTIVE, TenantEpoch(1), 1),
                )[it]
            },
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            clock = clock,
            random = SecureRandom(),
        )
    private val grants =
        HmacTenantGrantAuthority("ops", authority, ByteArray(32) { 2 }, clock = clock, maximumLifetime = Duration.ofHours(1))

    @Test
    fun `signed bounded grant admits only its covered tenant and operation`() {
        val covered = linkedSetOf(acme)
        val grant =
            grants.issue(
                TenantGrantRequest(
                    purpose = "backfill",
                    operations = setOf(TenantOperation.ADMIN),
                    cohort = covered,
                    expiresAt = clock.instant().plus(Duration.ofMinutes(5)),
                ),
            )
        covered += other

        assertThat(grants.verify(grant, acme, TenantOperation.ADMIN).epoch).isEqualTo(TenantEpoch(1))
        assertThatThrownBy { grants.verify(grant, other, TenantOperation.ADMIN) }
            .isInstanceOf(TenantGrantRequiredException::class.java)
        assertThatThrownBy { grants.verify(grant, acme, TenantOperation.WRITE) }
            .isInstanceOf(TenantGrantRequiredException::class.java)
    }

    @Test
    fun `grant is rejected by another issuer and after expiry`() {
        val grant =
            grants.issue(
                TenantGrantRequest(
                    purpose = "backfill",
                    operations = setOf(TenantOperation.ADMIN),
                    cohort = setOf(acme),
                    expiresAt = clock.instant().plus(Duration.ofMinutes(5)),
                ),
            )
        val anotherIssuer =
            HmacTenantGrantAuthority(
                "another",
                authority,
                ByteArray(32) { 2 },
                clock = clock,
                maximumLifetime = Duration.ofHours(1),
            )

        assertThatThrownBy { anotherIssuer.verify(grant, acme, TenantOperation.ADMIN) }
            .isInstanceOf(TenantGrantRequiredException::class.java)
        clock.advance(Duration.ofMinutes(5))
        assertThatThrownBy { grants.verify(grant, acme, TenantOperation.ADMIN) }
            .isInstanceOf(TenantGrantExpiredException::class.java)
    }
}
