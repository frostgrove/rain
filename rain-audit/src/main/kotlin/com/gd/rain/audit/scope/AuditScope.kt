package com.gd.rain.audit.scope

import java.util.Arrays

/**
 * A bounded, non-secret partition of the audit trail. It deliberately carries an opaque digest,
 * never a tenant reference or an application-owned identifier that could leak through evidence.
 */
public class AuditScope private constructor(
    public val kind: String,
    private val digest: ByteArray,
    public val epoch: Long,
) {
    public fun digest(): ByteArray = digest.copyOf()

    override fun equals(other: Any?): Boolean =
        other is AuditScope && kind == other.kind && epoch == other.epoch && Arrays.equals(digest, other.digest)

    override fun hashCode(): Int = 31 * (31 * kind.hashCode() + epoch.hashCode()) + Arrays.hashCode(digest)

    override fun toString(): String = "audit-scope[$kind, epoch=$epoch, digest=redacted]"

    public companion object {
        public const val MAX_DIGEST_BYTES: Int = 64

        public fun of(
            kind: String,
            digest: ByteArray,
            epoch: Long,
        ): AuditScope {
            require(KIND.matches(kind)) { "an audit scope kind is not stable" }
            require(digest.size in 1..MAX_DIGEST_BYTES) { "an audit scope digest has 1..$MAX_DIGEST_BYTES bytes" }
            require(epoch > 0) { "an audit scope epoch is positive" }
            return AuditScope(kind, digest.copyOf(), epoch)
        }

        private val KIND: Regex = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    }
}

/**
 * Optional current-scope source. It must return null outside its own admitted runtime rather than
 * deriving a scope from request text or a raw tenant id.
 */
public fun interface AuditScopeContributor {
    public fun current(): AuditScope?
}
