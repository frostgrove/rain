package com.gd.rain.tenancy.cache

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
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class HmacTenantCacheFactoryTest {
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
            clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z")),
            random = SecureRandom(),
        )

    @Test
    fun `cache key is tenant opaque and a generation advance makes previous entries unreachable`() {
        val backend = MemoryBackend()
        var generation = TenantCacheGeneration(0)
        val factory = HmacTenantCacheFactory(authority, TenantCacheGenerationSource { generation }, backend, ByteArray(32) { 2 })
        val cacheName = TenantCacheName("profile")
        val key = TenantCacheKey.of("logical-user-key")
        val acmeCache = factory.forScope(authority.lookup(acme, TenantOperation.READ))

        acmeCache.put(cacheName, key, byteArrayOf(7), Duration.ofMinutes(1))

        assertThat(acmeCache.get(cacheName, key)).containsExactly(7)
        assertThat(factory.forScope(authority.lookup(other, TenantOperation.READ)).get(cacheName, key)).isNull()
        assertThat(backend.keys.single().value).doesNotContain("acme", "logical-user-key")
        generation = TenantCacheGeneration(1)
        assertThat(acmeCache.get(cacheName, key)).isNull()
    }

    private class MemoryBackend : TenantCacheBackend {
        val keys: MutableList<TenantCacheBackendKey> = mutableListOf()
        private val values: MutableMap<String, ByteArray> = mutableMapOf()

        override fun get(key: TenantCacheBackendKey): ByteArray? = values[key.value]?.copyOf()

        override fun put(
            key: TenantCacheBackendKey,
            value: ByteArray,
            ttl: Duration,
        ) {
            keys += key
            values[key.value] = value.copyOf()
        }

        override fun evict(key: TenantCacheBackendKey) {
            values.remove(key.value)
        }
    }
}
