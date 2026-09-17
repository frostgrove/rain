package com.gd.rain.tenancy.grants

import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantGrantVerifier
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantScope
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The only input from an administrative grant command; it cannot ask for an unbounded tenant walk. */
public data class TenantGrantRequest(
    public val purpose: String,
    public val operations: Set<TenantOperation>,
    public val cohort: Set<TenantRef>,
    public val expiresAt: Instant,
) {
    init {
        require(purpose.toByteArray(Charsets.UTF_8).size in 1..256) { "tenant grant purpose is out of bounds" }
        require(purpose.none { it.isISOControl() }) { "tenant grant purpose contains a control character" }
        require(operations.isNotEmpty()) { "tenant grant declares an operation" }
        require(cohort.isNotEmpty() && cohort.size <= MAX_COHORT) { "tenant grant cohort is out of bounds" }
    }

    private companion object {
        const val MAX_COHORT: Int = 10_000
    }
}

/** Issues a bounded, signed cohort grant after every target is currently administratively admitted. */
public fun interface TenantGrantIssuer {
    public fun issue(request: TenantGrantRequest): TenantGrant
}

/** A refused, missing, or wrong-purpose cohort authorization; no tenant reference is disclosed. */
public class TenantGrantRequiredException : IllegalStateException("tenant grant does not authorize this operation")

/** A grant expired before a tenant data-plane unit could be entered. */
public class TenantGrantExpiredException : IllegalStateException("tenant grant has expired")

/**
 * HMAC grant authority with current/retired verification keys. The binding covers every cohort
 * member, operation, expiry and purpose in a deterministic byte form, so a token cannot be
 * broadened, transplanted to another issuer, or reinterpreted as a different fleet operation.
 */
public class HmacTenantGrantAuthority(
    public val issuer: String,
    private val authority: TenantAuthority,
    currentKey: ByteArray,
    retiredKeys: List<ByteArray> = emptyList(),
    private val clock: Clock,
    private val maximumLifetime: Duration,
) : TenantGrantIssuer,
    TenantGrantVerifier {
    private val currentKey: ByteArray = checkedKey(currentKey)
    private val retiredKeys: List<ByteArray> = retiredKeys.map(::checkedKey)

    init {
        require(ISSUER.matches(issuer)) { "tenant grant issuer is not stable" }
        require(!maximumLifetime.isNegative && !maximumLifetime.isZero) { "tenant grant maximum lifetime is positive" }
    }

    override fun issue(request: TenantGrantRequest): TenantGrant {
        val now = clock.instant()
        require(request.expiresAt.isAfter(now) && !request.expiresAt.isAfter(now.plus(maximumLifetime))) {
            "tenant grant expiry is outside its maximum lifetime"
        }
        val cohort = request.cohort.toSet()
        cohort.forEach { authority.lookup(it, TenantOperation.ADMIN) }
        val binding = hmac(currentKey, payload(issuer, request.purpose, request.operations, cohort, request.expiresAt))
        return TenantGrant(issuer, request.purpose, request.operations.toSet(), request.expiresAt, cohort, binding)
    }

    override fun verify(
        grant: TenantGrant,
        tenant: TenantRef,
        operation: TenantOperation,
    ): TenantScope {
        if (!grant.expiresAt.isAfter(clock.instant())) throw TenantGrantExpiredException()
        if (grant.issuer != issuer || tenant !in grant.cohort || operation !in grant.operations) {
            throw TenantGrantRequiredException()
        }
        val payload = payload(grant.issuer, grant.purpose, grant.operations, grant.cohort, grant.expiresAt)
        val valid = (listOf(currentKey) + retiredKeys).any { key -> MessageDigest.isEqual(grant.binding, hmac(key, payload)) }
        if (!valid) throw TenantGrantRequiredException()
        return authority.lookup(tenant, operation)
    }

    private fun payload(
        issuer: String,
        purpose: String,
        operations: Set<TenantOperation>,
        cohort: Set<TenantRef>,
        expiresAt: Instant,
    ): ByteArray =
        sequenceOf(
            FORMAT,
            issuer.toByteArray(Charsets.UTF_8),
            purpose.toByteArray(Charsets.UTF_8),
            operations
                .map(TenantOperation::name)
                .sorted()
                .joinToString(",")
                .toByteArray(Charsets.UTF_8),
            expiresAt.epochSecond.toString().toByteArray(Charsets.UTF_8),
            expiresAt.nano.toString().toByteArray(Charsets.UTF_8),
        ).plus(cohort.map(TenantRef::bytes).sortedWith(BYTE_ARRAY_ORDER)).joinWithNul()

    private fun hmac(
        key: ByteArray,
        payload: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(DOMAIN + byteArrayOf(0) + payload)
        }

    private fun checkedKey(key: ByteArray): ByteArray {
        require(key.size >= KEY_BYTES) { "tenant grant HMAC key is at least $KEY_BYTES bytes" }
        return key.copyOf()
    }

    private companion object {
        const val KEY_BYTES: Int = 32
        val FORMAT: ByteArray = "rain.tenant.grant.payload.v1".toByteArray(Charsets.UTF_8)
        val DOMAIN: ByteArray = "rain.tenant.grant.binding.v1".toByteArray(Charsets.UTF_8)
        val ISSUER: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
        val BYTE_ARRAY_ORDER: Comparator<ByteArray> =
            Comparator { left, right ->
                left.zip(right).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.toInt().compareTo(b.toInt()) }
                    ?: left.size.compareTo(right.size)
            }
    }
}

private fun Sequence<ByteArray>.joinWithNul(): ByteArray =
    flatMapIndexed { index, value ->
        if (index == 0) value.asIterable() else byteArrayOf(0).asIterable() + value.asIterable()
    }.toList().toByteArray()
