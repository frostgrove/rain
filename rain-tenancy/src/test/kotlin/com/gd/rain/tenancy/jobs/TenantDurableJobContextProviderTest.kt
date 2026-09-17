package com.gd.rain.tenancy.jobs

import com.gd.rain.jobs.context.DurableJobContextCapture
import com.gd.rain.jobs.context.DurableJobContextPermanentException
import com.gd.rain.jobs.context.DurableJobContextRequest
import com.gd.rain.jobs.context.DurableJobContextRestoreRequest
import com.gd.rain.jobs.context.DurableJobContextUnavailableException
import com.gd.rain.jobs.context.JobPayloadDigest
import com.gd.rain.jobs.context.TenantBindingMode
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantAuthorityUnavailableException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

class TenantDurableJobContextProviderTest {
    private val ref: TenantRef = TenantRef.of("acme")
    private val resolver: MutableResolver = MutableResolver(TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(1), 1))
    private val authority: TenantAuthority =
        HmacTenantAuthority(
            "test",
            resolver,
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            identityKey = ByteArray(32) { 2 },
            clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            random = SecureRandom(),
        )

    @Test
    fun `signed capture binds only its exact job and restores no tenant after close`() {
        val provider = TenantDurableJobContextProvider(authority)
        val request = request()
        val captured =
            TenantContext.bind(authority.lookup(ref, TenantOperation.DURABLE)).use {
                provider.capture(request) as DurableJobContextCapture.Captured
            }

        assertThat(captured.producerPartition?.copy()).hasSize(32)
        provider.restore(DurableJobContextRestoreRequest(request, captured.fragment, captured.producerPartition)).use {
            assertThat(TenantContext.requireScope().epoch).isEqualTo(TenantEpoch(1))
        }
        assertThatThrownBy(TenantContext::requireScope).isInstanceOf(IllegalStateException::class.java)

        assertThatThrownBy {
            provider.restore(
                DurableJobContextRestoreRequest(
                    request.copy(invocation = UUID.randomUUID()),
                    captured.fragment,
                    captured.producerPartition,
                ),
            )
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
    }

    @Test
    fun `stale tenant is permanent while a resolver outage is retryable`() {
        val provider = TenantDurableJobContextProvider(authority)
        val request = request()
        val captured =
            TenantContext.bind(authority.lookup(ref, TenantOperation.DURABLE)).use {
                provider.capture(request) as DurableJobContextCapture.Captured
            }
        resolver.resolution = TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(2), 2)

        assertThatThrownBy {
            provider.restore(DurableJobContextRestoreRequest(request, captured.fragment, captured.producerPartition))
        }.isInstanceOf(DurableJobContextPermanentException::class.java)

        val unavailable =
            object : TenantAuthority by authority {
                override fun durableUnseal(
                    token: com.gd.rain.tenancy.DurableTenantToken,
                    intent: com.gd.rain.tenancy.DurableTenantIntent,
                ) = throw TenantAuthorityUnavailableException("control plane is down")
            }
        assertThatThrownBy {
            TenantDurableJobContextProvider(unavailable).restore(
                DurableJobContextRestoreRequest(request, captured.fragment, captured.producerPartition),
            )
        }.isInstanceOf(DurableJobContextUnavailableException::class.java)
    }

    @Test
    fun `missing ambient tenant stays absent so required policy is enforced by the neutral jobs kernel`() {
        val result = TenantDurableJobContextProvider(authority).capture(request(TenantBindingMode.REQUIRED))

        assertThat(result).isEqualTo(DurableJobContextCapture.Absent)
    }

    private fun request(mode: TenantBindingMode = TenantBindingMode.INHERIT): DurableJobContextRequest =
        DurableJobContextRequest(
            DurableJobContextRequest.JOBS_NAMESPACE,
            "notes.write",
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            JobPayloadDigest.of("payload".toByteArray()),
            mode,
        )

    private class MutableResolver(
        var resolution: TenantResolution,
    ) : TenantResolver {
        override fun resolveCurrent(context: TenantRequestContext): TenantCandidate = TenantCandidate.Present(resolution, "test")

        override fun lookup(ref: TenantRef): TenantResolution? = resolution.takeIf { it.ref == ref }
    }
}
