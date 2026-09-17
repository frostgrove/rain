package com.gd.rain.i18n.tool.k2

import com.gd.rain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class RainI18nK2UsagePluginTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `K2 plugin records only resolved core keys and marks dynamic or escaped generated binders incomplete`() {
        val source = directory.resolve("Usage.kt")
        val imposter = directory.resolve("Imposter.kt")
        val manifest = directory.resolve("reports/usage.json")
        Files.createDirectories(manifest.parent)
        Files.writeString(
            imposter,
            """
            package impostor

            @Target(AnnotationTarget.FUNCTION)
            annotation class RainI18nGeneratedBinding(
                val key: String,
                val revision: Int,
                val contractDigest: String,
            )
            """.trimIndent(),
        )
        Files.writeString(
            source,
            """
            package fixture

            import com.gd.rain.i18n.MessageKey as RainMessageKey
            import com.gd.rain.i18n.RainI18nGeneratedBinding

            fun MessageKey(module: String, name: String): String = "${'$'}module.${'$'}name"

            @RainI18nGeneratedBinding("billing.receipt", 1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            fun generatedBinder(): String = "bound"

            @impostor.RainI18nGeneratedBinding("impostor.not_recorded", 1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            fun impostorBinder(): String = "not bound"

            fun usage(dynamicModule: String): Any {
                val exact = RainMessageKey("orders", "accepted")
                val lookAlike = MessageKey("not", "a_core_key")
                val dynamic = RainMessageKey(dynamicModule, "rejected")
                val generated = generatedBinder()
                val impostor = impostorBinder()
                val escaped = ::generatedBinder
                return listOf(exact, lookAlike, dynamic, generated, impostor, escaped)
            }
            """.trimIndent(),
        )

        val diagnostics = RecordingMessages()
        val result = compile(listOf(source, imposter), manifest, failOnDynamicUsage = false, diagnostics)

        assertThat(result).describedAs(diagnostics.messages.toString()).isEqualTo(ExitCode.OK)
        assertThat(Files.readString(manifest))
            .contains("\"complete\":false")
            .contains("\"keys\":[\"billing.receipt\",\"orders.accepted\"]")
            .contains("\"key\":\"orders.accepted\",\"complete\":true")
            .contains("\"key\":\"billing.receipt\",\"complete\":true")
            .contains("\"key\":null,\"complete\":false")
            .doesNotContain("not.a_core_key", "impostor.not_recorded")
    }

    @Test
    fun `strict K2 mode publishes incomplete evidence before refusing dynamic usage`() {
        val source = directory.resolve("Dynamic.kt")
        val manifest = directory.resolve("reports/strict.json")
        Files.createDirectories(manifest.parent)
        Files.writeString(
            source,
            """
            package fixture

            import com.gd.rain.i18n.MessageKey

            fun usage(module: String): MessageKey = MessageKey(module, "accepted")
            """.trimIndent(),
        )

        val diagnostics = RecordingMessages()
        val result = compile(listOf(source), manifest, failOnDynamicUsage = true, diagnostics)

        assertThat(result).describedAs(diagnostics.messages.toString()).isNotEqualTo(ExitCode.OK)
        assertThat(Files.readString(manifest)).contains("\"complete\":false", "\"key\":null")
    }

    private fun compile(
        sources: List<Path>,
        manifest: Path,
        failOnDynamicUsage: Boolean,
        diagnostics: RecordingMessages,
    ): ExitCode =
        K2JVMCompiler().exec(
            diagnostics,
            Services.EMPTY,
            K2JVMCompilerArguments().apply {
                freeArgs = sources.map(Path::toString)
                destination = directory.resolve("classes-${manifest.fileName}").toString()
                classpath = listOf(codeSource(MessageKey::class.java), codeSource(kotlin.Unit::class.java)).joinToString(File.pathSeparator)
                pluginClasspaths = arrayOf(codeSource(RainI18nK2UsagePluginRegistrar::class.java), serviceRoot().toString())
                pluginOptions =
                    arrayOf(
                        "plugin:${RainI18nK2UsageCommandLineProcessor.PLUGIN_ID}:manifest=$manifest",
                        "plugin:${RainI18nK2UsageCommandLineProcessor.PLUGIN_ID}:fail-on-dynamic=$failOnDynamicUsage",
                    )
            },
        )

    private fun codeSource(type: Class<*>): String =
        Path
            .of(
                type.protectionDomain.codeSource.location
                    .toURI(),
            ).toString()

    private fun serviceRoot(): Path {
        val resource =
            checkNotNull(
                RainI18nK2UsagePluginRegistrar::class.java.classLoader.getResource(
                    "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
                ),
            ) { "the K2 compiler-plugin registrar service is packaged" }
        var root = Path.of(URI(resource.toString()))
        repeat(3) { root = checkNotNull(root.parent) { "the registrar service has a resource root" } }
        return root
    }

    private class RecordingMessages : MessageCollector {
        val messages: StringBuilder = StringBuilder()
        private var errors: Boolean = false

        override fun clear() {
            messages.clear()
            errors = false
        }

        override fun hasErrors(): Boolean = errors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) errors = true
            messages
                .append(severity)
                .append(": ")
                .append(message)
                .append('\n')
        }
    }
}
