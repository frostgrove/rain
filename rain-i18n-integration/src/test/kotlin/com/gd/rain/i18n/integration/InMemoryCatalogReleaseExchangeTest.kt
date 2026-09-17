package com.gd.rain.i18n.integration

import com.gd.rain.i18n.ArtifactEnvelope
import com.gd.rain.i18n.ArtifactSignatureAlgorithm
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.Digest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryCatalogReleaseExchangeTest {
    @Test
    fun `exchange paginates stable exact release cursors and rejects an unknown cursor`() {
        val exchange = InMemoryCatalogReleaseExchange()
        val first = release("first", byteArrayOf(1))
        val second = release("second", byteArrayOf(2))
        val third = release("third", byteArrayOf(3))

        assertThat(exchange.publish(first)).isEqualTo(CatalogReleasePublicationOutcome.Published(receipt(first), false))
        assertThat(exchange.publish(second)).isEqualTo(CatalogReleasePublicationOutcome.Published(receipt(second), false))
        assertThat(exchange.publish(third)).isEqualTo(CatalogReleasePublicationOutcome.Published(receipt(third), false))

        val firstPage = exchange.fetch(CatalogReleaseFetch(null, 2))
        assertThat(firstPage)
            .isEqualTo(CatalogReleaseFetchOutcome.Fetched(CatalogReleasePage(listOf(first, second), second.reference)))
        assertThat(exchange.fetch(CatalogReleaseFetch(second.reference, 2)))
            .isEqualTo(CatalogReleaseFetchOutcome.Fetched(CatalogReleasePage(listOf(third), null)))
        assertThat(exchange.fetch(CatalogReleaseFetch(CatalogRef("missing", Digest.sha256(byteArrayOf(9))), 1)))
            .isEqualTo(CatalogReleaseFetchOutcome.Refused(CatalogReleaseExchangeRefusal.CURSOR_INVALID))
    }

    @Test
    fun `exchange makes exact republish idempotent and enforces finite retention`() {
        val one = release("one", byteArrayOf(1))
        val exchange = InMemoryCatalogReleaseExchange(maxReleases = 1)

        assertThat(exchange.publish(one)).isEqualTo(CatalogReleasePublicationOutcome.Published(receipt(one), false))
        assertThat(exchange.publish(release("one", byteArrayOf(1))))
            .isEqualTo(CatalogReleasePublicationOutcome.Published(receipt(one), true))
        assertThat(exchange.publish(release("two", byteArrayOf(2))))
            .isEqualTo(CatalogReleasePublicationOutcome.Refused(CatalogReleaseExchangeRefusal.CAPACITY_REACHED))
    }

    private fun release(
        revision: String,
        artifact: ByteArray,
    ): CatalogReleasePackage {
        val digest = Digest.sha256(artifact)
        return CatalogReleasePackage(
            CatalogRef(revision, digest),
            artifact,
            ArtifactEnvelope("fixture", digest, "key", ArtifactSignatureAlgorithm.ED25519, byteArrayOf(1)),
        )
    }

    private fun receipt(release: CatalogReleasePackage): CatalogReleaseReceipt =
        CatalogReleaseReceipt("in_memory:${release.reference.digest.hex}")
}
