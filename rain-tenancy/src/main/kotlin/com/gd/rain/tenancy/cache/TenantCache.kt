package com.gd.rain.tenancy.cache

import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRuntime
import com.gd.rain.tenancy.TenantRuntimeLease
import com.gd.rain.tenancy.TenantRuntimeTask
import com.gd.rain.tenancy.TenantScope
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A declared cache name. A tenant cache can never accidentally address an application-global name. */
@JvmInline
public value class TenantCacheName(
    public val value: String,
) {
    init {
        require(NAME.matches(value)) { "tenant cache name is not stable" }
    }

    private companion object {
        val NAME: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** A caller-local cache key; the raw bytes never leave the scoped cache adapter. */
public class TenantCacheKey private constructor(
    private val value: ByteArray,
) {
    public fun copy(): ByteArray = value.copyOf()

    override fun toString(): String = "tenant-cache-key[redacted]"

    public companion object {
        public const val MAX_BYTES: Int = 1024

        public fun of(value: String): TenantCacheKey = of(value.toByteArray(Charsets.UTF_8))

        public fun of(value: ByteArray): TenantCacheKey {
            require(value.isNotEmpty() && value.size <= MAX_BYTES) { "tenant cache key is out of bounds" }
            return TenantCacheKey(value.copyOf())
        }
    }
}

/** Monotonic control-plane generation; advancing it makes every previous tenant namespace unreachable. */
@JvmInline
public value class TenantCacheGeneration(
    public val value: Long,
) {
    init {
        require(value >= 0) { "tenant cache generation is non-negative" }
    }
}

/** Supplies the current generation without granting generic control-plane mutation access. */
public fun interface TenantCacheGenerationSource {
    public fun generation(scope: TenantScope): TenantCacheGeneration
}

/** Opaque backend address derived entirely by Rain; it has no raw tenant or caller key component. */
public class TenantCacheBackendKey internal constructor(
    public val value: String,
) {
    override fun toString(): String = value
}

/** The selected cache provider. Provider failures propagate; a tenant cache never falls back to global storage. */
public interface TenantCacheBackend {
    public fun get(key: TenantCacheBackendKey): ByteArray?

    public fun put(
        key: TenantCacheBackendKey,
        value: ByteArray,
        ttl: Duration,
    ): Unit

    public fun evict(key: TenantCacheBackendKey): Unit
}

/** A cache restricted to an already-issued tenant scope. */
public interface TenantCache {
    public fun get(
        cache: TenantCacheName,
        key: TenantCacheKey,
    ): ByteArray?

    public fun put(
        cache: TenantCacheName,
        key: TenantCacheKey,
        value: ByteArray,
        ttl: Duration,
    ): Unit

    public fun evict(
        cache: TenantCacheName,
        key: TenantCacheKey,
    ): Unit
}

/** Explicit factory for scoped cache use in a tenant runtime task or low-level data-plane callback. */
public fun interface TenantCacheFactory {
    public fun forScope(scope: TenantScope): TenantCache
}

/**
 * HMAC namespace cache adapter. The backend sees a deployment-scoped opaque namespace, epoch and
 * generation, then an HMAC entry key; it never sees a raw tenant reference or caller cache key.
 */
public class HmacTenantCacheFactory(
    private val authority: TenantAuthority,
    private val generations: TenantCacheGenerationSource,
    private val backend: TenantCacheBackend,
    key: ByteArray,
) : TenantCacheFactory {
    private val key: ByteArray =
        key.copyOf().also {
            require(
                it.size >= KEY_BYTES,
            ) { "tenant cache HMAC key is at least $KEY_BYTES bytes" }
        }

    override fun forScope(scope: TenantScope): TenantCache {
        authority.current(scope, TenantOperation.READ)
        return ScopedTenantCache(scope)
    }

    private inner class ScopedTenantCache(
        private val scope: TenantScope,
    ) : TenantCache {
        override fun get(
            cache: TenantCacheName,
            key: TenantCacheKey,
        ): ByteArray? = backend.get(address(scope, cache, key))?.copyOf()

        override fun put(
            cache: TenantCacheName,
            key: TenantCacheKey,
            value: ByteArray,
            ttl: Duration,
        ) {
            require(value.size <= MAX_VALUE_BYTES) { "tenant cache value is out of bounds" }
            require(!ttl.isNegative && !ttl.isZero) { "tenant cache TTL is positive" }
            backend.put(address(scope, cache, key), value.copyOf(), ttl)
        }

        override fun evict(
            cache: TenantCacheName,
            key: TenantCacheKey,
        ) {
            backend.evict(address(scope, cache, key))
        }
    }

    private fun address(
        scope: TenantScope,
        cache: TenantCacheName,
        key: TenantCacheKey,
    ): TenantCacheBackendKey {
        authority.current(scope, TenantOperation.READ)
        val generation = generations.generation(scope)
        val namespace =
            hmac(
                NAMESPACE_DOMAIN,
                scope.namespaceSeed() + long(scope.epoch.value) + long(generation.value),
            )
        val entry = hmac(ENTRY_DOMAIN, namespace + byteArrayOf(0) + cache.value.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + key.copy())
        return TenantCacheBackendKey(
            "rain:tenant:${Base64.getUrlEncoder().withoutPadding().encodeToString(namespace)}:${scope.epoch.value}:${generation.value}:" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(entry),
        )
    }

    private fun hmac(
        domain: ByteArray,
        payload: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(domain + byteArrayOf(0) + payload)
        }

    private companion object {
        const val KEY_BYTES: Int = 32
        const val MAX_VALUE_BYTES: Int = 1_048_576
        val NAMESPACE_DOMAIN: ByteArray = "rain.tenant.cache.namespace.v1".toByteArray(Charsets.UTF_8)
        val ENTRY_DOMAIN: ByteArray = "rain.tenant.cache.entry.v1".toByteArray(Charsets.UTF_8)

        fun long(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
    }
}

/** Installs the same explicit cache adapter into magic runtime work without changing global caching. */
public class TenantCacheRuntimeTask(
    private val factory: TenantCacheFactory,
) : TenantRuntimeTask {
    override val id: String = "tenant-cache"

    override fun enter(runtime: TenantRuntime): TenantRuntimeLease {
        val cache = factory.forScope(runtime.scope)
        runtime.installCache(cache)
        return TenantRuntimeLease { runtime.uninstallCache(cache) }
    }
}
