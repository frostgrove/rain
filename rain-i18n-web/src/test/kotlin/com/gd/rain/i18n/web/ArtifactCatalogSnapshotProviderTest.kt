package com.gd.rain.i18n.web

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.i18n.CatalogArtifactCodec
import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.DefaultResourceLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

class ArtifactCatalogSnapshotProviderTest {
    @Test
    fun `provider loads exactly one local canonical artifact through configured runtime identity`(
        @TempDir directory: Path,
    ) {
        val artifact = CatalogArtifactCodec().encode(source())
        val path = Files.write(directory.resolve("catalog.json"), artifact)
        val settings =
            I18nArtifactProperties(
                enabled = true,
                artifactLocation = path.toUri().toString(),
                runtime = I18nArtifactProperties.Runtime("rain-mf2/v1", "rain-i18n/1", "icu4j-78.3"),
            ).settings()

        val provider = ArtifactCatalogSnapshotProvider(settings, DefaultResourceLoader())

        assertThat(provider.current().reference.revision).isEqualTo("fixture")
    }

    @Test
    fun `bootstrap refuses remote artifact URLs rather than silently creating a network watcher`() {
        assertThatThrownBy {
            I18nArtifactProperties(
                enabled = true,
                artifactLocation = "https://catalog.example/rain.json",
                runtime = I18nArtifactProperties.Runtime("rain-mf2/v1", "rain-i18n/1", "icu4j-78.3"),
            ).settings()
        }.isInstanceOf(ConfigurationProblemsException::class.java)
            .hasMessageContaining("rain.i18n.artifact-location")
    }

    private fun source(): CatalogSpec =
        CatalogSpec(
            CatalogIdentity("fixture", icuClDrTzdbIdentity = "icu4j-78.3"),
            LocaleTag.parse("en"),
            LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
            defaultZone = ZoneId.of("UTC"),
            messages = listOf(MessageSpec(MessageKey("app", "ready"), 1, "Ready", "fixture")),
        )
}
