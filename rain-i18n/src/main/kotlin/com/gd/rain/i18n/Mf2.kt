package com.gd.rain.i18n

import java.math.BigDecimal
import java.text.Normalizer

/** The fixed public grammar profile. New syntax requires a new profile rather than reinterpreting v1. */
public object Mf2Profile {
    public const val ID: String = "rain-mf2/v1"
}

/** A parser finding with a bounded offset into one template. */
public data class TemplateProblem(
    public val offset: Int,
    public val message: String,
) : Comparable<TemplateProblem> {
    override fun compareTo(other: TemplateProblem): Int = compareValuesBy(this, other, TemplateProblem::offset, TemplateProblem::message)
}

/** A compiled v1 pattern; it contains no locale-specific text or mutable formatter. */
public data class Mf2Template internal constructor(
    internal val nodes: List<Mf2Node>,
    internal val declarations: List<Mf2Declaration> = emptyList(),
    internal val select: Mf2Select? = null,
)

/** A declared expression binding. Inputs describe a contract argument; locals are template-private. */
internal data class Mf2Declaration(
    val kind: Mf2DeclarationKind,
    val name: String,
    val expression: Mf2BindingExpression,
    val selectMode: Mf2SelectMode,
)

internal sealed interface Mf2BindingExpression {
    data class Value(
        val expression: Mf2Node.Expression,
    ) : Mf2BindingExpression

    data class Literal(
        val value: String,
    ) : Mf2BindingExpression
}

internal enum class Mf2DeclarationKind {
    INPUT,
    LOCAL,
}

/** The select body of a message. Each variant has exactly one key per selector. */
internal data class Mf2Select(
    val selectors: List<String>,
    val variants: List<Mf2Variant>,
)

internal data class Mf2Variant(
    val keys: List<Mf2SelectorKey>,
    val template: Mf2Template,
)

internal sealed interface Mf2SelectorKey {
    data object Wildcard : Mf2SelectorKey

    data class Exact(
        val value: String,
    ) : Mf2SelectorKey
}

/** A selector's matching semantics, established by its input/local declaration. */
internal enum class Mf2SelectMode {
    EXACT,
    CARDINAL,
    ORDINAL,
}

/** The grammar AST retained by a catalog snapshot. */
public sealed interface Mf2Node {
    public data class Text(
        public val value: String,
    ) : Mf2Node

    public data class Expression(
        public val variable: String,
        public val function: Mf2Function?,
        public val options: Map<String, String>,
        public val metadata: PartMetadata,
    ) : Mf2Node

    public data class MarkupOpen(
        public val name: String,
        public val metadata: PartMetadata,
    ) : Mf2Node

    public data class MarkupClose(
        public val name: String,
        public val metadata: PartMetadata,
    ) : Mf2Node

    public data class MarkupStandalone(
        public val name: String,
        public val metadata: PartMetadata,
    ) : Mf2Node
}

/** The only formatter functions a v1 expression may invoke. */
public enum class Mf2Function {
    STRING,
    NUMBER,
    INTEGER,
    CURRENCY,
    PERCENT,
    OFFSET,
    DATE,
    TIME,
    DATETIME,
    UNIT,
}

/** Metadata carried structurally into rich parts; neither identifier nor direction comes from data. */
public data class PartMetadata(
    public val id: String? = null,
    public val direction: PartDirection = PartDirection.AUTO,
)

/** Bidi direction policy for one value/markup part. */
public enum class PartDirection {
    LTR,
    RTL,
    AUTO,
    INHERIT,
}

/** A template compilation either yields a whole AST or deterministic syntax/contract findings. */
public sealed interface TemplateCompilation {
    public data class Compiled(
        public val template: Mf2Template,
    ) : TemplateCompilation

    public data class Refused(
        public val problems: List<TemplateProblem>,
    ) : TemplateCompilation {
        init {
            require(problems.isNotEmpty()) { "a refused template compilation names a problem" }
        }
    }
}

/**
 * Strict bounded parser for a pattern message. Select/local declarations are parsed by the select
 * compiler stage; the pattern language here is shared by source, translations and every variant.
 */
