package com.gd.rain.i18n.tool

import com.gd.rain.i18n.ArgumentSpec
import com.gd.rain.i18n.ArgumentType
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.MessageRecord

/** One deterministic generated Kotlin source file, kept separate from any Gradle writer. */
public data class GeneratedKotlinSource(
    public val relativePath: String,
    public val content: String,
) {
    init {
        require(relativePath.matches(RELATIVE_KOTLIN_PATH)) { "a generated Kotlin path is a relative .kt path" }
    }

    private companion object {
        val RELATIVE_KOTLIN_PATH: Regex = Regex("[a-zA-Z0-9_/-]+\\.kt")
    }
}

/**
 * Produces the primary magic-first Kotlin binders from an immutable checked snapshot.
 *
 * The output never uses reflection: each message receives an argument data class and a function
 * that verifies the generated contract against the snapshot before constructing a deferred message.
 */
public object KotlinContractGenerator {
    public fun generate(
        snapshot: CatalogSnapshot,
        packageName: String,
        fileName: String = "RainI18nContracts.kt",
    ): GeneratedKotlinSource {
        require(PACKAGE_NAME.matches(packageName)) { "a generated Kotlin package is dot-separated identifiers" }
        require(FILE_NAME.matches(fileName)) { "a generated Kotlin file name is an identifier ending in .kt" }
        val messages = snapshot.keys.sorted().mapNotNull(snapshot::message)
        val output = StringBuilder()
        output.append("@file:Suppress(\"unused\")\n\n")
        output.append("package ").append(packageName).append("\n\n")
        output.append("import com.gd.rain.i18n.*\n")
        output.append("import java.math.BigDecimal\n")
        output.append("import java.math.BigInteger\n")
        output.append("import java.time.Instant\n")
        output.append("import java.time.LocalDate\n")
        output.append("import java.util.Currency\n\n")
        output.append("/** Generated from catalog ").append(snapshot.reference.revision).append(". Do not edit. */\n")
        output.append("public object RainI18nContracts {\n")
        output.append("    public const val CATALOG_REVISION: String = ").append(kotlinString(snapshot.reference.revision)).append("\n")
        output.append("    public const val CATALOG_DIGEST: String = ").append(kotlinString(snapshot.digest.hex)).append("\n\n")
        output.append("    public data class Money(public val amount: BigDecimal, public val currency: Currency)\n\n")
        messages.forEachIndexed { index, record -> appendMessage(output, record, index + 1) }
        output.append("}\n")
        return GeneratedKotlinSource(
            relativePath = packageName.replace('.', '/') + "/" + fileName,
            content = output.toString(),
        )
    }

    private fun appendMessage(
        output: StringBuilder,
        record: MessageRecord,
        index: Int,
    ) {
        val label = "Message$index"
        val arguments = record.spec.arguments.sortedBy(ArgumentSpec::name)
        appendEnums(output, label, arguments)
        if (arguments.isEmpty()) {
            output.append("    public object ").append(label).append("Args\n\n")
        } else {
            output.append("    public data class ").append(label).append("Args(\n")
            arguments.forEachIndexed { argumentIndex, argument ->
                output.append("        public val ").append(valueName(argument.name)).append(": ")
                output.append(kotlinType(label, argument))
                if (!argument.required) output.append(" = OptionalValue.Absent")
                if (argumentIndex != arguments.lastIndex) output.append(',')
                output.append("\n")
            }
            output.append("    )\n\n")
        }
        output
            .append("    @RainI18nGeneratedBinding(\n")
            .append("        key = ")
            .append(kotlinString(record.contract.key.value))
            .append(",\n")
            .append("        revision = ")
            .append(record.contract.revision)
            .append(",\n")
            .append("        contractDigest = ")
            .append(kotlinString(record.contract.digest.value.hex))
            .append(",\n")
            .append("    )\n")
            .append("    public fun ")
            .append(label.replaceFirstChar(Char::lowercase))
            .append("(snapshot: CatalogSnapshot): MessageDefinition<")
        output.append(label).append("Args> =\n")
        output.append("        snapshot.definition(\n")
        output.append("            MessageContractRef(\n")
        output.append("                MessageKey(").append(kotlinString(record.contract.key.module)).append(", ")
        output.append(kotlinString(record.contract.key.name)).append("),\n")
        output.append("                ").append(record.contract.revision).append(",\n")
        output.append("                ContractDigest(Digest.parse(").append(kotlinString(record.contract.digest.value.hex)).append(")),\n")
        output.append("            ),\n")
        output.append("        ) { arguments ->\n")
        output.append("            MessageArguments.build {\n")
        arguments.forEach { argument -> appendEncoding(output, label, argument) }
        output.append("            }\n")
        output.append("        }\n\n")
    }

