package com.gd.rain.i18n.tool

import com.gd.rain.i18n.MessageKey
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** One literal catalog-key construction discovered in a Kotlin source file. */
public data class KotlinMessageKeyUsage(
    public val key: MessageKey,
    public val source: String,
    public val line: Int,
)

/** A constructor call which the bounded literal extractor cannot prove is an exact catalog key. */
public data class KotlinDynamicMessageKeyUsage(
    public val source: String,
    public val line: Int,
)

/** A deterministic, finite manifest of direct low-level [MessageKey] construction in Kotlin and Java. */
public data class KotlinUsageManifest(
    public val keys: List<MessageKey>,
    public val literalUsages: List<KotlinMessageKeyUsage>,
    public val dynamicUsages: List<KotlinDynamicMessageKeyUsage>,
)

/** A deliberately bounded source input for [KotlinUsageExtractor]. */
public data class KotlinUsageExtractionLimits(
    public val maxFiles: Int = 20_000,
    public val maxFileBytes: Int = 4 * 1024 * 1024,
) {
    init {
        require(maxFiles in 1..100_000) { "Kotlin usage extraction supports 1..100000 files" }
        require(maxFileBytes in 1..16 * 1024 * 1024) { "a Kotlin usage file ceiling is 1..16777216 bytes" }
    }
}

/**
 * Extracts direct, literal Kotlin `MessageKey("module", "name")` and Java
 * `new MessageKey("module", "name")` calls without compiling application code. It is intentionally
 * conservative: interpolation, expressions and malformed syntax become
 * [KotlinDynamicMessageKeyUsage] rather than a guessed key. Generated binder calls require the K2
 * semantic extractor which is a separate future frontend; this parser never claims semantic usage
 * coverage that it cannot prove.
 */
