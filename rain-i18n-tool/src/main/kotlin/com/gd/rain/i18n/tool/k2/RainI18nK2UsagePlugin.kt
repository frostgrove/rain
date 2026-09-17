@file:OptIn(
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)
@file:Suppress("DEPRECATION_ERROR")

package com.gd.rain.i18n.tool.k2

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.classIdWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * K2 compiler-plugin entry point for semantic i18n usage extraction.
 *
 * The ordinary library deliberately has no compiler dependency. This optional tool-side plugin is
 * loaded only by Kotlin compilation, after K2 has resolved constructor symbols and overloads; it
 * therefore never turns a source token that merely looks like `MessageKey` into usage evidence.
 */
public class RainI18nK2UsagePluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val manifest = configuration.get(RainI18nK2UsageConfiguration.MANIFEST) ?: return
        IrGenerationExtension.registerExtension(
            RainI18nK2UsageIrExtension(
                manifest = Path.of(manifest),
                failOnDynamicUsage = configuration.get(RainI18nK2UsageConfiguration.FAIL_ON_DYNAMIC_USAGE) ?: true,
            ),
        )
    }

    override val pluginId: String = RainI18nK2UsageCommandLineProcessor.PLUGIN_ID
}

/** The one explicit output option; the Gradle adapter owns source-set/classpath selection. */
public class RainI18nK2UsageCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<CliOption> =
        listOf(
            CliOption(
                optionName = "manifest",
                valueDescription = "path",
                description = "write the resolved Rain i18n usage manifest at this exact path",
                required = true,
                allowMultipleOccurrences = false,
            ),
            CliOption(
                optionName = "fail-on-dynamic",
                valueDescription = "true|false",
                description = "fail compilation when semantic i18n usage cannot be proven",
                required = false,
                allowMultipleOccurrences = false,
            ),
        )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            "manifest" -> {
                configuration.put(RainI18nK2UsageConfiguration.MANIFEST, value)
            }

            "fail-on-dynamic" -> {
                require(value == "true" || value == "false") { "Rain i18n K2 fail-on-dynamic is true or false" }
                configuration.put(RainI18nK2UsageConfiguration.FAIL_ON_DYNAMIC_USAGE, value.toBoolean())
            }

            else -> {
                throw IllegalArgumentException("unsupported Rain i18n K2 compiler option ${option.optionName}")
            }
        }
    }

    public companion object {
        public const val PLUGIN_ID: String = "com.gd.rain.i18n.k2-usage"
    }
}

/** K2 compiler configuration remains private to the compiler-plugin boundary. */
internal object RainI18nK2UsageConfiguration {
    val MANIFEST: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rain.i18n.k2.usage.manifest")
    val FAIL_ON_DYNAMIC_USAGE: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("rain.i18n.k2.usage.fail-on-dynamic")
}

/**
 * A post-resolution K2 IR visitor. It records only calls whose resolved constructor owner is the
 * core `MessageKey` class. Dynamic operands remain visible as `complete=false`; they cannot be
 * mistaken for an exact key by the lexical extractor or by this semantic one.
 */
internal class RainI18nK2UsageIrExtension(
    private val manifest: Path,
    private val failOnDynamicUsage: Boolean,
) : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        val generatedBindings = mutableMapOf<IrFunctionSymbol, String>()
        moduleFragment.files.sortedBy { it.fileEntry.name }.forEach { file ->
            file.acceptChildrenVoid(K2GeneratedBindingIndexVisitor(generatedBindings))
        }
        val usages = mutableListOf<K2Usage>()
        moduleFragment.files.sortedBy { it.fileEntry.name }.forEach { file ->
            file.acceptChildrenVoid(K2UsageVisitor(file, usages, generatedBindings))
        }
        K2UsageManifestWriter.write(manifest, usages)
        if (failOnDynamicUsage && usages.any { !it.complete }) {
            error("Rain i18n semantic usage is incomplete: ${usages.count { !it.complete }} dynamic or escaped binding(s)")
        }
    }
}

private class K2UsageVisitor(
    private val file: IrFile,
    private val usages: MutableList<K2Usage>,
    private val generatedBindings: Map<IrFunctionSymbol, String>,
) : IrVisitorVoid() {
    private var generatedBindingDepth: Int = 0

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitFunction(declaration: IrFunction) {
        val generatedBinding = declaration.symbol in generatedBindings
        if (generatedBinding) generatedBindingDepth += 1
        try {
            super.visitFunction(declaration)
        } finally {
            if (generatedBinding) generatedBindingDepth -= 1
        }
    }

    override fun visitConstructorCall(expression: IrConstructorCall) {
        if (generatedBindingDepth == 0 && expression.type.classFqName() == MESSAGE_KEY_FQ_NAME) {
            val module = expression.stringArgument(0)
            val name = expression.stringArgument(1)
            usages +=
                if (module != null && name != null && validKeyPart(module) && validKeyPart(name)) {
                    K2Usage(file.fileEntry.name, line(expression), "$module.$name", complete = true)
                } else {
                    K2Usage(file.fileEntry.name, line(expression), null, complete = false)
                }
        }
        super.visitConstructorCall(expression)
    }

    override fun visitCall(expression: IrCall) {
        (generatedBindings[expression.symbol] ?: expression.symbol.owner.generatedBindingKey())?.let { key ->
            usages += K2Usage(file.fileEntry.name, line(expression), key, complete = true)
        }
        super.visitCall(expression)
    }

    override fun visitFunctionReference(expression: IrFunctionReference) {
        if (expression.symbol in generatedBindings || expression.symbol.owner.generatedBindingKey() != null) {
            usages += K2Usage(file.fileEntry.name, line(expression), null, complete = false)
        }
        super.visitFunctionReference(expression)
    }

    private fun line(expression: IrElement): Int = file.fileEntry.getLineNumber(expression.startOffset).plus(1)
}