public object Mf2Compiler {
    /** Compiles a complete `rain-mf2/v1` message, including declarations and select bodies. */
    public fun compile(
        source: String,
        arguments: List<ArgumentSpec>,
        output: OutputKind,
        allowedMarkup: Set<String>,
        limits: I18nLimits,
    ): TemplateCompilation {
        if (source.utf8Size() > limits.maxTemplateBytes) {
            return TemplateCompilation.Refused(listOf(TemplateProblem(0, "template exceeds ${limits.maxTemplateBytes} UTF-8 bytes")))
        }
        return if (source.trimStart().startsWith('.')) {
            MessageParser(source, arguments, output, allowedMarkup, limits).parse()
        } else {
            compilePattern(source, arguments, output, allowedMarkup, limits)
        }
    }

    public fun compilePattern(
        source: String,
        arguments: List<ArgumentSpec>,
        output: OutputKind,
        allowedMarkup: Set<String>,
        limits: I18nLimits,
    ): TemplateCompilation {
        if (source.utf8Size() > limits.maxTemplateBytes) {
            return TemplateCompilation.Refused(listOf(TemplateProblem(0, "template exceeds ${limits.maxTemplateBytes} UTF-8 bytes")))
        }
        val parser = PatternParser(source, arguments.associateBy(ArgumentSpec::name), output, allowedMarkup, limits)
        return parser.parse()
    }
}

/**
 * Parses the declaration/select layer around ordinary patterns. Pattern syntax remains in
 * [PatternParser], keeping one validator for source, translations and every selected variant.
 */
