package com.gd.rain.tenancy

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Checks a resolution and mints the only value that can enter a tenant data plane. */
public interface TenantAuthority {
    public val origin: String

    public fun verify(
        resolution: TenantResolution,
        operation: TenantOperation,
    ): TenantScope

    public fun resolveCurrent(
        context: TenantRequestContext,
        operation: TenantOperation,
    ): TenantScope

    public fun lookup(
        ref: TenantRef,
        operation: TenantOperation,
    ): TenantScope

    /** Verifies a scope is from this authority and is still admitted for [operation]. */
    public fun current(
        scope: TenantScope,
        operation: TenantOperation,
    ): TenantScope

    public fun durableSeal(
        scope: TenantScope,
        intent: DurableTenantIntent,
    ): DurableTenantToken

    public fun durableUnseal(
        token: DurableTenantToken,
        intent: DurableTenantIntent,
    ): TenantScope
}

/**
 * Opaque, authority-bound tenant capability. It has no public constructor, raw reference accessor,
 * or deserializer. A scope is process-local and is rechecked before each data-plane entry.
 */
public class TenantScope internal constructor(
    internal val ref: TenantRef,
    public val epoch: TenantEpoch,
    public val lifecycle: TenantLifecycle,
    public val placementVersion: Long,
    internal val authorityOrigin: String,
    internal val binding: ByteArray,
    internal val issuedAt: Instant,
    internal val nonce: ByteArray,
    internal val diagnosticDigest: TenantDigest,
) {
    /** A tenant-specific seed for optional namespace adapters; it contains no raw tenant reference. */
    public fun namespaceSeed(): ByteArray = diagnosticDigest.copy()

    override fun toString(): String = "tenant-scope[$diagnosticDigest]"

    internal fun hasOrigin(origin: String): Boolean = authorityOrigin == origin
}

/** Lifecycle admission defaults. Production configuration can only narrow this matrix. */
public class TenantAdmission private constructor(
    private val admitted: Map<TenantLifecycle, Set<TenantOperation>>,
) {
    public fun allows(
        lifecycle: TenantLifecycle,
        operation: TenantOperation,
    ): Boolean = operation in admitted.getValue(lifecycle)

    /** Returns a narrower admission matrix; an expansion is refused. */
    public fun narrowedBy(allowed: Map<TenantLifecycle, Set<TenantOperation>>): TenantAdmission {
        require(allowed.keys == TenantLifecycle.entries.toSet()) { "a narrowed tenant admission declares every lifecycle" }
        allowed.forEach { (lifecycle, operations) ->
            require(operations.all { it in admitted.getValue(lifecycle) }) { "tenant admission may only narrow the default" }
            require(TenantOperation.WRITE !in operations || TenantOperation.READ in operations) {
                "tenant write admission implies read admission"
            }
        }
        return TenantAdmission(allowed.mapValues { (_, operations) -> operations.toSet() })
    }

    public companion object {
        public val DEFAULT: TenantAdmission =
            TenantAdmission(
                mapOf(
                    TenantLifecycle.PROVISIONING to setOf(TenantOperation.ADMIN),
                    TenantLifecycle.ACTIVE to TenantOperation.entries.toSet(),
                    TenantLifecycle.READ_ONLY to setOf(TenantOperation.READ, TenantOperation.ADMIN),
                    TenantLifecycle.SUSPENDED to setOf(TenantOperation.ADMIN),
                    TenantLifecycle.MIGRATING to setOf(TenantOperation.ADMIN),
                    TenantLifecycle.DELETING to setOf(TenantOperation.ADMIN),
                    TenantLifecycle.DELETED to emptySet(),
                ),
            )
    }
}