private fun IrConstructorCall.stringArgument(index: Int): String? = (arguments.getOrNull(index) as? IrConst)?.value as? String

private fun org.jetbrains.kotlin.ir.types.IrType.classFqName(): String? =
    ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.classIdWhenAvailable?.asSingleFqName()?.asString()

private fun IrFunction.generatedBindingKey(): String? {
    val annotation =
        annotations.firstOrNull { candidate ->
            candidate.classSymbol.classIdWhenAvailable
                ?.asSingleFqName()
                ?.asString() == GENERATED_BINDING_FQ_NAME
        } ?: return null
    val key = (annotation.arguments.getOrNull(0) as? IrConst)?.value as? String ?: return null
    val revision = (annotation.arguments.getOrNull(1) as? IrConst)?.value as? Int ?: return null
    val digest = (annotation.arguments.getOrNull(2) as? IrConst)?.value as? String ?: return null
    return key.takeIf { validKey(key) && revision >= 1 && DIGEST.matches(digest) }
}

private const val MESSAGE_KEY_FQ_NAME: String = "com.gd.rain.i18n.MessageKey"
private const val GENERATED_BINDING_FQ_NAME: String = "com.gd.rain.i18n.RainI18nGeneratedBinding"
private val KEY_PART: Regex = Regex("^[a-z][a-z0-9_]{0,127}$")
private val DIGEST: Regex = Regex("^[0-9a-f]{64}$")

private fun validKeyPart(value: String): Boolean = KEY_PART.matches(value)

private fun validKey(value: String): Boolean =
    value.indexOf('.').let { separator ->
        separator > 0 && separator == value.lastIndexOf('.') &&
            validKeyPart(value.substring(0, separator)) && validKeyPart(value.substring(separator + 1))
    }

private class K2GeneratedBindingIndexVisitor(
    private val generatedBindings: MutableMap<IrFunctionSymbol, String>,
) : IrVisitorVoid() {
    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitFunction(declaration: IrFunction) {
        declaration.generatedBindingKey()?.let { key -> generatedBindings[declaration.symbol] = key }
        super.visitFunction(declaration)
    }
}

/** Canonical, bounded compiler output: it contains no template text, typed values or classpath details. */
private object K2UsageManifestWriter {
    fun write(
        target: Path,
        usages: List<K2Usage>,
    ) {
        val normalized = target.toAbsolutePath().normalize()
        val parent = checkNotNull(normalized.parent) { "a K2 i18n usage manifest target has no parent" }
        require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "a K2 i18n usage manifest parent is not a non-symlink directory"
        }
        val sorted = usages.distinct().sortedWith(compareBy(K2Usage::source, K2Usage::line, K2Usage::key, K2Usage::complete))
        val complete = sorted.all(K2Usage::complete)
        val keys = sorted.mapNotNull(K2Usage::key).distinct().sorted()
        val bytes = document(complete, keys, sorted).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "a K2 i18n usage manifest exceeds $MAX_MANIFEST_BYTES bytes" }
        val staged = Files.createTempFile(parent, ".rain-i18n-k2-", ".tmp")
        try {
            FileChannel.open(staged, WRITE, NOFOLLOW_LINKS).use { channel ->
                var content = ByteBuffer.wrap(bytes)
                while (content.hasRemaining()) channel.write(content)
                channel.force(true)
            }
            try {
                Files.move(staged, normalized, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IllegalStateException("a K2 i18n usage manifest requires an atomic move", failure)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun document(
        complete: Boolean,
        keys: List<String>,
        usages: List<K2Usage>,
    ): String =
        buildString {
            append("{\"schema\":\"rain.i18n.k2-usage/v1\",\"complete\":").append(complete).append(",\"keys\":[")
            keys.forEachIndexed { index, key ->
                if (index > 0) append(',')
                string(key)
            }
            append("],\"usages\":[")
            usages.forEachIndexed { index, usage ->
                if (index > 0) append(',')
                append("{\"source\":")
                string(usage.source)
                append(",\"line\":").append(usage.line).append(",\"key\":")
                usage.key?.let { value -> string(value) } ?: append("null")
                append(",\"complete\":").append(usage.complete).append('}')
            }
            append("]}\n")
        }

    private fun StringBuilder.string(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u").append(character.code.toString(16).padStart(4, '0')) else append(character)
            }
        }
        append('"')
    }

    private const val MAX_MANIFEST_BYTES: Int = 8 * 1024 * 1024
}

private data class K2Usage(
    val source: String,
    val line: Int,
    val key: String?,
    val complete: Boolean,
)
