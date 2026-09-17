package com.gd.rain.tenancy

import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant

class HmacTenantAuthorityTest {
    private val ref = TenantRef.of("acme")
    private val resolver = MutableResolver(TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(1), 1))
    private val authority =
        HmacTenantAuthority(
            origin = "helpdesk",
            resolver = resolver,
            admission = TenantAdmission.DEFAULT,
            currentKey = ByteArray(32) { 1 },
            identityKey = ByteArray(32) { 2 },
            clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            random = SecureRandom(),
        )

    @Test
    fun `durable token is bound to exact job intent and current epoch`() {
        val scope = authority.lookup(ref, TenantOperation.DURABLE)
        val intent = intent("payload-one")
        val token = authority.durableSeal(scope, intent)

        authority.durableUnseal(token, intent)

        assertThatThrownBy { authority.durableUnseal(token, intent("payload-two")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("durable tenant token was transplanted")

        resolver.resolution = TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(2), 2)

        assertThatThrownBy { authority.durableUnseal(token, intent) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("durable tenant token is stale")
    }

    @Test
    fun `admission fails closed for read only writes`() {
        resolver.resolution = TenantResolution(ref, TenantLifecycle.READ_ONLY, TenantEpoch(1), 1)

        assertThatThrownBy { authority.lookup(ref, TenantOperation.WRITE) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("tenant operation is not admitted")
    }

    @Test
    fun `malformed request resolution does not degrade into a missing tenant`() {
        resolver.candidate = TenantCandidate.Malformed("principal")

        assertThatThrownBy { authority.resolveCurrent(EmptyContext, TenantOperation.READ) }
            .isSameAs(TenantRefusal.Malformed)
    }

    private fun intent(payload: String): DurableTenantIntent =
        DurableTenantIntent("jobs", "projection.pass", "11111111-1111-1111-1111-111111111111", sha256(payload))

    private fun sha256(value: String): ByteArray =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())

    private class MutableResolver(
        var resolution: TenantResolution,
    ) : TenantResolver {
        var candidate: TenantCandidate = TenantCandidate.Present(resolution, "test")

        override fun resolveCurrent(context: TenantRequestContext): TenantCandidate = candidate

        override fun lookup(ref: TenantRef): TenantResolution? = resolution.takeIf { it.ref == ref }
    }

    private data object EmptyContext : TenantRequestContext {
        override fun attribute(name: String): String? = null
    }
}