private class MessageParser(
    private val source: String,
    arguments: List<ArgumentSpec>,
    private val output: OutputKind,
    private val allowedMarkup: Set<String>,
    private val limits: I18nLimits,
) {
    private val available = arguments.associateByTo(linkedMapOf(), ArgumentSpec::name)
    private val selectModes = arguments.associate { it.name to defaultSelectMode(null, it.type) }.toMutableMap()
    private val declarations = mutableListOf<Mf2Declaration>()
    private val problems = mutableListOf<TemplateProblem>()
    private var index = 0

    fun parse(): TemplateCompilation {
        while (atLineStart() && index < source.length && source[index] == '.' && !source.startsWith(".match", index)) {
            if (source.startsWith(".input", index)) {
                parseInput()
            } else if (source.startsWith(".local", index)) {
                parseLocal()
            } else {
                problems += TemplateProblem(index, "unknown message declaration")
                skipLine()
            }
            skipLineBreaks()
        }
        val body =
            if (source.startsWith(".match", index)) {
                parseSelect()
            } else {
                parsePatternBody(index, source.substring(index))
            }
        if (body == null || problems.isNotEmpty()) return TemplateCompilation.Refused(problems.sorted())
        return TemplateCompilation.Compiled(Mf2Template(body.nodes, declarations.toList(), body.select))
    }

    private fun parseInput() {
        val start = index
        val line = takeLine().removePrefix(".input").trim()
        val parsed = parseBindingExpression(start, line) ?: return
        val value = parsed.expression as? Mf2BindingExpression.Value
        if (value == null) {
            problems += TemplateProblem(start, "an input declaration contains one variable expression")
            return
        }
        val name = value.expression.variable
        val raw = available[name]
        if (raw == null || declarations.any { it.name == name }) {
            problems += TemplateProblem(start, "input $name is not one declared message argument")
            return
        }
        declarations += Mf2Declaration(Mf2DeclarationKind.INPUT, name, parsed.expression, parsed.selectMode)
        selectModes[name] = parsed.selectMode
    }

    private fun parseLocal() {
        val start = index
        val line = takeLine().removePrefix(".local").trim()
        val match = LOCAL.matchEntire(line)
        if (match == null) {
            problems += TemplateProblem(start, "a local declaration is .local \$name = {expression}")
            return
        }
        val name = match.groupValues[1]
        if (name !in available && !MessageKey.IDENTIFIER.matches(name)) {
            problems += TemplateProblem(start, "local name $name is invalid")
            return
        }
        if (available.containsKey(name)) {
            problems += TemplateProblem(start, "local $name shadows an input or earlier local")
            return
        }
        val parsed = parseBindingExpression(start, match.groupValues[2]) ?: return
        val specification = expressionSpec(name, parsed.expression, start) ?: return
        available[name] = specification
        selectModes[name] = parsed.selectMode
        declarations += Mf2Declaration(Mf2DeclarationKind.LOCAL, name, parsed.expression, parsed.selectMode)
    }

    private fun parseBindingExpression(
        start: Int,
        text: String,
    ): ParsedBinding? {
        if (text.startsWith("{|") && text.endsWith("|}")) {
            val literal = text.substring(2, text.length - 2)
            if (literal.hasUnpairedSurrogate() || literal.utf8Size() > limits.maxTemplateBytes) {
                problems += TemplateProblem(start, "a local literal is valid bounded UTF-8 text")
                return null
            }
            return ParsedBinding(Mf2BindingExpression.Literal(literal), Mf2SelectMode.EXACT)
        }
        val selected = SELECT_OPTION.find(text)?.groupValues?.get(1)
        val normalized = text.replace(SELECT_OPTION, "").normalizeOffsetOptions()
        val template = compilePatternFragment(start, normalized) ?: return null
        val expression = template.nodes.singleOrNull() as? Mf2Node.Expression
        if (expression == null) {
            problems += TemplateProblem(start, "a declaration has exactly one variable expression")
            return null
        }
        val mode = selectMode(selected, expression, start) ?: return null
        return ParsedBinding(Mf2BindingExpression.Value(expression), mode)
    }

    private fun expressionSpec(
        name: String,
        expression: Mf2BindingExpression,
        start: Int,
    ): ArgumentSpec? =
        when (expression) {
            is Mf2BindingExpression.Literal -> {
                ArgumentSpec(name, ArgumentType.TEXT, required = false)
            }

            is Mf2BindingExpression.Value -> {
                val sourceSpec = available[expression.expression.variable]
                if (sourceSpec == null) {
                    problems += TemplateProblem(start, "local $name references an undeclared value")
                    null
                } else {
                    ArgumentSpec(
                        name,
                        sourceSpec.type,
                        required = false,
                        nullable = sourceSpec.nullable,
                        enumValues = sourceSpec.enumValues,
                    )
                }
            }
        }

    private fun selectMode(
        selected: String?,
        expression: Mf2Node.Expression,
        start: Int,
    ): Mf2SelectMode? {
        val type = available[expression.variable]?.type
        if (type == null) {
            problems += TemplateProblem(start, "expression variable ${expression.variable} is not declared")
            return null
        }
        return when (selected?.lowercase()) {
            null -> {
                selectModes[expression.variable] ?: defaultSelectMode(expression.function, type)
            }

            "plural", "cardinal" -> {
                Mf2SelectMode.CARDINAL
            }

            "ordinal" -> {
                Mf2SelectMode.ORDINAL
            }

            "exact" -> {
                Mf2SelectMode.EXACT
            }

            else -> {
                problems += TemplateProblem(start, "select is exact, cardinal or ordinal")
                null
            }
        }
    }

    private fun parseSelect(): Mf2Template? {
        val start = index
        val names =
            takeLine()
                .removePrefix(".match")
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)
        if (names.isEmpty() || names.size > limits.maxArguments) {
            problems += TemplateProblem(start, "a match has 1..${limits.maxArguments} selectors")
            return null
        }
        val selectors = names.map { raw -> raw.removePrefix("$") }
        if (selectors.size != selectors.toSet().size || selectors.any { it !in available }) {
            problems += TemplateProblem(start, "a match names each declared selector once")
            return null
        }
        skipLineBreaks()
        val variants = mutableListOf<Mf2Variant>()
        val seen = mutableSetOf<List<Mf2SelectorKey>>()
        while (index < source.length) {
            skipLineBreaks()
            if (index == source.length) break
            val variantStart = index
            val keys = mutableListOf<Mf2SelectorKey>()
            selectors.forEachIndexed { selectorIndex, _ ->
                val token = takeToken(variantStart)
                if (token == null) return@forEachIndexed
                val key = selectorKey(token, selectModes.getValue(selectors[selectorIndex]), variantStart) ?: return@forEachIndexed
                keys += key
            }
            if (keys.size != selectors.size) {
                skipLine()
                continue
            }
            skipWhitespace()
            if (!source.startsWith("{{", index)) {
                problems += TemplateProblem(variantStart, "a match variant is followed by {{pattern}}")
                skipLine()
                continue
            }
            val patternStart = index + 2
            val patternEnd = endOfWrappedPattern(patternStart)
            if (patternEnd == null) {
                problems += TemplateProblem(index, "a match variant pattern is not closed")
                index = source.length
                break
            }
            val pattern = parsePatternBody(patternStart, source.substring(patternStart, patternEnd))
            index = patternEnd + 2
            if (pattern != null && !seen.add(keys.toList())) {
                problems += TemplateProblem(variantStart, "a match variant duplicates selector keys")
            } else if (pattern != null) {
                variants += Mf2Variant(keys.toList(), pattern)
            }
        }
        if (variants.isEmpty()) {
            problems += TemplateProblem(start, "a match declares at least one variant")
            return null
        }
        if (variants.none { variant -> variant.keys.all { it is Mf2SelectorKey.Wildcard } }) {
            problems += TemplateProblem(start, "a match declares an all-wildcard fallback variant")
        }
        return Mf2Template(emptyList(), emptyList(), Mf2Select(selectors, variants))
    }

    private fun parsePatternBody(
        start: Int,
        body: String,
    ): Mf2Template? {
        val trimmedStart = body.indexOfFirst { !it.isWhitespace() }
        if (trimmedStart >= 0 && body.substring(trimmedStart).startsWith(".match")) {
            val nested = MessageParser(body, available.values.toList(), output, allowedMarkup, limits).parse()
            return nested.orProblem(start, problems)
        }
        val trimmed = body.trim()
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}") && wrappedWhole(trimmed)) {
            val offset = body.indexOf("{{") + 2
            return parsePatternBody(start + offset, trimmed.substring(2, trimmed.length - 2))
        }
        return compilePatternFragment(start, body)
    }

    private fun compilePatternFragment(
        start: Int,
        pattern: String,
    ): Mf2Template? {
        val compiled = Mf2Compiler.compilePattern(pattern, available.values.toList(), output, allowedMarkup, limits)
        return compiled.orProblem(start, problems)
    }

    private fun selectorKey(
        token: String,
        mode: Mf2SelectMode,
        start: Int,
    ): Mf2SelectorKey? {
        if (token == "*") return Mf2SelectorKey.Wildcard
        val value = token.removeSurrounding("|")
        if (value.hasUnpairedSurrogate() || Normalizer.normalize(value, Normalizer.Form.NFC) != value) {
            problems += TemplateProblem(start, "a selector key is NFC text")
            return null
        }
        if (mode == Mf2SelectMode.EXACT) return Mf2SelectorKey.Exact(value)
        if (value in PLURAL_CATEGORIES || canonicalNumber(value)) return Mf2SelectorKey.Exact(value)
        problems += TemplateProblem(start, "a plural selector key is a CLDR category, exact canonical number, or *")
        return null
    }

    private fun takeToken(start: Int): String? {
        skipWhitespace()
        if (index == source.length || source[index] == '\n') {
            problems += TemplateProblem(start, "a match variant has one key per selector")
            return null
        }
        if (source[index] == '|') {
            val end = source.indexOf('|', index + 1)
            if (end < 0) {
                problems += TemplateProblem(index, "a quoted selector key is not closed")
                return null
            }
            return source.substring(index, end + 1).also { index = end + 1 }
        }
        val begin = index
        while (index < source.length && !source[index].isWhitespace() && !source.startsWith("{{", index)) index++
        return source.substring(begin, index).takeIf(String::isNotEmpty)
    }

    private fun endOfWrappedPattern(start: Int): Int? {
        var cursor = start
        var depth = 1
        while (cursor < source.length - 1) {
            when {
                source.startsWith("{{", cursor) -> {
                    depth++
                    cursor += 2
                }

                source.startsWith("}}", cursor) -> {
                    depth--
                    if (depth == 0) return cursor
                    cursor += 2
                }

                else -> {
                    cursor++
                }
            }
        }
        return null
    }

    private fun wrappedWhole(value: String): Boolean = endOfWhole(value) == value.length - 2

    private fun endOfWhole(value: String): Int {
        var cursor = 2
        var depth = 1
        while (cursor < value.length - 1) {
            when {
                value.startsWith("{{", cursor) -> {
                    depth++
                    cursor += 2
                }

                value.startsWith("}}", cursor) -> {
                    depth--
                    if (depth == 0) return cursor
                    cursor += 2
                }

                else -> {
                    cursor++
                }
            }
        }
        return -1
    }

    private fun atLineStart(): Boolean = index == 0 || source[index - 1] == '\n'

    private fun takeLine(): String {
        val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
        return source.substring(index, end).also { index = end }
    }

    private fun skipLine() {
        takeLine()
        skipLineBreaks()
    }

    private fun skipLineBreaks() {
        while (index < source.length && (source[index] == '\n' || source[index] == '\r')) index++
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private data class ParsedBinding(
        val expression: Mf2BindingExpression,
        val selectMode: Mf2SelectMode,
    )

    private companion object {
        val LOCAL: Regex = Regex("\\$([a-z][a-z0-9_]{0,127})\\s*=\\s*(.+)")
        val SELECT_OPTION: Regex = Regex("(?<!\\S)select=([^\\s}]+)")
        val PLURAL_CATEGORIES: Set<String> = setOf("zero", "one", "two", "few", "many", "other")
    }
}

