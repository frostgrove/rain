package com.gd.rain.tenancy.objectstore

import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A normalized logical name that cannot escape a tenant-owned object namespace. */
public class TenantObjectName private constructor(
    private val value: String,
) {
    override fun toString(): String = "tenant-object-name[redacted]"

    internal fun bytes(): ByteArray = value.toByteArray(Charsets.UTF_8)

    public companion object {
        public const val MAX_BYTES: Int = 512

        public fun of(value: String): TenantObjectName {
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            require(normalized.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES) { "tenant object name is out of bounds" }
            require(normalized.none { it.isISOControl() || it == '\\' }) { "tenant object name contains a forbidden character" }
            val segments = normalized.split('/')
            require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) { "tenant object name escapes its namespace" }
            return TenantObjectName(normalized)
        }
    }
}

/** A streaming write with a declared content length. The store owns the stream only for the call. */
public interface TenantObjectContent {
    public val length: Long
    public val mediaType: String?

    public fun openStream(): InputStream
}

/** Small-object convenience; production adapters can stream arbitrary bounded content through [TenantObjectContent]. */
public class TenantObjectBytes private constructor(
    private val value: ByteArray,
    override val mediaType: String?,
) : TenantObjectContent {
    override val length: Long get() = value.size.toLong()

    override fun openStream(): InputStream = ByteArrayInputStream(value.copyOf())

    public fun copy(): ByteArray = value.copyOf()

    public companion object {
        public const val MAX_BYTES: Int = 16 * 1024 * 1024

        public fun of(
            value: ByteArray,
            mediaType: String? = null,
        ): TenantObjectBytes {
            require(value.size <= MAX_BYTES) { "in-memory tenant object is too large" }
            require(mediaType == null || MEDIA_TYPE.matches(mediaType)) { "tenant object media type is malformed" }
            return TenantObjectBytes(value.copyOf(), mediaType)
        }

        private val MEDIA_TYPE: Regex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    }
}

/** Non-secret object metadata; a digest lets applications verify content without exposing its name. */
public class TenantObjectMetadata(
    public val length: Long,
    public val mediaType: String?,
    digest: ByteArray,
) {
    private val digest: ByteArray = digest.copyOf()

    init {
        require(length >= 0) { "tenant object length is non-negative" }
        require(mediaType == null || MEDIA_TYPE.matches(mediaType)) { "tenant object media type is malformed" }
        require(this.digest.size == DIGEST_BYTES) { "tenant object digest has 32 bytes" }
    }

    public fun digest(): ByteArray = digest.copyOf()

    private companion object {
        const val DIGEST_BYTES: Int = 32
        val MEDIA_TYPE: Regex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    }
}

/** A backend read. Its stream must be closed by the caller. */
public interface TenantObjectRead : AutoCloseable {
    public val metadata: TenantObjectMetadata

    public fun openStream(): InputStream
}

/** A signed URL is intentionally redacted from ordinary logs and has an explicit expiry. */
public class TenantObjectSignedUrl internal constructor(
    private val value: String,
    public val expiresAt: Instant,
) {
    public fun value(): String = value

    override fun toString(): String = "tenant-object-signed-url[redacted]"
}

/** Opaque backend address derived from scope, epoch, and object name. */
public class TenantObjectAddress internal constructor(
    public val value: String,
) {
    override fun toString(): String = value
}

/** Provider port. It receives only opaque addresses, never a tenant reference or logical path. */
public interface TenantObjectBackend {
    public fun put(
        address: TenantObjectAddress,
        content: TenantObjectContent,
    ): TenantObjectMetadata

    public fun open(address: TenantObjectAddress): TenantObjectRead?

    public fun delete(address: TenantObjectAddress): Unit

    /** Null means this backend intentionally does not support signed URLs. */
    public fun signRead(
        address: TenantObjectAddress,
        expiresAt: Instant,
    ): String?
}

/** Scope-bound object operations. There is no static/public URL operation in this API. */
public interface TenantObjectStore {
    public fun put(
        name: TenantObjectName,
        content: TenantObjectContent,
    ): TenantObjectMetadata

    public fun open(name: TenantObjectName): TenantObjectRead?

    public fun delete(name: TenantObjectName): Unit

    public fun signedReadUrl(
        name: TenantObjectName,
        ttl: Duration,
    ): TenantObjectSignedUrl?
}

/** Explicit scoped factory for both runtime-task and low-level data-plane use. */
public fun interface TenantObjectStoreFactory {
    public fun forScope(scope: TenantScope): TenantObjectStore
}

/**
 * HMAC namespace adapter over an application-selected object backend. Write/delete revalidate
 * WRITE admission; reads and signed URLs revalidate READ admission at each operation.
 */
public class HmacTenantObjectStoreFactory(
    private val authority: TenantAuthority,
    private val backend: TenantObjectBackend,
    key: ByteArray,
    private val clock: Clock,
    private val maximumSignedUrlTtl: Duration,
) : TenantObjectStoreFactory {
    private val key: ByteArray =
        key.copyOf().also {
            require(
                it.size >= KEY_BYTES,
            ) { "tenant object HMAC key is at least $KEY_BYTES bytes" }
        }

    init {
        require(!maximumSignedUrlTtl.isNegative && !maximumSignedUrlTtl.isZero) { "tenant signed URL maximum TTL is positive" }
    }

    override fun forScope(scope: TenantScope): TenantObjectStore {
        authority.current(scope, TenantOperation.READ)
        return ScopedStore(scope)
    }

    private inner class ScopedStore(
        private val scope: TenantScope,
    ) : TenantObjectStore {
        override fun put(
            name: TenantObjectName,
            content: TenantObjectContent,
        ): TenantObjectMetadata {
            authority.current(scope, TenantOperation.WRITE)
            require(content.length >= 0) { "tenant object content length is non-negative" }
            return backend.put(address(scope, name), content)
        }

        override fun open(name: TenantObjectName): TenantObjectRead? {
            authority.current(scope, TenantOperation.READ)
            return backend.open(address(scope, name))
        }

        override fun delete(name: TenantObjectName) {
            authority.current(scope, TenantOperation.WRITE)
            backend.delete(address(scope, name))
        }

        override fun signedReadUrl(
            name: TenantObjectName,
            ttl: Duration,
        ): TenantObjectSignedUrl? {
            authority.current(scope, TenantOperation.READ)
            require(!ttl.isNegative && !ttl.isZero && ttl <= maximumSignedUrlTtl) { "tenant signed URL TTL is out of bounds" }
            val expiry = clock.instant().plus(ttl)
            return backend.signRead(address(scope, name), expiry)?.let { TenantObjectSignedUrl(it, expiry) }
        }
    }

    private fun address(
        scope: TenantScope,
        name: TenantObjectName,
    ): TenantObjectAddress {
        val namespace = hmac(NAMESPACE_DOMAIN, scope.namespaceSeed() + long(scope.epoch.value))
        val objectKey = hmac(OBJECT_DOMAIN, namespace + byteArrayOf(0) + name.bytes())
        return TenantObjectAddress(
            "rain/tenant/${Base64.getUrlEncoder().withoutPadding().encodeToString(namespace)}/${scope.epoch.value}/" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(objectKey),
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
        val NAMESPACE_DOMAIN: ByteArray = "rain.tenant.object.namespace.v1".toByteArray(Charsets.UTF_8)
        val OBJECT_DOMAIN: ByteArray = "rain.tenant.object.entry.v1".toByteArray(Charsets.UTF_8)

        fun long(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
    }
}

/** Utility for test kits and adapters which need a SHA-256 digest for metadata. */
public fun tenantObjectDigest(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
