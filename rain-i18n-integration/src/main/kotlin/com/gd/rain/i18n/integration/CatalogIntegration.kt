package com.gd.rain.i18n.integration

import com.gd.rain.i18n.ArtifactEnvelope
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.I18nLimits

/**
 * A bounded immutable remote release package.
 *
 * Remote packages always carry provenance. The caller must still pass both fields to
 * [com.gd.rain.i18n.TrustedCatalogLoader] before storage or activation; this transport contract
 * deliberately cannot make an artifact trusted on its own.
 */
public class CatalogReleasePackage(
    /** Immutable release identity; it is also the only legal paging cursor. */
    public val reference: CatalogRef,
    artifact: ByteArray,
    public val envelope: ArtifactEnvelope,
    limits: I18nLimits = I18nLimits(),
) {
    private val artifact: ByteArray = artifact.copyOf()

    init {
        require(this.artifact.isNotEmpty() && this.artifact.size <= limits.maxArtifactBytes) {
            "a catalog release package artifact is 1..${limits.maxArtifactBytes} bytes"
        }
        require(reference.digest == Digest.sha256(this.artifact)) {
            "a catalog release package reference does not identify its artifact"
        }
        require(envelope.artifactDigest == reference.digest) {
            "a catalog release package envelope does not identify its artifact"
        }
    }

    public fun artifactBytes(): ByteArray = artifact.copyOf()
}

/** A bounded pull request; [after] is exclusive and must name a release the source has exposed. */
public data class CatalogReleaseFetch(
    public val after: CatalogRef?,
    public val limit: Int,
) {
    init {
        require(limit in 1..MAX_PAGE_SIZE) { "a catalog release fetch limit is 1..$MAX_PAGE_SIZE" }
    }

    public companion object {
        public const val MAX_PAGE_SIZE: Int = 1_000
    }
}

/** A page is ordered by one source's stable release order; [next] is the exclusive cursor for its successor page. */
public data class CatalogReleasePage(
    public val releases: List<CatalogReleasePackage>,
    public val next: CatalogRef?,
) {
    init {
        require(releases.size <= CatalogReleaseFetch.MAX_PAGE_SIZE) {
            "a catalog release page has at most ${CatalogReleaseFetch.MAX_PAGE_SIZE} packages"
        }
        require(releases.map(CatalogReleasePackage::reference).distinct().size == releases.size) {
            "a catalog release page repeats a release"
        }
        require(next == null || releases.isNotEmpty()) { "an empty catalog release page has no continuation" }
    }
}

/** A source distinguishes a bad/missing cursor from a normal empty page. */
public sealed interface CatalogReleaseFetchOutcome {
    public data class Fetched(
        public val page: CatalogReleasePage,
    ) : CatalogReleaseFetchOutcome

    public data class Refused(
        public val reason: CatalogReleaseExchangeRefusal,
    ) : CatalogReleaseFetchOutcome
}

/** Publication is idempotent only for the exact immutable release identity. */
public sealed interface CatalogReleasePublicationOutcome {
    public data class Published(
        public val receipt: CatalogReleaseReceipt,
        public val alreadyPresent: Boolean,
    ) : CatalogReleasePublicationOutcome

    public data class Refused(
        public val reason: CatalogReleaseExchangeRefusal,
    ) : CatalogReleasePublicationOutcome
}

/** A bounded remote receipt is audit correlation only, never catalog trust. */
@JvmInline
public value class CatalogReleaseReceipt(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "a catalog release receipt is 1..$MAX_BYTES UTF-8 bytes"
        }
    }

    public companion object {
        public const val MAX_BYTES: Int = 512
    }
}

/** Transport-safe refusal reasons; cryptographic trust is still decided locally by the kernel. */
public enum class CatalogReleaseExchangeRefusal {
    CURSOR_INVALID,
    RELEASE_CONFLICT,
    CAPACITY_REACHED,
    UNAVAILABLE,
    UNAUTHORIZED,
}

/**
 * Remote polling/storage input; scheduling and retry policy are application-owned.
 *
 * Implementations return at most [limit] packages in a stable source order. A cursor identifies
 * an already observed immutable release; it is not an authority to activate that release.
 */
public interface CatalogReleaseSource {
    public fun fetch(request: CatalogReleaseFetch): CatalogReleaseFetchOutcome
}

/** Remote publication output; a sink never activates the release by itself. */
public interface CatalogReleaseSink {
    public fun publish(release: CatalogReleasePackage): CatalogReleasePublicationOutcome
}
