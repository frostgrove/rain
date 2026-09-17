package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

class ArtifactCodecTest {
    @Test
    fun `compiled artifact verifies canonical source header identity and snapshot digest`() {
        val source =
            CatalogSpec(
                CatalogIdentity("artifact", icuClDrTzdbIdentity = "icu4j-78.3"),
                LocaleTag.parse("en"),
                LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, "Ready", "ready state")),
            )
        val codec = CatalogArtifactCodec()

        val encoded = codec.encode(source)
        val decoded = codec.decode(encoded) as CatalogArtifactDecoding.Decoded

        assertThat(decoded.snapshot.digest).isEqualTo((CatalogCompiler.compile(source) as CatalogCompilation.Compiled).snapshot.digest)
        assertThat(decoded.artifactDigest).isEqualTo(Digest.sha256(encoded))
    }

    @Test
    fun `artifact refuses a tampered snapshot digest`() {
        val source =
            CatalogSpec(
                CatalogIdentity("artifact-tamper", icuClDrTzdbIdentity = "icu4j-78.3"),
                LocaleTag.parse("en"),
                LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, "Ready", "ready state")),
            )
        val codec = CatalogArtifactCodec()
        val encoded = codec.encode(source)
        val snapshot = (CatalogCompiler.compile(source) as CatalogCompilation.Compiled).snapshot
        val tampered = encoded.decodeToString().replace(snapshot.digest.hex, "0".repeat(64)).toByteArray()

        assertThat(codec.decode(tampered)).isInstanceOf(CatalogArtifactDecoding.Refused::class.java)
    }

    @Test
    fun `artifact refuses a grammar or ICU backend identity different from the configured runtime`() {
        val source =
            CatalogSpec(
                CatalogIdentity("artifact-runtime", icuClDrTzdbIdentity = "icu4j-78.3"),
                LocaleTag.parse("en"),
                LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, "Ready", "ready state")),
            )
        val artifact = CatalogArtifactCodec().encode(source)
        val runtime = CatalogRuntimeIdentity(icuClDrTzdbIdentity = "icu4j-79.1")

        val decoded = CatalogArtifactCodec(runtimeIdentity = runtime).decode(artifact)

        assertThat(decoded).isEqualTo(
            CatalogArtifactDecoding.Refused(
                listOf(CatalogArtifactProblem("$.identity", "artifact identity differs from configured runtime")),
            ),
        )
    }
}
