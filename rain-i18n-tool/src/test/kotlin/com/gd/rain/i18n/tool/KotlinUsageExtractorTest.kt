package com.gd.rain.i18n.tool

import com.gd.rain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KotlinUsageExtractorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `extractor records literal keys while refusing expressions comments and interpolation as proof`() {
        val source = directory.resolve("Example.kt")
        Files.writeString(
            source,
            """
            package example

            // MessageKey("comment", "ignored")
            val exact = MessageKey("orders", "accepted")
            val dynamic = MessageKey(module, "accepted")
            val interpolated = MessageKey("orders", "${'$'}name")
            /* MessageKey("block", "ignored") */
            """.trimIndent(),
        )

        val extracted = KotlinUsageExtractor().extract(listOf(source))

        assertThat(extracted.keys).containsExactly(MessageKey("orders", "accepted"))
        assertThat(extracted.literalUsages.single().line).isEqualTo(4)
        assertThat(extracted.dynamicUsages).hasSize(2).allSatisfy { usage ->
            assertThat(usage.line).isIn(5, 6)
        }
    }

    @Test
    fun `extractor records direct Java constructor keys and conservatively marks expressions dynamic`() {
        val source = directory.resolve("Example.java")
        Files.writeString(
            source,
            """
            package example;
            // new MessageKey("comment", "ignored");
            var exact = new MessageKey("orders", "accepted");
            var dynamic = new MessageKey(module, "accepted");
            """.trimIndent(),
        )

        val extracted = KotlinUsageExtractor().extract(listOf(source))

        assertThat(extracted.keys).containsExactly(MessageKey("orders", "accepted"))
        assertThat(extracted.literalUsages.single().line).isEqualTo(3)
        assertThat(extracted.dynamicUsages.single().line).isEqualTo(4)
    }
}
