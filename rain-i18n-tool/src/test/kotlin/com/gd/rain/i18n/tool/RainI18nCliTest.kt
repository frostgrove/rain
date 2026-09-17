package com.gd.rain.i18n.tool

import com.gd.rain.i18n.CatalogIdentity
import com.gd.rain.i18n.CatalogSourceCodec
import com.gd.rain.i18n.CatalogSpec
import com.gd.rain.i18n.LocalePolicy
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.MessageSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

class RainI18nCliTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `cli validates without writing and routes compile generation and export through verified writers`() {
        val source = directory.resolve("catalog.json")
        Files.write(source, source())
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        assertThat(RainI18nCli.execute(listOf("check", source.toString()), stdout, stderr)).isZero()
        assertThat(stdout.toString()).isEqualTo("valid\n")
        assertThat(stderr).isEmpty()

        val artifact = directory.resolve("output/catalog.rain-i18n")
        assertThat(RainI18nCli.execute(listOf("compile", source.toString(), artifact.toString()), stdout, stderr)).isZero()
        assertThat(artifact).exists()
        assertThat(artifact.resolveSibling(".${artifact.fileName}.rain-i18n.lock")).exists()

        val generated = directory.resolve("output/RainI18nContracts.kt")
        assertThat(
            RainI18nCli.execute(
                listOf("generate-kotlin", source.toString(), "example.i18n", generated.toString()),
                stdout,
                stderr,
            ),
        ).isZero()
        assertThat(generated).exists()

        val publication = directory.resolve("output/typescript")
        assertThat(RainI18nCli.execute(listOf("export-typescript", source.toString(), publication.toString()), stdout, stderr)).isZero()
        assertThat(publication.resolve("current.json")).exists()
    }

    @Test
    fun `cli refuses an unknown command without creating output`() {
        val errors = StringBuilder()

        assertThat(RainI18nCli.execute(listOf("remove-everything"), StringBuilder(), errors)).isEqualTo(2)

        assertThat(errors).contains("usage:")
        assertThat(directory.toFile().list()).isEmpty()
    }

    private fun source(): ByteArray {
        val en = LocaleTag.parse("en")
        return CatalogSourceCodec().encode(
            CatalogSpec(
                CatalogIdentity("cli", icuClDrTzdbIdentity = "icu4j-78.3"),
                en,
                LocalePolicy(setOf(en), en),
                defaultZone = ZoneId.of("UTC"),
                messages = listOf(MessageSpec(MessageKey("cli", "title"), 1, "Title", "A title", public = true)),
            ),
        )
    }
}