private fun TemplateCompilation.orProblem(
    offset: Int,
    target: MutableList<TemplateProblem>,
): Mf2Template? =
    when (this) {
        is TemplateCompilation.Compiled -> {
            template
        }

        is TemplateCompilation.Refused -> {
            problems.forEach { problem -> target += problem.copy(offset = problem.offset + offset) }
            null
        }
    }

internal fun defaultSelectMode(
    function: Mf2Function?,
    type: ArgumentType,
): Mf2SelectMode =
    when (function) {
        Mf2Function.STRING -> {
            Mf2SelectMode.EXACT
        }

        Mf2Function.NUMBER,
        Mf2Function.INTEGER,
        Mf2Function.CURRENCY,
        Mf2Function.PERCENT,
        Mf2Function.OFFSET,
        Mf2Function.UNIT,
        -> {
            Mf2SelectMode.CARDINAL
        }

        else -> {
            if (
                type in
                setOf(
                    ArgumentType.INTEGER,
                    ArgumentType.UNSIGNED_INTEGER,
                    ArgumentType.BIG_INTEGER,
                    ArgumentType.DECIMAL,
                    ArgumentType.MONEY,
                )
            ) {
                Mf2SelectMode.CARDINAL
            } else {
                Mf2SelectMode.EXACT
            }
        }
    }

