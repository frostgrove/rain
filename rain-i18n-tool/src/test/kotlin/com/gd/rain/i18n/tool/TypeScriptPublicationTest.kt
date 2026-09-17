package com.gd.rain.i18n.tool

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TypeScriptPublicationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `publication pins a complete content-addressed generation and reader rejects unexpected generation files`() {
        val export =
            TypeScriptExport(
                "export type Public = 'shop.title';\n".toByteArray(),
                "{\"schema\":\"rain.i18n.public/v1\"}\n".toByteArray(),
            )
        val publication = TypeScriptPublication()

        val result = publication.publish(directory, export)
        val loaded = publication.read(directory)

        assertThat(result).isEqualTo(TypeScriptPublicationWrite.Published(TypeScriptPublicationResult(export.digest)))
        assertThat(loaded).isInstanceOf(TypeScriptPublicationRead.Loaded::class.java)
        assertThat((loaded as TypeScriptPublicationRead.Loaded).export.declarationsBytes()).isEqualTo(export.declarationsBytes())
        Files.writeString(directory.resolve("generations").resolve("sha256-${export.digest.hex}").resolve("unexpected"), "no")

        assertThat(publication.read(directory))
            .isEqualTo(TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID))
    }
}