    private fun appendEnums(
        output: StringBuilder,
        label: String,
        arguments: List<ArgumentSpec>,
    ) {
        arguments.filter { it.type == ArgumentType.ENUM }.forEach { argument ->
            val enumName = enumName(label, argument)
            output.append("    public enum class ").append(enumName).append("(public val wire: String) {\n")
            argument.enumValues.sorted().forEachIndexed { index, value ->
                output
                    .append("        ")
                    .append(enumMember(value))
                    .append('(')
                    .append(kotlinString(value))
                    .append(')')
                if (index != argument.enumValues.size - 1) output.append(',') else output.append(';')
                output.append("\n")
            }
            output.append("    }\n\n")
        }
    }

    private fun appendEncoding(
        output: StringBuilder,
        label: String,
        argument: ArgumentSpec,
    ) {
        val field = "arguments.${valueName(argument.name)}"
        val encoded = encode(argument, label, "value")
        if (!argument.required) {
            output.append("                when (val value = ").append(field).append(") {\n")
            output.append("                    OptionalValue.Absent -> {}\n")
            output.append("                    OptionalValue.Null -> nullValue(").append(kotlinString(argument.name)).append(")\n")
            output.append("                    is OptionalValue.Present -> value(").append(kotlinString(argument.name)).append(", ")
            output.append(encoded).append(")\n")
            output.append("                }\n")
        } else if (argument.nullable) {
            output.append("                ").append(field).append("?.let { value -> value(")
            output
                .append(kotlinString(argument.name))
                .append(", ")
                .append(encoded)
                .append(") } ?: nullValue(")
            output.append(kotlinString(argument.name)).append(")\n")
        } else {
            output.append("                value(").append(kotlinString(argument.name)).append(", ")
            output.append(encode(argument, label, field)).append(")\n")
        }
    }

    private fun kotlinType(
        label: String,
        argument: ArgumentSpec,
    ): String {
        val base =
            when (argument.type) {
                ArgumentType.TEXT -> "String"
                ArgumentType.BOOLEAN -> "Boolean"
                ArgumentType.INTEGER -> "Long"
                ArgumentType.UNSIGNED_INTEGER -> "UnsignedLong"
                ArgumentType.BIG_INTEGER -> "BigInteger"
                ArgumentType.DECIMAL -> "BigDecimal"
                ArgumentType.MONEY -> "Money"
                ArgumentType.DATE -> "LocalDate"
                ArgumentType.INSTANT -> "Instant"
                ArgumentType.ENUM -> enumName(label, argument)
            }
        return when {
            !argument.required -> "OptionalValue<$base>"
            argument.nullable -> "$base?"
            else -> base
        }
    }

    private fun encode(
        argument: ArgumentSpec,
        label: String,
        value: String,
    ): String =
        when (argument.type) {
            ArgumentType.TEXT -> "MessageValue.Text($value)"
            ArgumentType.BOOLEAN -> "MessageValue.BooleanValue($value)"
            ArgumentType.INTEGER -> "MessageValue.IntegerValue($value)"
            ArgumentType.UNSIGNED_INTEGER -> "MessageValue.UnsignedIntegerValue($value)"
            ArgumentType.BIG_INTEGER -> "MessageValue.BigIntegerValue($value)"
            ArgumentType.DECIMAL -> "MessageValue.DecimalValue($value)"
            ArgumentType.MONEY -> "MessageValue.MoneyValue($value.amount, $value.currency)"
            ArgumentType.DATE -> "MessageValue.DateValue($value)"
            ArgumentType.INSTANT -> "MessageValue.InstantValue($value)"
            ArgumentType.ENUM -> "MessageValue.EnumValue($value.wire)"
        }

    private fun enumName(
        label: String,
        argument: ArgumentSpec,
    ): String = label + title(argument.name) + "Value"

    private fun valueName(value: String): String = if (value in KOTLIN_KEYWORDS) "${value}_" else value

    private fun enumMember(value: String): String = "VALUE_" + value.uppercase()

    private fun title(value: String): String = value.split('_').joinToString("") { piece -> piece.replaceFirstChar(Char::uppercase) }

    private fun kotlinString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

    private val PACKAGE_NAME: Regex = Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*")
    private val FILE_NAME: Regex = Regex("[a-zA-Z_][a-zA-Z0-9_]*\\.kt")
    private val KOTLIN_KEYWORDS: Set<String> =
        setOf(
            "as",
            "break",
            "class",
            "continue",
            "do",
            "else",
            "false",
            "for",
            "fun",
            "if",
            "in",
            "interface",
            "is",
            "null",
            "object",
            "package",
            "return",
            "super",
            "this",
            "throw",
            "true",
            "try",
            "typealias",
            "val",
            "var",
            "when",
            "while",
        )
}

/** A generated public TypeScript declaration and its separately consumable structural manifest. */
public class TypeScriptExport internal constructor(
    declarations: ByteArray,
    manifest: ByteArray,
) {
    private val declarations: ByteArray = declarations.copyOf()
    private val manifest: ByteArray = manifest.copyOf()

    /** Digest of the exact two content roles, used as a publication generation identity. */
    public val digest: Digest = Digest.sha256(this.declarations + byteArrayOf(0) + this.manifest)

    public fun declarationsBytes(): ByteArray = declarations.copyOf()

    public fun manifestBytes(): ByteArray = manifest.copyOf()
}