private fun String.normalizeOffsetOptions(): String =
    replace(Regex("(?<!\\S)add=([^\\s}]+)"), "offset=$1")
        .replace(Regex("(?<!\\S)subtract=([^\\s}]+)"), "offset=-$1")

private fun canonicalNumber(value: String): Boolean =
    try {
        BigDecimal(value).toPlainString() == value
    } catch (_: NumberFormatException) {
        false
    }

private class PatternParser(
    private val source: String,
    private val arguments: Map<String, ArgumentSpec>,
    private val output: OutputKind,
    private val allowedMarkup: Set<String>,
    private val limits: I18nLimits,
) {
    private val nodes = mutableListOf<Mf2Node>()
    private val problems = mutableListOf<TemplateProblem>()
    private val markup = ArrayDeque<String>()
    private var index = 0

    fun parse(): TemplateCompilation {
        val text = StringBuilder()
        while (index < source.length) {
            when (source[index]) {
                '{' -> {
                    flushText(text)
                    parseBrace()
                }

                '}' -> {
                    problems += TemplateProblem(index, "unexpected closing brace")
                    index++
                }

                else -> {
                    text.append(source[index++])
                }
            }
        }
        flushText(text)
        while (markup.isNotEmpty()) problems += TemplateProblem(source.length, "markup ${markup.removeLast()} is not closed")
        return if (problems.isEmpty()) {
            TemplateCompilation.Compiled(Mf2Template(nodes.toList()))
        } else {
            TemplateCompilation.Refused(problems.sorted())
        }
    }

    private fun flushText(text: StringBuilder) {
        if (text.isNotEmpty()) {
            nodes += Mf2Node.Text(text.toString())
            text.clear()
        }
    }

    private fun parseBrace() {
        val start = index++
        val end = source.indexOf('}', index)
        if (end < 0) {
            problems += TemplateProblem(start, "opening brace is not closed")
            index = source.length
            return
        }
        val body = source.substring(index, end).trim()
        index = end + 1
        when {
            body.startsWith('$') -> parseExpression(start, body)
            body.startsWith('#') -> parseMarkupOpen(start, body)
            body.startsWith('/') -> parseMarkupClose(start, body)
            else -> problems += TemplateProblem(start, "a pattern brace contains an expression or markup tag")
        }
    }

    private fun parseExpression(
        start: Int,
        body: String,
    ) {
        val tokens = tokenize(start, body) ?: return
        val variable = tokens.first().removePrefix("$")
        if (!MessageKey.IDENTIFIER.matches(variable)) {
            problems += TemplateProblem(start, "expression variable ${tokens.first()} is invalid")
            return
        }
        val argument = arguments[variable]
        if (argument == null) {
            problems += TemplateProblem(start, "expression variable $variable is not declared")
            return
        }
        var cursor = 1
        val function =
            tokens.getOrNull(cursor)?.takeIf { it.startsWith(':') }?.let { token ->
                cursor++
                Mf2Function.entries.firstOrNull { it.name.equals(token.removePrefix(":"), ignoreCase = true) }
                    ?: run {
                        problems += TemplateProblem(start, "function $token is not supported by ${Mf2Profile.ID}")
                        return
                    }
            }
        val parsed = parseOptions(start, tokens.drop(cursor)) ?: return
        if (!functionCompatible(function, argument.type)) {
            problems += TemplateProblem(start, "function ${function?.name?.lowercase()} cannot format ${argument.type.name.lowercase()}")
            return
        }
        if (!validateFunctionOptions(start, function, parsed.options)) return
        nodes += Mf2Node.Expression(variable, function, parsed.options, parsed.metadata)
    }

    private fun parseMarkupOpen(
        start: Int,
        body: String,
    ) {
        val standalone = body.endsWith("/")
        val tokens = tokenize(start, body.removeSuffix("/").trim()) ?: return
        val name = tokens.first().removePrefix("#")
        if (!MessageKey.IDENTIFIER.matches(name)) {
            problems += TemplateProblem(start, "markup name $name is invalid")
            return
        }
        if (output != OutputKind.RICH || name !in allowedMarkup) {
            problems += TemplateProblem(start, "markup $name is not allowed by this message contract")
            return
        }
        val options = parseOptions(start, tokens.drop(1)) ?: return
        if (!validateMarkupOptions(start, options.options)) return
        if (standalone) {
            nodes += Mf2Node.MarkupStandalone(name, options.metadata)
        } else {
            if (markup.size == limits.maxNestingDepth) {
                problems += TemplateProblem(start, "markup nesting exceeds ${limits.maxNestingDepth}")
                return
            }
            markup += name
            nodes += Mf2Node.MarkupOpen(name, options.metadata)
        }
    }

    private fun parseMarkupClose(
        start: Int,
        body: String,
    ) {
        val tokens = tokenize(start, body) ?: return
        val name = tokens.first().removePrefix("/")
        if (tokens.size == 1) {
            if (markup.removeLastOrNull() != name) {
                problems += TemplateProblem(start, "markup close $name does not match the open tag")
                return
            }
            nodes += Mf2Node.MarkupClose(name, PartMetadata())
            return
        }
        val options = parseOptions(start, tokens.drop(1)) ?: return
        if (!validateMarkupOptions(start, options.options)) return
        if (markup.removeLastOrNull() != name) {
            problems += TemplateProblem(start, "markup close $name does not match the open tag")
            return
        }
        nodes += Mf2Node.MarkupClose(name, options.metadata)
    }

    private fun tokenize(
        start: Int,
        body: String,
    ): List<String>? {
        val tokens = mutableListOf<String>()
        var cursor = 0
        while (cursor < body.length) {
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
            if (cursor == body.length) break
            val token = StringBuilder()
            if (body[cursor] == '|') {
                cursor++
                while (cursor < body.length && body[cursor] != '|') token.append(body[cursor++])
                if (cursor == body.length) {
                    problems += TemplateProblem(start, "quoted literal is not closed")
                    return null
                }
                cursor++
                tokens += "|$token|"
                continue
            }
            while (cursor < body.length && !body[cursor].isWhitespace()) token.append(body[cursor++])
            tokens += token.toString()
        }
        if (tokens.isEmpty()) {
            problems += TemplateProblem(start, "brace body is empty")
            return null
        }
        return tokens
    }

    private fun parseOptions(
        start: Int,
        tokens: List<String>,
    ): ParsedOptions? {
        val options = linkedMapOf<String, String>()
        var id: String? = null
        var direction = PartDirection.AUTO
        tokens.forEach { token ->
            val equals = token.indexOf('=')
            if (equals <= 0 || equals == token.lastIndex) {
                problems += TemplateProblem(start, "option $token is not key=value")
                return null
            }
            val key = token.substring(0, equals)
            val value = token.substring(equals + 1).removeSurrounding("|")
            if (!OPTION.matches(key) || value.utf8Size() > limits.maxDescriptionBytes) {
                problems += TemplateProblem(start, "option $key is invalid or too long")
                return null
            }
            if (options.putIfAbsent(key, value) != null) {
                problems += TemplateProblem(start, "option $key is stated more than once")
                return null
            }
            when (key) {
                "u:id" -> {
                    id = value.takeUnless { it == "nil" }
                }

                "u:dir" -> {
                    direction =
                        PartDirection.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                            ?: run {
                                problems += TemplateProblem(start, "u:dir is ltr, rtl, auto or inherit")
                                return null
                            }
                }
            }
        }
        return ParsedOptions(options, PartMetadata(id, direction))
    }

    private fun functionCompatible(
        function: Mf2Function?,
        type: ArgumentType,
    ): Boolean =
        when (function) {
            null, Mf2Function.STRING -> {
                true
            }

            Mf2Function.NUMBER, Mf2Function.INTEGER, Mf2Function.PERCENT, Mf2Function.OFFSET, Mf2Function.UNIT -> {
                type in NUMERIC
            }

            Mf2Function.CURRENCY -> {
                type == ArgumentType.MONEY
            }

            Mf2Function.DATE -> {
                type == ArgumentType.DATE || type == ArgumentType.INSTANT
            }

            Mf2Function.TIME, Mf2Function.DATETIME -> {
                type == ArgumentType.INSTANT
            }
        }

    private fun validateFunctionOptions(
        start: Int,
        function: Mf2Function?,
        options: Map<String, String>,
    ): Boolean =
        try {
            val formatting = options.withoutUniversal()
            when (function) {
                null, Mf2Function.STRING -> {
                    require(formatting.isEmpty()) { "string accepts only universal metadata" }
                }

                Mf2Function.NUMBER, Mf2Function.INTEGER, Mf2Function.PERCENT -> {
                    formatting.numberOptions()
                }

                Mf2Function.CURRENCY -> {
                    formatting.numberOptions(setOf("currencySign"))
                    formatting.currencySign()
                }

                Mf2Function.OFFSET -> {
                    formatting.requiredDecimal("offset")
                    formatting.numberOptions(setOf("offset"))
                }

                Mf2Function.UNIT -> {
                    UnitIdentifier.parse(formatting.required("unit"))
                    formatting.numberOptions(setOf("unit"))
                }

                Mf2Function.DATE, Mf2Function.TIME -> {
                    formatting.dateStyle()
                }

                Mf2Function.DATETIME -> {
                    formatting.dateStyle("dateStyle")
                    formatting.dateStyle("timeStyle")
                }
            }
            true
        } catch (failure: IllegalArgumentException) {
            problems += TemplateProblem(start, failure.message ?: "formatter options are invalid")
            false
        }

    private fun validateMarkupOptions(
        start: Int,
        options: Map<String, String>,
    ): Boolean {
        if (options.keys.all { it == "u:id" || it == "u:dir" }) return true
        problems += TemplateProblem(start, "markup accepts only u:id and u:dir metadata")
        return false
    }

    private data class ParsedOptions(
        val options: Map<String, String>,
        val metadata: PartMetadata,
    )

    private companion object {
        val OPTION: Regex = Regex("(?:[a-z][a-zA-Z0-9]*|u:(?:id|dir))")
        val NUMERIC: Set<ArgumentType> =
            setOf(ArgumentType.INTEGER, ArgumentType.UNSIGNED_INTEGER, ArgumentType.BIG_INTEGER, ArgumentType.DECIMAL, ArgumentType.MONEY)
    }
}
