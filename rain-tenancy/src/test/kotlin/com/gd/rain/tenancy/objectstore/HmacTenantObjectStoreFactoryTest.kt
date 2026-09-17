package com.gd.rain.tenancy.objectstore

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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class HmacTenantObjectStoreFactoryTest {
    private val acme = TenantRef.of("acme")
    private val other = TenantRef.of("other")
    private val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
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

    @Test
    fun `scoped store derives opaque addresses and has no cross-tenant or static URL escape`() {
        val backend = MemoryBackend()
        val factory = HmacTenantObjectStoreFactory(authority, backend, ByteArray(32) { 2 }, clock, Duration.ofMinutes(5))
        val name = TenantObjectName.of("exports/report.json")
        val acmeStore = factory.forScope(authority.lookup(acme, TenantOperation.WRITE))

        acmeStore.put(name, TenantObjectBytes.of("{}".toByteArray(), "application/json"))

        assertThat(acmeStore.open(name)?.openStream()?.use(InputStream::readBytes)).containsExactly('{'.code.toByte(), '}'.code.toByte())
        assertThat(factory.forScope(authority.lookup(other, TenantOperation.READ)).open(name)).isNull()
        assertThat(backend.addresses.single().value).doesNotContain("acme", "report.json", "exports")
        val signed = requireNotNull(acmeStore.signedReadUrl(name, Duration.ofMinutes(1)))
        assertThat(signed.toString()).isEqualTo("tenant-object-signed-url[redacted]")
        assertThat(signed.value()).startsWith("https://objects.example.test/")
        assertThatThrownBy { TenantObjectName.of("../escape") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private class MemoryBackend : TenantObjectBackend {
        val addresses: MutableList<TenantObjectAddress> = mutableListOf()
        private val values: MutableMap<String, Stored> = mutableMapOf()

        override fun put(
            address: TenantObjectAddress,
            content: TenantObjectContent,
        ): TenantObjectMetadata {
            val bytes = content.openStream().use(InputStream::readBytes)
            require(bytes.size.toLong() == content.length)
            val metadata = TenantObjectMetadata(bytes.size.toLong(), content.mediaType, tenantObjectDigest(bytes))
            addresses += address
            values[address.value] = Stored(bytes, metadata)
            return metadata
        }

        override fun open(address: TenantObjectAddress): TenantObjectRead? =
            values[address.value]?.let { stored ->
                object : TenantObjectRead {
                    override val metadata: TenantObjectMetadata = stored.metadata

                    override fun openStream(): InputStream = ByteArrayInputStream(stored.bytes.copyOf())

                    override fun close() = Unit
                }
            }

        override fun delete(address: TenantObjectAddress) {
            values.remove(address.value)
        }

        override fun signRead(
            address: TenantObjectAddress,
            expiresAt: Instant,
        ): String? = "https://objects.example.test/${address.value}?expires=${expiresAt.epochSecond}"

        private data class Stored(
            val bytes: ByteArray,
            val metadata: TenantObjectMetadata,
        )
    }
}
