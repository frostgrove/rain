package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.ZoneId

class ArtifactTrustTest {
    @Test
    fun `signed remote loading verifies raw artifact identity origin and trusted key`() {
        val source =
            CatalogSpec(
                CatalogIdentity("trusted", icuClDrTzdbIdentity = "icu4j-78.3"),
                LocaleTag.parse("en"),
                LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, "Ready", "ready state")),
            )
        val codec = CatalogArtifactCodec()
        val artifact = codec.encode(source)
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val origin = "release_registry"
        val signature =
            Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(artifactSignaturePayload(origin, artifact, source.identity))
                sign()
            }
        val envelope = ArtifactEnvelope(origin, Digest.sha256(artifact), "release_key", ArtifactSignatureAlgorithm.ED25519, signature)
        val loader = TrustedCatalogLoader(codec, listOf(CatalogTrustKey("release_key", ArtifactSignatureAlgorithm.ED25519, pair.public)))

        val loaded = loader.load(artifact, CatalogTrustPolicy.SIGNED_REMOTE, envelope)
        val wrongOrigin =
            ArtifactEnvelope("other_registry", Digest.sha256(artifact), "release_key", ArtifactSignatureAlgorithm.ED25519, signature)

        assertThat(loaded).isInstanceOf(TrustedCatalogLoad.Loaded::class.java)
        assertThat(
            loader.load(artifact, CatalogTrustPolicy.SIGNED_REMOTE, wrongOrigin),
        ).isEqualTo(TrustedCatalogLoad.Refused(CatalogTrustRefusal.SIGNATURE_INVALID))
        assertThat(
            loader.load(artifact, CatalogTrustPolicy.LOCAL_BUILD, envelope),
        ).isEqualTo(TrustedCatalogLoad.Refused(CatalogTrustRefusal.POLICY_FORBIDS_ORIGIN))
    }
}