public class KotlinUsageExtractor(
    private val limits: KotlinUsageExtractionLimits = KotlinUsageExtractionLimits(),
) {
    public fun extract(paths: Iterable<Path>): KotlinUsageManifest {
        val files = paths.toList().sortedBy { path -> path.toString() }
        require(files.size <= limits.maxFiles) { "Kotlin usage extraction has more than ${limits.maxFiles} files" }
        val literal = mutableListOf<KotlinMessageKeyUsage>()
        val dynamic = mutableListOf<KotlinDynamicMessageKeyUsage>()
        files.forEach { path ->
            require(path.fileName.toString().let { it.endsWith(".kt") || it.endsWith(".java") }) {
                "i18n usage input ${path.fileName} is neither a .kt nor a .java file"
            }
            val bytes = Files.readAllBytes(path)
            require(bytes.size <= limits.maxFileBytes) { "Kotlin usage input ${path.fileName} exceeds ${limits.maxFileBytes} bytes" }
            val source = path.toString().replace('\\', '/')
            val tokens = Tokenizer(bytes.toString(StandardCharsets.UTF_8)).tokens()
            tokens.indices.forEach { index ->
                if (tokens[index] !is Token.Identifier || (tokens[index] as Token.Identifier).value != "MessageKey") return@forEach
                val opening = tokens.getOrNull(index + 1)
                if (opening !is Token.Symbol || opening.value != '(') return@forEach
                val first = tokens.getOrNull(index + 2)
                val comma = tokens.getOrNull(index + 3)
                val second = tokens.getOrNull(index + 4)
                val closing = tokens.getOrNull(index + 5)
                if (first is Token.StringLiteral &&
                    first.value != null &&
                    comma is Token.Symbol &&
                    comma.value == ',' &&
                    second is Token.StringLiteral &&
                    second.value != null &&
                    closing is Token.Symbol &&
                    closing.value == ')'
                ) {
                    try {
                        literal += KotlinMessageKeyUsage(MessageKey(first.value, second.value), source, tokens[index].line)
                    } catch (_: IllegalArgumentException) {
                        dynamic += KotlinDynamicMessageKeyUsage(source, tokens[index].line)
                    }
                } else {
                    dynamic += KotlinDynamicMessageKeyUsage(source, tokens[index].line)
                }
            }
        }
        return KotlinUsageManifest(
            literal.map(KotlinMessageKeyUsage::key).distinct().sorted(),
            literal.sortedWith(compareBy(KotlinMessageKeyUsage::source, KotlinMessageKeyUsage::line, KotlinMessageKeyUsage::key)),
            dynamic.distinct().sortedWith(compareBy(KotlinDynamicMessageKeyUsage::source, KotlinDynamicMessageKeyUsage::line)),
        )
    }

    private sealed interface Token {
        public val line: Int

        public data class Identifier(
            public val value: String,
            override val line: Int,
        ) : Token

        public data class Symbol(
            public val value: Char,
            override val line: Int,
        ) : Token

        public data class StringLiteral(
            public val value: String?,
            override val line: Int,
        ) : Token
    }

    private class Tokenizer(
        private val input: String,
    ) {
        private var offset: Int = 0
        private var line: Int = 1

        fun tokens(): List<Token> {
            val tokens = mutableListOf<Token>()
            while (offset < input.length) {
                when {
                    input[offset].isWhitespace() -> consumeWhitespace()
                    input.startsWith("//", offset) -> consumeLineComment()
                    input.startsWith("/*", offset) -> consumeBlockComment()
                    input[offset] == '"' -> tokens += consumeString()
                    input[offset].isLetter() || input[offset] == '_' -> tokens += consumeIdentifier()
                    else -> tokens += Token.Symbol(input[offset++], line)
                }
            }
            return tokens
        }

        private fun consumeWhitespace() {
            while (offset < input.length && input[offset].isWhitespace()) {
                if (input[offset++] == '\n') line += 1
            }
        }

        private fun consumeLineComment() {
            while (offset < input.length && input[offset] != '\n') offset += 1
        }

        private fun consumeBlockComment() {
            var depth = 0
            do {
                if (input.startsWith("/*", offset)) {
                    depth += 1
                    offset += 2
                } else if (input.startsWith("*/", offset)) {
                    depth -= 1
                    offset += 2
                } else {
                    if (input[offset++] == '\n') line += 1
                }
            } while (offset < input.length && depth > 0)
        }

        private fun consumeIdentifier(): Token.Identifier {
            val started = line
            val from = offset
            while (offset < input.length && (input[offset].isLetterOrDigit() || input[offset] == '_')) offset += 1
            return Token.Identifier(input.substring(from, offset), started)
        }

        private fun consumeString(): Token.StringLiteral {
            val started = line
            if (input.startsWith("\"\"\"", offset)) return consumeRawString(started)
            offset += 1
            val value = StringBuilder()
            var interpolated = false
            while (offset < input.length) {
                val character = input[offset++]
                when (character) {
                    '"' -> {
                        return Token.StringLiteral(value.toString().takeUnless { interpolated }, started)
                    }

                    '\\' -> {
                        value.append(consumeEscape())
                    }

                    '$' -> {
                        interpolated = true
                        value.append(character)
                    }

                    '\n', '\r' -> {
                        return Token.StringLiteral(null, started)
                    }

                    else -> {
                        value.append(character)
                    }
                }
            }
            return Token.StringLiteral(null, started)
        }

        private fun consumeRawString(started: Int): Token.StringLiteral {
            offset += 3
            val from = offset
            while (offset < input.length && !input.startsWith("\"\"\"", offset)) {
                if (input[offset++] == '\n') line += 1
            }
            if (offset >= input.length) return Token.StringLiteral(null, started)
            val content = input.substring(from, offset)
            offset += 3
            return Token.StringLiteral(content.takeUnless { '$' in content }, started)
        }

        private fun consumeEscape(): Char {
            if (offset >= input.length) return '\u0000'
            val escaped = input[offset++]
            return when (escaped) {
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                'f' -> '\u000C'
                '"' -> '"'
                '\\' -> '\\'
                'u' -> consumeUnicodeEscape()
                else -> escaped
            }
        }

        private fun consumeUnicodeEscape(): Char {
            if (offset + 4 > input.length) return '\u0000'
            val digits = input.substring(offset, offset + 4)
            offset += 4
            return digits.toIntOrNull(16)?.toChar() ?: '\u0000'
        }
    }
}
