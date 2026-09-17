package com.gd.rain.tenancy

import java.text.Normalizer
import java.util.Arrays

/** An application tenant reference, intentionally not an authority and never printed verbatim. */
public class TenantRef private constructor(
    private val canonical: String,
) {
    internal fun bytes(): ByteArray = canonical.toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is TenantRef && canonical == other.canonical

    override fun hashCode(): Int = canonical.hashCode()

    override fun toString(): String = "tenant-ref[redacted]"

    public companion object {
        public const val MAX_BYTES: Int = 128

        /** Normalizes to NFC and refuses control characters or an out-of-bound UTF-8 identifier. */
        public fun of(value: String): TenantRef {
            val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            require(normalized.isNotBlank() && normalized.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "tenant reference is blank or exceeds $MAX_BYTES UTF-8 bytes"
            }
            require(normalized.none { it.isISOControl() }) { "tenant reference contains a control character" }
            return TenantRef(normalized)
        }
    }
}

/** Epoch fencing protects restore/reprovision from stale pools, jobs, scopes, and event namespaces. */
@JvmInline
public value class TenantEpoch(
    public val value: Long,
) {
    init {
        require(value > 0) { "tenant epoch is positive" }
    }
}

/** Closed lifecycle graph vocabulary. An application cannot introduce an unreviewed routable state. */
public enum class TenantLifecycle {
    PROVISIONING,
    ACTIVE,
    READ_ONLY,
    SUSPENDED,
    MIGRATING,
    DELETING,
    DELETED,
}

/** Admission is purpose-specific; write never silently degrades to read. */
public enum class TenantOperation {
    READ,
    WRITE,
    DURABLE,
    ADMIN,
}

/** A plain resolver result. It cannot be bound or used as tenant authority. */
public data class TenantResolution(
    public val ref: TenantRef,
    public val lifecycle: TenantLifecycle,
    public val epoch: TenantEpoch,
    public val placementVersion: Long,
) {
    init {
        require(placementVersion > 0) { "tenant placement version is positive" }
    }
}

/** A bounded non-secret digest for diagnostics; it is unsuitable as a key or a metric label. */
public class TenantDigest internal constructor(
    private val value: ByteArray,
) {
    init {
        require(value.size == SIZE_BYTES) { "tenant digest has ${value.size} bytes, not $SIZE_BYTES" }
    }

    public fun copy(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is TenantDigest && Arrays.equals(value, other.value)

    override fun hashCode(): Int = Arrays.hashCode(value)

    override fun toString(): String = "tenant-digest:${value.take(8).joinToString("") { "%02x".format(it) }}"

    internal companion object {
        const val SIZE_BYTES: Int = 32
    }
}

/** Closed outcomes suitable for metrics and public fault translation; none contains tenant material. */
public enum class TenantOutcome {
    ADMITTED,
    REQUIRED,
    FORBIDDEN,
    NOT_FOUND,
    STALE,
    PINNED,
    CAPACITY,
    UNAVAILABLE,
    SOURCE_MISMATCH,
    OPERATION_COLLISION,
    GRANT_REQUIRED,
    GRANT_EXPIRED,
}

/** A source may contribute one candidate, no candidate, or a malformed request — never a scope. */
public sealed interface TenantCandidate {
    public data object Absent : TenantCandidate

    public data class Present(
        public val resolution: TenantResolution,
        public val provenance: String,
    ) : TenantCandidate {
        init {
            require(PROVENANCE.matches(provenance)) { "tenant candidate provenance is not stable" }
        }
    }

    public data class Malformed(
        public val provenance: String,
    ) : TenantCandidate {
        init {
            require(PROVENANCE.matches(provenance)) { "tenant candidate provenance is not stable" }
        }
    }

    private companion object {
        val PROVENANCE: Regex = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    }
}

/** Minimal transport-neutral input passed to tenant resolution sources. */
public interface TenantRequestContext {
    public fun attribute(name: String): String?
}

/** One ordered resolver source. Its id is part of observable route semantics. */
public interface TenantResolutionSource {
    public val id: String

    public fun resolve(context: TenantRequestContext): TenantCandidate
}

/** Resolver port used by HTTP binding, durable restore, and explicit administration. */
public interface TenantResolver : TenantResolutionDirectory {
    public fun resolveCurrent(context: TenantRequestContext): TenantCandidate

    override fun lookup(ref: TenantRef): TenantResolution?
}

/** Read-only control-plane lookup used to assemble a resolver without coupling it to HTTP sources. */
public fun interface TenantResolutionDirectory {
    public fun lookup(ref: TenantRef): TenantResolution?
}

/**
 * The authority's control-plane lookup could not be completed. Durable adapters translate this
 * distinctly from a refused, stale, or malformed tenant so normal job retry policy can apply.
 */
public open class TenantAuthorityUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Resolves agreeing candidates and refuses conflicts rather than arbitrarily picking precedence. */
public class CompositeTenantResolver(
    sources: List<TenantResolutionSource>,
    private val lookupSource: (TenantRef) -> TenantResolution?,
) : TenantResolver {
    private val sources: List<TenantResolutionSource> = sources.toList()

    init {
        require(this.sources.map(TenantResolutionSource::id).all(::isTenantResolutionSourceId)) { "tenant source id is not stable" }
        require(
            this.sources
                .map(TenantResolutionSource::id)
                .distinct()
                .size == this.sources.size,
        ) {
            "tenant source id is declared more than once"
        }
    }

    override fun resolveCurrent(context: TenantRequestContext): TenantCandidate {
        val candidates = sources.map { it.resolve(context) }
        val malformed = candidates.filterIsInstance<TenantCandidate.Malformed>().firstOrNull()
        if (malformed != null) return malformed
        val present = candidates.filterIsInstance<TenantCandidate.Present>()
        if (present.isEmpty()) return TenantCandidate.Absent
        val first = present.first()
        if (present.any { it.resolution.ref != first.resolution.ref }) return TenantCandidate.Malformed("conflict")
        return first
    }

    override fun lookup(ref: TenantRef): TenantResolution? = lookupSource(ref)
}

internal fun isTenantResolutionSourceId(value: String): Boolean = TENANT_RESOLUTION_SOURCE_ID.matches(value)

internal val TENANT_RESOLUTION_SOURCE_ID: Regex = Regex("^[a-z][a-z0-9_.-]{0,63}$")
