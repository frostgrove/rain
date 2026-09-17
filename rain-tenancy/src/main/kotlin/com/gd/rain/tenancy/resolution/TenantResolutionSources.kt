package com.gd.rain.tenancy.resolution

import com.gd.rain.tenancy.TENANT_RESOLUTION_SOURCE_ID
import com.gd.rain.tenancy.TenantCandidate
import com.gd.rain.tenancy.TenantRequestContext
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolutionSource
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/** A source may prove a resolution, be absent, or refuse malformed input; it cannot mint a scope. */
public sealed interface TenantResolutionSignal {
    public data object Absent : TenantResolutionSignal

    public data object Malformed : TenantResolutionSignal

    public data class Resolved(
        public val resolution: TenantResolution,
    ) : TenantResolutionSignal
}

/** Application-owned principal lookup, deliberately independent of a particular security framework. */
public fun interface TenantPrincipalResolver {
    public fun resolve(context: TenantRequestContext): TenantResolutionSignal
}

/**
 * Principal adapter for a composite resolver. A principal integration can return only a plain
 * resolution signal, never an already-admitted [com.gd.rain.tenancy.TenantScope].
 */
public class PrincipalTenantResolutionSource(
    override val id: String,
    private val principals: TenantPrincipalResolver,
) : TenantResolutionSource {
    init {
        require(TENANT_RESOLUTION_SOURCE_ID.matches(id)) { "tenant source id is not stable" }
    }

    override fun resolve(context: TenantRequestContext): TenantCandidate = principals.resolve(context).candidate(id)
}

/** Servlet-like transport contexts may expose a parsed authority to the canonical-host source. */
public interface TenantHostRequestContext : TenantRequestContext {
    /** Parsed host and port after the hosting transport's forwarding policy has run. */
    public fun authority(): TenantHostAuthority?
}

/** A transport-provided host and port. Its string form is deliberately redacted. */
public class TenantHostAuthority(
    public val host: String,
    public val port: Int,
) {
    override fun toString(): String = "tenant-host-authority[redacted]"
}

/**
 * Canonical hostname used only for a domain-directory lookup. [lookupKey] is intentionally not a
 * `toString` value: integrations must not accidentally put it into an error, log, or metric label.
 */
public class TenantCanonicalHost private constructor(
    public val lookupKey: String,
    public val port: Int,
) {
    override fun equals(other: Any?): Boolean = other is TenantCanonicalHost && lookupKey == other.lookupKey && port == other.port

    override fun hashCode(): Int = 31 * lookupKey.hashCode() + port

    override fun toString(): String = "tenant-canonical-host[redacted]"

    public companion object {
        /** Lower-cases IDNA domains, accepts canonical IP literals, and rejects malformed authority syntax. */
        public fun parse(authority: TenantHostAuthority): TenantCanonicalHost? {
            if (authority.port !in MIN_PORT..MAX_PORT) return null
            val raw = authority.host
            if (raw != raw.trim() || raw.isEmpty() || raw.toByteArray(Charsets.UTF_8).size > MAX_HOST_BYTES) return null
            val key = canonicalIp(raw) ?: canonicalDomain(raw) ?: return null
            return TenantCanonicalHost(key, authority.port)
        }

        private fun canonicalIp(raw: String): String? {
            val bracketed = raw.startsWith('[') || raw.endsWith(']')
            val candidate =
                if (bracketed) {
                    if (!raw.startsWith('[') || !raw.endsWith(']')) return null
                    raw.substring(1, raw.length - 1)
                } else {
                    raw
                }
            if (candidate.contains('%')) return null
            if (candidate.count { it == ':' } >= 2) {
                return try {
                    (InetAddress.getByName(candidate) as? Inet6Address)?.hostAddress?.lowercase(Locale.ROOT)
                } catch (_: java.net.UnknownHostException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            if (!IPV4.matches(candidate)) return null
            val parts = candidate.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 }) return null
            return octets.joinToString(".")
        }

        private fun canonicalDomain(raw: String): String? {
            if (raw.startsWith('[') || raw.endsWith(']') || ':' in raw) return null
            val withoutRootDot = raw.removeSuffix(".")
            if (withoutRootDot.isEmpty() || withoutRootDot.endsWith('.')) return null
            val ascii =
                try {
                    IDN.toASCII(withoutRootDot, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            if (ascii.toByteArray(Charsets.UTF_8).size > MAX_DNS_BYTES) return null
            if (ascii.split('.').any { !DNS_LABEL.matches(it) }) return null
            return ascii
        }

        private const val MIN_PORT: Int = 1
        private const val MAX_PORT: Int = 65_535
        private const val MAX_HOST_BYTES: Int = 512
        private const val MAX_DNS_BYTES: Int = 253
        private val IPV4: Regex = Regex("^[0-9.]+$")
        private val DNS_LABEL: Regex = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    }
}

/** Resolves a canonical host through application-owned domain ownership data. */
public fun interface TenantHostResolver {
    /** Null means this valid authority is central or not a tenant domain. */
    public fun resolve(host: TenantCanonicalHost): TenantResolution?
}

/**
 * Canonical host adapter. It consumes a transport-parsed authority, never `Host`, `Forwarded`, or
 * `X-Forwarded-Host` directly; rain-web's own forwarded-header policy remains the sole transport
 * trust boundary.
 */
public class CanonicalHostTenantResolutionSource(
    override val id: String,
    private val domains: TenantHostResolver,
) : TenantResolutionSource {
    init {
        require(TENANT_RESOLUTION_SOURCE_ID.matches(id)) { "tenant source id is not stable" }
    }

    override fun resolve(context: TenantRequestContext): TenantCandidate {
        val authority = (context as? TenantHostRequestContext)?.authority() ?: return TenantCandidate.Absent
        val host = TenantCanonicalHost.parse(authority) ?: return TenantCandidate.Malformed(id)
        return domains.resolve(host)?.let { TenantCandidate.Present(it, id) } ?: TenantCandidate.Absent
    }
}

/** A request context whose header access is available only to an explicitly configured verifier. */
public interface TenantHeaderRequestContext : TenantRequestContext {
    public fun headers(name: String): List<String>
}

/**
 * Application security boundary for a tenant header. It receives the request only after the host
 * transport adapter made header access available, and returns a signal rather than a scope.
 */
public fun interface TrustedTenantHeaderVerifier {
    public fun verify(context: TenantHeaderRequestContext): TenantResolutionSignal
}

/**
 * Header resolution has no default instance and cannot be enabled by a boolean. An application
 * must install a verifier that proves its own proxy/signature/security conditions.
 */
public class TrustedHeaderTenantResolutionSource(
    override val id: String,
    private val verifier: TrustedTenantHeaderVerifier,
) : TenantResolutionSource {
    init {
        require(TENANT_RESOLUTION_SOURCE_ID.matches(id)) { "tenant source id is not stable" }
    }

    override fun resolve(context: TenantRequestContext): TenantCandidate {
        val headers = context as? TenantHeaderRequestContext ?: return TenantCandidate.Absent
        return verifier.verify(headers).candidate(id)
    }
}

private fun TenantResolutionSignal.candidate(provenance: String): TenantCandidate =
    when (this) {
        TenantResolutionSignal.Absent -> TenantCandidate.Absent
        TenantResolutionSignal.Malformed -> TenantCandidate.Malformed(provenance)
        is TenantResolutionSignal.Resolved -> TenantCandidate.Present(resolution, provenance)
    }