/** Exact durable-job binding covered by the authority's MAC. */
public data class DurableTenantIntent(
    public val jobNamespace: String,
    public val definition: String,
    public val invocation: String,
    public val payloadDigest: ByteArray,
) {
    init {
        require(NAME.matches(jobNamespace) && NAME.matches(definition)) { "durable tenant intent has an invalid namespace or definition" }
        require(invocation.toByteArray(Charsets.UTF_8).size in 1..128) { "durable invocation is out of bounds" }
        require(payloadDigest.size == 32) { "durable payload digest has ${payloadDigest.size} bytes, not 32" }
    }

    internal fun canonical(): ByteArray =
        listOf(jobNamespace, definition, invocation).joinToString("\u0000").toByteArray(Charsets.UTF_8) + byteArrayOf(0) + payloadDigest

    private companion object {
        val NAME: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** Versioned signed durable scope bytes. Its string form cannot disclose tenant or MAC material. */
public class DurableTenantToken internal constructor(
    private val value: ByteArray,
) {
    public fun copy(): ByteArray = value.copyOf()

    override fun toString(): String = "durable-tenant-token[redacted]"
}

/** HMAC-backed authority with explicit key rotation and constant-time scope verification. */
public class HmacTenantAuthority(
    override val origin: String,
    private val resolver: TenantResolver,
    private val admission: TenantAdmission,
    currentKey: ByteArray,
    retiredKeys: List<ByteArray> = emptyList(),
    identityKey: ByteArray = currentKey,
    private val clock: Clock,
    private val random: SecureRandom,
    private val revalidate: Boolean = true,
) : TenantAuthority {
    private val currentKey: ByteArray = checkedKey(currentKey)
    private val retiredKeys: List<ByteArray> = retiredKeys.map(::checkedKey)
    private val identityKey: ByteArray = checkedKey(identityKey)

    init {
        require(ORIGIN.matches(origin)) { "tenant authority origin is not stable" }
    }

    override fun verify(
        resolution: TenantResolution,
        operation: TenantOperation,
    ): TenantScope {
        require(admission.allows(resolution.lifecycle, operation)) { "tenant operation is not admitted" }
        val issuedAt = clock.instant()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val digest = TenantDigest(hmac(identityKey, "rain.tenant.digest.v1", resolution.ref.bytes()))
        val binding = hmac(currentKey, "rain.tenant.scope.v1", scopePayload(resolution, issuedAt, nonce))
        return TenantScope(
            ref = resolution.ref,
            epoch = resolution.epoch,
            lifecycle = resolution.lifecycle,
            placementVersion = resolution.placementVersion,
            authorityOrigin = origin,
            binding = binding,
            issuedAt = issuedAt,
            nonce = nonce,
            diagnosticDigest = digest,
        )
    }

    override fun resolveCurrent(
        context: TenantRequestContext,
        operation: TenantOperation,
    ): TenantScope =
        when (val candidate = resolver.resolveCurrent(context)) {
            TenantCandidate.Absent -> throw TenantRefusal.Required
            is TenantCandidate.Malformed -> throw TenantRefusal.Malformed
            is TenantCandidate.Present -> verify(candidate.resolution, operation)
        }

    override fun lookup(
        ref: TenantRef,
        operation: TenantOperation,
    ): TenantScope = verify(checkNotNull(resolver.lookup(ref)) { "tenant was not found" }, operation)

    override fun current(
        scope: TenantScope,
        operation: TenantOperation,
    ): TenantScope {
        require(scope.hasOrigin(origin)) { "tenant scope belongs to another origin" }
        val verified = listOf(currentKey) + retiredKeys
        require(verified.any { key -> MessageDigest.isEqual(scope.binding, hmac(key, "rain.tenant.scope.v1", scopePayload(scope))) }) {
            "tenant scope binding is invalid"
        }
        if (!revalidate) {
            require(admission.allows(scope.lifecycle, operation)) { "tenant operation is not admitted" }
            return scope
        }
        val resolved = checkNotNull(resolver.lookup(scope.ref)) { "tenant was not found" }
        require(resolved.epoch == scope.epoch && resolved.placementVersion == scope.placementVersion) { "tenant scope is stale" }
        require(resolved.lifecycle == scope.lifecycle) { "tenant lifecycle changed" }
        require(admission.allows(resolved.lifecycle, operation)) { "tenant operation is not admitted" }
        return scope
    }

    override fun durableSeal(
        scope: TenantScope,
        intent: DurableTenantIntent,
    ): DurableTenantToken {
        current(scope, TenantOperation.DURABLE)
        val payload = durablePayload(scope, intent)
        return DurableTenantToken(payload + hmac(currentKey, "rain.tenant.durable.v1", payload))
    }

    override fun durableUnseal(
        token: DurableTenantToken,
        intent: DurableTenantIntent,
    ): TenantScope {
        val raw = token.copy()
        require(raw.size > MAC_BYTES) { "durable tenant token is malformed" }
        val payload = raw.copyOfRange(0, raw.size - MAC_BYTES)
        val mac = raw.copyOfRange(raw.size - MAC_BYTES, raw.size)
        val macIsValid =
            (listOf(currentKey) + retiredKeys).any {
                MessageDigest.isEqual(mac, hmac(it, "rain.tenant.durable.v1", payload))
            }
        require(macIsValid) { "durable tenant token binding is invalid" }
        val decoded = decodeDurablePayload(payload, intent)
        val fresh = checkNotNull(resolver.lookup(decoded.ref)) { "tenant was not found" }
        require(fresh.epoch == decoded.epoch && fresh.placementVersion == decoded.placementVersion) {
            "durable tenant token is stale"
        }
        return verify(fresh, TenantOperation.DURABLE)
    }

    private fun scopePayload(scope: TenantScope): ByteArray =
        scopePayload(TenantResolution(scope.ref, scope.lifecycle, scope.epoch, scope.placementVersion), scope.issuedAt, scope.nonce)

    private fun scopePayload(
        resolution: TenantResolution,
        issuedAt: Instant,
        nonce: ByteArray,
    ): ByteArray =
        listOf(
            "rain.tenant.scope.payload.v1".toByteArray(Charsets.UTF_8),
            origin.toByteArray(Charsets.UTF_8),
            resolution.ref.bytes(),
            resolution.lifecycle.name.toByteArray(Charsets.UTF_8),
            long(resolution.epoch.value),
            long(resolution.placementVersion),
            long(issuedAt.epochSecond),
            long(issuedAt.nano.toLong()),
            nonce,
        ).joinWithNul()

    private fun durablePayload(
        scope: TenantScope,
        intent: DurableTenantIntent,
    ): ByteArray =
        listOf(
            "rain.tenant.durable.payload.v1".toByteArray(Charsets.UTF_8),
            origin.toByteArray(Charsets.UTF_8),
            scope.ref.bytes(),
            scope.lifecycle.name.toByteArray(Charsets.UTF_8),
            scope.epoch.value
                .toString()
                .toByteArray(Charsets.UTF_8),
            scope.placementVersion.toString().toByteArray(Charsets.UTF_8),
            Base64.getUrlEncoder().withoutPadding().encode(intent.canonical()),
        ).joinWithNul()

    private fun decodeDurablePayload(
        payload: ByteArray,
        intent: DurableTenantIntent,
    ): TenantResolution {
        val fields = payload.splitNul()
        require(fields.size == DURABLE_FIELDS) { "durable tenant token has an unknown format" }
        require(fields[0].toStringUtf8() == "rain.tenant.durable.payload.v1" && fields[1].toStringUtf8() == origin) {
            "durable tenant token belongs to another origin"
        }
        require(
            MessageDigest.isEqual(
                fields[6],
                Base64.getUrlEncoder().withoutPadding().encode(intent.canonical()),
            ),
        ) { "durable tenant token was transplanted" }
        return TenantResolution(
            ref = TenantRef.of(fields[2].toStringUtf8()),
            lifecycle = TenantLifecycle.valueOf(fields[3].toStringUtf8()),
            epoch = TenantEpoch(fields[4].toStringUtf8().toLong()),
            placementVersion = fields[5].toStringUtf8().toLong(),
        )
    }

    private fun checkedKey(key: ByteArray): ByteArray {
        require(key.size >= MAC_BYTES) { "tenant authority HMAC key is at least $MAC_BYTES bytes" }
        return key.copyOf()
    }

    private fun hmac(
        key: ByteArray,
        domain: String,
        payload: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(domain.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + payload)
        }

    private companion object {
        const val MAC_BYTES: Int = 32
        const val NONCE_BYTES: Int = 16
        const val DURABLE_FIELDS: Int = 7
        val ORIGIN: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** Closed scope failures that can be translated without leaking resolution input. */
public sealed class TenantRefusal(
    message: String,
) : IllegalStateException(message) {
    public data object Required : TenantRefusal("tenant is required")

    /** A candidate source disagreed or could not parse its input; it must never fall through to central work. */
    public data object Malformed : TenantRefusal("tenant selection is malformed")

    /** A route declared central work but the request carried a valid tenant selection. */
    public data object CentralRouteSelected : TenantRefusal("this route is central and does not accept a tenant selection")
}

private fun long(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

private fun List<ByteArray>.joinWithNul(): ByteArray =
    flatMapIndexed { index, value ->
        if (index ==
            0
        ) {
            value.asIterable()
        } else {
            byteArrayOf(0).asIterable() + value.asIterable()
        }
    }.toByteArray()

private fun ByteArray.splitNul(): List<ByteArray> {
    val result = mutableListOf<ByteArray>()
    var start = 0
    forEachIndexed { index, byte ->
        if (byte == 0.toByte()) {
            result += copyOfRange(start, index)
            start = index + 1
        }
    }
    result += copyOfRange(start, size)
    return result
}

private fun ByteArray.toStringUtf8(): String = toString(Charsets.UTF_8)
