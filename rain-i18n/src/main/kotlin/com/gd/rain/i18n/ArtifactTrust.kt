package com.gd.rain.i18n

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature

/** Whether a release is locally built or must carry independently verified remote provenance. */
public enum class CatalogTrustPolicy {
    LOCAL_BUILD,
    SIGNED_REMOTE,
}

/** The fixed signature algorithms that may authenticate a remote catalog artifact. */
public enum class ArtifactSignatureAlgorithm(
    internal val jcaName: String,
) {
    ED25519("Ed25519"),
    RSA_SHA256("SHA256withRSA"),
}

/** A bounded active or retiring verification key; a key is never selected by artifact input alone. */
public data class CatalogTrustKey(
    public val id: String,
    public val algorithm: ArtifactSignatureAlgorithm,
    public val publicKey: PublicKey,
    public val state: CatalogTrustKeyState = CatalogTrustKeyState.ACTIVE,
) {
    init {
        require(MessageKey.IDENTIFIER.matches(id)) { "a trust key id matches ${MessageKey.IDENTIFIER.pattern}" }
    }
}

/** Retiring keys remain explicit and bounded rather than silently accepting all historical keys. */
public enum class CatalogTrustKeyState {
    ACTIVE,
    RETIRING,
}

/**
 * A remote envelope separate from the artifact's integrity digest. Signature bytes are copied on
 * ingress and egress so a caller cannot alter what was verified after construction.
 */
public class ArtifactEnvelope(
    public val origin: String,
    public val artifactDigest: Digest,
    public val keyId: String,
    public val algorithm: ArtifactSignatureAlgorithm,
    signature: ByteArray,
) {
    private val signature: ByteArray = signature.copyOf()

    init {
        require(origin.isNotBlank() && origin.utf8Size() <= 512) { "an artifact origin is 1..512 UTF-8 bytes" }
        require(MessageKey.IDENTIFIER.matches(keyId)) { "an envelope key id matches ${MessageKey.IDENTIFIER.pattern}" }
        require(this.signature.isNotEmpty() && this.signature.size <= 16 * 1024) { "a signature is 1..16384 bytes" }
    }

    public fun signatureBytes(): ByteArray = signature.copyOf()
}

/** Result of a load whose artifact syntax/integrity/provenance all passed before snapshot install. */
public sealed interface TrustedCatalogLoad {
    public data class Loaded(
        public val snapshot: CatalogSnapshot,
        public val artifactDigest: Digest,
        public val verifiedKeyId: String?,
    ) : TrustedCatalogLoad

    public data class Refused(
        public val reason: CatalogTrustRefusal,
    ) : TrustedCatalogLoad
}

/** Closed refusal reasons are safe for operations and never expose signature internals. */
public enum class CatalogTrustRefusal {
    ARTIFACT_INVALID,
    POLICY_FORBIDS_ORIGIN,
    ENVELOPE_REQUIRED,
    DIGEST_MISMATCH,
    KEY_UNKNOWN,
    KEY_ALGORITHM_MISMATCH,
    SIGNATURE_INVALID,
}

/**
 * The final gate before a snapshot becomes eligible for controller activation. It has no network
 * side effects: polling, storage and scheduling stay in adapters above this kernel contract.
 */
public class TrustedCatalogLoader(
    private val codec: CatalogArtifactCodec = CatalogArtifactCodec(),
    keys: Collection<CatalogTrustKey> = emptyList(),
) {
    private val keys: Map<String, CatalogTrustKey> = keys.associateBy(CatalogTrustKey::id)

    init {
        require(this.keys.size == keys.size) { "a trust key id is unique" }
    }

    public fun load(
        bytes: ByteArray,
        policy: CatalogTrustPolicy,
        envelope: ArtifactEnvelope? = null,
    ): TrustedCatalogLoad {
        val decoded = codec.decode(bytes)
        if (decoded !is CatalogArtifactDecoding.Decoded) return TrustedCatalogLoad.Refused(CatalogTrustRefusal.ARTIFACT_INVALID)
        if (policy == CatalogTrustPolicy.LOCAL_BUILD) {
            if (envelope != null) return TrustedCatalogLoad.Refused(CatalogTrustRefusal.POLICY_FORBIDS_ORIGIN)
            return TrustedCatalogLoad.Loaded(decoded.snapshot, decoded.artifactDigest, null)
        }
        val signed = envelope ?: return TrustedCatalogLoad.Refused(CatalogTrustRefusal.ENVELOPE_REQUIRED)
        if (signed.artifactDigest != decoded.artifactDigest) return TrustedCatalogLoad.Refused(CatalogTrustRefusal.DIGEST_MISMATCH)
        val key = keys[signed.keyId] ?: return TrustedCatalogLoad.Refused(CatalogTrustRefusal.KEY_UNKNOWN)
        if (key.algorithm != signed.algorithm) return TrustedCatalogLoad.Refused(CatalogTrustRefusal.KEY_ALGORITHM_MISMATCH)
        if (!verify(key, signed, bytes, decoded.snapshot.identity)) return TrustedCatalogLoad.Refused(CatalogTrustRefusal.SIGNATURE_INVALID)
        return TrustedCatalogLoad.Loaded(decoded.snapshot, decoded.artifactDigest, key.id)
    }

    private fun verify(
        key: CatalogTrustKey,
        envelope: ArtifactEnvelope,
        artifact: ByteArray,
        identity: CatalogIdentity,
    ): Boolean =
        try {
            Signature.getInstance(key.algorithm.jcaName).run {
                initVerify(key.publicKey)
                update(artifactSignaturePayload(envelope.origin, artifact, identity))
                verify(envelope.signatureBytes())
            }
        } catch (_: GeneralSecurityException) {
            false
        }
}

/** Exact signature payload: origin, raw bytes and identity cannot be swapped independently. */
public fun artifactSignaturePayload(
    origin: String,
    artifact: ByteArray,
    identity: CatalogIdentity,
): ByteArray {
    val output = ByteArrayOutputStream()
    listOf(
        "rain.i18n.envelope/v1".toByteArray(Charsets.UTF_8),
        origin.toByteArray(Charsets.UTF_8),
        artifact.copyOf(),
        identity.revision.toByteArray(Charsets.UTF_8),
        identity.profile.toByteArray(Charsets.UTF_8),
        identity.engine.toByteArray(Charsets.UTF_8),
        identity.icuClDrTzdbIdentity.toByteArray(Charsets.UTF_8),
    ).forEach { field ->
        output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(field.size).array())
        output.write(field)
    }
    return output.toByteArray()
}