/** Exports only `public=true` message schemas; no translation wording or formatter parity leaks out. */
public object TypeScriptContractExporter {
    public fun export(snapshot: CatalogSnapshot): TypeScriptExport {
        val records =
            snapshot.keys
                .sorted()
                .mapNotNull(snapshot::message)
                .filter { it.spec.public }
        return TypeScriptExport(
            declarations(records).toByteArray(Charsets.UTF_8),
            manifest(snapshot, records).toByteArray(Charsets.UTF_8),
        )
    }

    private fun declarations(records: List<MessageRecord>): String =
        buildString {
            append("// Generated by rain-i18n-tool. formattingParity=false.\n")
            append("export type RainI18nInt64 = string & { readonly __rainI18nInt64: unique symbol };\n")
            append("export type RainI18nUint64 = string & { readonly __rainI18nUint64: unique symbol };\n")
            append("export type RainI18nBigInt = string & { readonly __rainI18nBigInt: unique symbol };\n")
            append("export type RainI18nDecimal = string & { readonly __rainI18nDecimal: unique symbol };\n")
            append("export type RainI18nDate = string & { readonly __rainI18nDate: unique symbol };\n")
            append("export type RainI18nInstant = string & { readonly __rainI18nInstant: unique symbol };\n")
            append("export type RainI18nMoney = { amount: RainI18nDecimal; currency: string };\n\n")
            append("export type RainI18nMessageKey = ")
            if (records.isEmpty()) append("never") else append(records.joinToString(" | ") { tsString(it.contract.key.value) })
            append(";\n\n")
            append("export interface RainI18nMessageArguments {\n")
            records.forEach { record ->
                append("  ").append(tsString(record.contract.key.value)).append(": {")
                if (record.spec.arguments.isEmpty()) {
                    append("};\n")
                } else {
                    append("\n")
                    record.spec.arguments.sortedBy(ArgumentSpec::name).forEach { argument ->
                        append("    ").append(tsString(argument.name))
                        if (!argument.required) append('?')
                        append(": ").append(tsType(argument))
                        if (argument.nullable) append(" | null")
                        append(";\n")
                    }
                    append("  };\n")
                }
            }
            append("}\n")
        }

    private fun manifest(
        snapshot: CatalogSnapshot,
        records: List<MessageRecord>,
    ): String =
        buildString {
            append("{\"schema\":\"rain.i18n.public/v1\",\"catalog\":{\"revision\":")
            append(jsonString(snapshot.reference.revision)).append(",\"digest\":").append(jsonString(snapshot.digest.hex))
            append("},\"formattingParity\":false,\"messages\":[")
            records.forEachIndexed { index, record ->
                if (index > 0) append(',')
                append("{\"key\":").append(jsonString(record.contract.key.value))
                append(",\"contractRevision\":").append(record.contract.revision)
                append(",\"contractDigest\":").append(jsonString(record.contract.digest.value.hex))
                append(",\"output\":").append(jsonString(record.spec.output.name))
                append(",\"markup\":[")
                record.spec.markup.sorted().forEachIndexed { markupIndex, tag ->
                    if (markupIndex > 0) append(',')
                    append(jsonString(tag))
                }
                append("],\"arguments\":[")
                record.spec.arguments.sortedBy(ArgumentSpec::name).forEachIndexed { argumentIndex, argument ->
                    if (argumentIndex > 0) append(',')
                    append("{\"name\":").append(jsonString(argument.name))
                    append(",\"type\":").append(jsonString(argument.type.name))
                    append(",\"required\":").append(argument.required)
                    append(",\"nullable\":").append(argument.nullable)
                    append(",\"enumValues\":[")
                    argument.enumValues.sorted().forEachIndexed { enumIndex, value ->
                        if (enumIndex > 0) append(',')
                        append(jsonString(value))
                    }
                    append("]}")
                }
                append("]}")
            }
            append("]}\n")
        }

    private fun tsType(argument: ArgumentSpec): String =
        when (argument.type) {
            ArgumentType.TEXT -> "string"
            ArgumentType.BOOLEAN -> "boolean"
            ArgumentType.INTEGER -> "RainI18nInt64"
            ArgumentType.UNSIGNED_INTEGER -> "RainI18nUint64"
            ArgumentType.BIG_INTEGER -> "RainI18nBigInt"
            ArgumentType.DECIMAL -> "RainI18nDecimal"
            ArgumentType.MONEY -> "RainI18nMoney"
            ArgumentType.DATE -> "RainI18nDate"
            ArgumentType.INSTANT -> "RainI18nInstant"
            ArgumentType.ENUM -> argument.enumValues.sorted().joinToString(" | ", transform = ::tsString)
        }

    private fun tsString(value: String): String = jsonString(value)

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> {
                        append("\\\\")
                    }

                    '"' -> {
                        append("\\\"")
                    }

                    '\b' -> {
                        append("\\b")
                    }

                    '\u000C' -> {
                        append("\\f")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    else -> {
                        if (character.code < 0x20) {
                            append("\\u").append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
            append('"')
        }
}
