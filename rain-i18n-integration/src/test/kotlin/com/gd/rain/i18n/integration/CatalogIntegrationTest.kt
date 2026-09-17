package com.gd.rain.i18n.integration

import com.gd.rain.i18n.ArtifactEnvelope
import com.gd.rain.i18n.ArtifactSignatureAlgorithm
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.I18nLimits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class CatalogIntegrationTest {
    @Test
    fun `release package defensively copies remote artifact bytes on ingress and egress`() {
        val supplied = byteArrayOf(1, 2, 3)
        val release = CatalogReleasePackage(referenceFor(supplied), supplied, envelopeFor(supplied))
        supplied[0] = 9
        val received = release.artifactBytes()
        received[1] = 8

        assertThat(release.artifactBytes()).containsExactly(1, 2, 3)
    }

    @Test
    fun `release package applies the local artifact size ceiling before retention`() {
        val supplied = byteArrayOf(1, 2)

        assertThatIllegalArgumentException()
            .isThrownBy { CatalogReleasePackage(referenceFor(supplied), supplied, envelopeFor(supplied), I18nLimits(maxArtifactBytes = 1)) }
            .withMessage("a catalog release package artifact is 1..1 bytes")
    }

    @Test
    fun `release package requires both reference and envelope to identify exact bytes`() {
        val supplied = byteArrayOf(1, 2)

        assertThatIllegalArgumentException()
            .isThrownBy {
                CatalogReleasePackage(
                    CatalogRef("release", Digest.sha256(byteArrayOf(9))),
                    supplied,
                    envelopeFor(supplied),
                )
            }.withMessage("a catalog release package reference does not identify its artifact")
    }

    private fun referenceFor(artifact: ByteArray): CatalogRef = CatalogRef("release", Digest.sha256(artifact))

    private fun envelopeFor(artifact: ByteArray): ArtifactEnvelope =
        ArtifactEnvelope(
            origin = "fixture_registry",
            artifactDigest = Digest.sha256(artifact),
            keyId = "fixture_key",
            algorithm = ArtifactSignatureAlgorithm.ED25519,
            signature = byteArrayOf(1),
        )
}
