package com.gd.rain.tenancy.resolution

import com.gd.rain.tenancy.TenantCandidate
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantRequestContext
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.web.ServletTenantRequestContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class TenantResolutionSourcesTest {
    private val resolution = TenantResolution(TenantRef.of("acme"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)

    @Test
    fun `principal source relabels a plain resolution signal with its stable source id`() {
        val source = PrincipalTenantResolutionSource("principal") { TenantResolutionSignal.Resolved(resolution) }

        assertThat(source.resolve(PlainContext)).isEqualTo(TenantCandidate.Present(resolution, "principal"))
    }

    @Test
    fun `canonical host normalizes IDNA and port before application domain lookup`() {
        var lookedUp: TenantCanonicalHost? = null
        val source =
            CanonicalHostTenantResolutionSource("canonical-host") { host ->
                lookedUp = host
                resolution
            }

        assertThat(source.resolve(HostContext(TenantHostAuthority("TÉNANT.Example.", 443))))
            .isEqualTo(TenantCandidate.Present(resolution, "canonical-host"))
        assertThat(lookedUp?.lookupKey).isEqualTo("xn--tnant-bsa.example")
        assertThat(lookedUp?.port).isEqualTo(443)
        assertThat(lookedUp.toString()).doesNotContain("example")
    }

    @Test
    fun `host source rejects malformed authority and ignores a context with no host transport capability`() {
        val source = CanonicalHostTenantResolutionSource("canonical-host") { resolution }

        listOf(
            TenantHostAuthority("example.test", 0),
            TenantHostAuthority(" example.test", 443),
            TenantHostAuthority("2001:::1", 443),
        ).forEach { authority ->
            assertThat(source.resolve(HostContext(authority)))
                .isEqualTo(TenantCandidate.Malformed("canonical-host"))
        }
        assertThat(source.resolve(PlainContext)).isEqualTo(TenantCandidate.Absent)
    }

    @Test
    fun `trusted header source receives headers only through its explicit verifier`() {
        val source =
            TrustedHeaderTenantResolutionSource("signed-header") { context ->
                if (context.headers("X-Tenant-Proof") == listOf("valid")) {
                    TenantResolutionSignal.Resolved(resolution)
                } else {
                    TenantResolutionSignal.Malformed
                }
            }
        val request =
            MockHttpServletRequest().apply {
                serverName = "edge.example"
                serverPort = 443
                addHeader("X-Tenant-Proof", "valid")
            }

        assertThat(source.resolve(ServletTenantRequestContext(request)))
            .isEqualTo(TenantCandidate.Present(resolution, "signed-header"))
        assertThat(source.resolve(PlainContext)).isEqualTo(TenantCandidate.Absent)
    }

    private data object PlainContext : TenantRequestContext {
        override fun attribute(name: String): String? = null
    }

    private class HostContext(
        private val host: TenantHostAuthority,
    ) : TenantHostRequestContext {
        override fun authority(): TenantHostAuthority = host

        override fun attribute(name: String): String? = null
    }
}
