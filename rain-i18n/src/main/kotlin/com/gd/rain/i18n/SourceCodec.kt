package com.gd.rain.i18n

import tools.jackson.core.JsonEncoding
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.ObjectWriteContext
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.json.JsonFactory
import java.io.ByteArrayOutputStream
import java.time.ZoneId

/** One bounded, path-addressed canonical-source decode finding. */
public data class SourceCodecProblem(
    public val path: String,
    public val message: String,
) : Comparable<SourceCodecProblem> {
    override fun compareTo(other: SourceCodecProblem): Int =
        compareValuesBy(this, other, SourceCodecProblem::path, SourceCodecProblem::message)
}

/** Decoding never returns a partially populated source declaration. */
public sealed interface CatalogSourceDecoding {
    public data class Decoded(
        public val source: CatalogSpec,
    ) : CatalogSourceDecoding

    public data class Refused(
        public val problems: List<SourceCodecProblem>,
    ) : CatalogSourceDecoding {
        init {
            require(problems.isNotEmpty()) { "a refused source decode names a problem" }
        }
    }
}

/**
 * Strict canonical JSON codec for `rain.i18n.source/v1`.
 *
 * It uses Jackson Core tokens only: no data binding, tree, duplicate-field overwrite or unknown
 * property tolerance can enter a catalog. The source format retains review stamps verbatim; the
 * compiler then validates their semantic relationship to the wording and contract.
 */
public class CatalogSourceCodec(
    private val limits: I18nLimits = I18nLimits(),
) {
    /** Emits one deterministic UTF-8 JSON document with stable field, message and set ordering. */
    public fun encode(source: CatalogSpec): ByteArray {
        val output = ByteArrayOutputStream()
        JsonFactory().createGenerator(ObjectWriteContext.empty(), output, JsonEncoding.UTF8).use { generator ->
            generator.writeStartObject()
            generator.writeStringProperty("schema", SCHEMA)
            identity(generator, source.identity)
            generator.writeStringProperty("sourceLocale", source.sourceLocale.value)
            localePolicy(generator, source.localePolicy)
            stringArray(generator, "requiredLocales", source.requiredLocales.map(LocaleTag::value).sorted())
            generator.writeStringProperty("defaultZone", source.defaultZone.id)
            codecLimits(generator, source.limits)
            generator.writeName("messages")
            generator.writeStartArray()
            source.messages.sortedBy(MessageSpec::key).forEach { message -> message(generator, message) }
            generator.writeEndArray()
            generator.writeEndObject()
        }
        val bytes = output.toByteArray()
        require(bytes.size <= limits.maxCatalogBytes) { "canonical source exceeds ${limits.maxCatalogBytes} bytes" }
        return bytes
    }

    /** Parses only the current schema and validates byte/token/depth limits before model creation. */
    public fun decode(bytes: ByteArray): CatalogSourceDecoding {
        if (bytes.size > limits.maxCatalogBytes) {
            return CatalogSourceDecoding.Refused(listOf(SourceCodecProblem("$", "source exceeds ${limits.maxCatalogBytes} bytes")))
        }
        return try {
            SourceReader(bytes, limits).read()
        } catch (failure: SourceRefusal) {
            CatalogSourceDecoding.Refused(listOf(SourceCodecProblem(failure.path, failure.message)))
        } catch (_: Exception) {
            CatalogSourceDecoding.Refused(listOf(SourceCodecProblem("$", "malformed canonical JSON source")))
        }
    }

    private fun identity(
        generator: JsonGenerator,
        identity: CatalogIdentity,
    ) {
        generator.writeName("identity")
        generator.writeStartObject()
        generator.writeStringProperty("revision", identity.revision)
        generator.writeStringProperty("profile", identity.profile)
        generator.writeStringProperty("engine", identity.engine)
        generator.writeStringProperty("icuClDrTzdbIdentity", identity.icuClDrTzdbIdentity)
        generator.writeEndObject()
    }

    private fun localePolicy(
        generator: JsonGenerator,
        policy: LocalePolicy,
    ) {
        generator.writeName("localePolicy")
        generator.writeStartObject()
        stringArray(generator, "supported", policy.supported.map(LocaleTag::value).sorted())
        generator.writeStringProperty("defaultLocale", policy.defaultLocale.value)
        generator.writeName("parents")
        generator.writeStartArray()
        policy.parents.toSortedMap().forEach { (locale, parent) ->
            generator.writeStartObject()
            generator.writeStringProperty("locale", locale.value)
            generator.writeStringProperty("parent", parent.value)
            generator.writeEndObject()
        }
        generator.writeEndArray()
        generator.writeStringProperty("matchMode", policy.matchMode.name)
        generator.writeStringProperty("defaultOnMiss", policy.defaultOnMiss.name)
        generator.writeEndObject()
    }

    private fun codecLimits(
        generator: JsonGenerator,
        value: I18nLimits,
    ) {
        generator.writeName("limits")
        generator.writeStartObject()
        generator.writeNumberProperty("maxCatalogBytes", value.maxCatalogBytes)
        generator.writeNumberProperty("maxArtifactBytes", value.maxArtifactBytes)
        generator.writeNumberProperty("maxMessages", value.maxMessages)
        generator.writeNumberProperty("maxLocales", value.maxLocales)
        generator.writeNumberProperty("maxArguments", value.maxArguments)
        generator.writeNumberProperty("maxIdentifierBytes", value.maxIdentifierBytes)
        generator.writeNumberProperty("maxTemplateBytes", value.maxTemplateBytes)
        generator.writeNumberProperty("maxDescriptionBytes", value.maxDescriptionBytes)
        generator.writeNumberProperty("maxOutputBytes", value.maxOutputBytes)
        generator.writeNumberProperty("maxOutputParts", value.maxOutputParts)
        generator.writeNumberProperty("maxNestingDepth", value.maxNestingDepth)
        generator.writeNumberProperty("maxLocaleRangeBytes", value.maxLocaleRangeBytes)
        generator.writeNumberProperty("maxLocaleRanges", value.maxLocaleRanges)
        generator.writeNumberProperty("maxExplanationBytes", value.maxExplanationBytes)
        generator.writeEndObject()
    }

    private fun message(
        generator: JsonGenerator,
        value: MessageSpec,
    ) {
        generator.writeStartObject()
        generator.writeStringProperty("key", value.key.value)
        generator.writeNumberProperty("contractRevision", value.contractRevision)
        generator.writeStringProperty("source", value.source)
        generator.writeStringProperty("description", value.description)
        generator.writeName("arguments")
        generator.writeStartArray()
        value.arguments.sortedBy(ArgumentSpec::name).forEach { argument -> argument(generator, argument) }
        generator.writeEndArray()
        generator.writeStringProperty("output", value.output.name)
        stringArray(generator, "markup", value.markup.sorted())
        generator.writeStringProperty("overridePolicy", value.overridePolicy.name)
        generator.writeBooleanProperty("allowEmpty", value.allowEmpty)
        generator.writeBooleanProperty("public", value.public)
        generator.writeName("translations")
        generator.writeStartArray()
        value.translations.sortedBy(TranslationSpec::locale).forEach { translation -> translation(generator, translation) }
        generator.writeEndArray()
        generator.writeEndObject()
    }

    private fun argument(
        generator: JsonGenerator,
        value: ArgumentSpec,
    ) {
        generator.writeStartObject()
        generator.writeStringProperty("name", value.name)
        generator.writeStringProperty("type", value.type.name)
        generator.writeBooleanProperty("required", value.required)
        generator.writeBooleanProperty("nullable", value.nullable)
        stringArray(generator, "enumValues", value.enumValues.sorted())
        generator.writeEndObject()
    }

    private fun translation(
        generator: JsonGenerator,
        value: TranslationSpec,
    ) {
        generator.writeStartObject()
        generator.writeStringProperty("locale", value.locale.value)
        generator.writeStringProperty("text", value.text)
        generator.writeStringProperty("review", value.review.name)
        value.reviewedSource?.let { generator.writeStringProperty("reviewedSource", it.value.hex) }
        value.reviewDigest?.let { generator.writeStringProperty("reviewDigest", it.value.hex) }
        generator.writeEndObject()
    }

    private fun stringArray(
        generator: JsonGenerator,
        name: String,
        values: List<String>,
    ) {
        generator.writeName(name)
        generator.writeStartArray()
        values.forEach(generator::writeString)
        generator.writeEndArray()
    }

    private companion object {
        const val SCHEMA: String = "rain.i18n.source/v1"
    }
}

private class SourceRefusal(
    val path: String,
    override val message: String,
) : IllegalArgumentException(message)

private class SourceReader(
    private val bytes: ByteArray,
    private val localLimits: I18nLimits,
) {
    private val parser: JsonParser =
        JsonFactory
            .builder()
            .streamReadConstraints(
                StreamReadConstraints
                    .builder()
                    .maxDocumentLength(localLimits.maxCatalogBytes.toLong())
                    .maxNestingDepth(localLimits.maxNestingDepth)
                    .maxTokenCount(localLimits.maxMessages.toLong() * (localLimits.maxArguments + 32L))
                    .maxStringLength(maxOf(localLimits.maxTemplateBytes, localLimits.maxDescriptionBytes))
                    .maxNameLength(localLimits.maxIdentifierBytes)
                    .build(),
            ).build()
            .createParser(ObjectReadContext.empty(), bytes, 0, bytes.size)

    fun read(): CatalogSourceDecoding =
        parser.use {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "$")
            var schema: String? = null
            var identity: CatalogIdentity? = null
            var sourceLocale: LocaleTag? = null
            var localePolicy: LocalePolicy? = null
            var requiredLocales: Set<LocaleTag>? = null
            var defaultZone: ZoneId? = null
            var limits: I18nLimits? = null
            var messages: List<MessageSpec>? = null
            objectFields(
                "$",
                setOf("schema", "identity", "sourceLocale", "localePolicy", "requiredLocales", "defaultZone", "limits", "messages"),
            ) { name ->
                when (name) {
                    "schema" -> schema = string("$.schema")
                    "identity" -> identity = identity("$.identity")
                    "sourceLocale" -> sourceLocale = locale("$.sourceLocale")
                    "localePolicy" -> localePolicy = localePolicy("$.localePolicy")
                    "requiredLocales" -> requiredLocales = localeArray("$.requiredLocales", localLimits.maxLocales).toSet()
                    "defaultZone" -> defaultZone = zone("$.defaultZone")
                    "limits" -> limits = limits("$.limits")
                    "messages" -> messages = messages("$.messages")
                }
            }
            requireValue(schema, "$.schema") { it == "rain.i18n.source/v1" }
            val decoded =
                try {
                    CatalogSpec(
                        requireValue(identity, "$.identity") { true },
                        requireValue(sourceLocale, "$.sourceLocale") { true },
                        requireValue(localePolicy, "$.localePolicy") { true },
                        requireValue(requiredLocales, "$.requiredLocales") { true },
                        requireValue(defaultZone, "$.defaultZone") { true },
                        requireValue(limits, "$.limits") { true },
                        requireValue(messages, "$.messages") { true },
                    )
                } catch (failure: IllegalArgumentException) {
                    throw SourceRefusal("$", failure.message ?: "source fields are inconsistent")
                }
            if (parser.nextToken() != null) refuse("$", "trailing JSON value")
            val compilation = CatalogCompiler.compile(decoded)
            if (compilation is CatalogCompilation.Refused) {
                return CatalogSourceDecoding.Refused(
                    compilation.problems.map { problem ->
                        SourceCodecProblem(problem.path, problem.message)
                    },
                )
            }
            if (!CatalogSourceCodec(localLimits).encode(decoded).contentEquals(bytes)) {
                return CatalogSourceDecoding.Refused(
                    listOf(SourceCodecProblem("$", "source bytes are not in canonical rain.i18n.source/v1 form")),
                )
            }
            return CatalogSourceDecoding.Decoded(decoded)
        }

    private fun identity(path: String): CatalogIdentity {
        var revision: String? = null
        var profile: String? = null
        var engine: String? = null
        var dataIdentity: String? = null
        objectFields(path, setOf("revision", "profile", "engine", "icuClDrTzdbIdentity")) { name ->
            when (name) {
                "revision" -> revision = string("$path.revision")
                "profile" -> profile = string("$path.profile")
                "engine" -> engine = string("$path.engine")
                "icuClDrTzdbIdentity" -> dataIdentity = string("$path.icuClDrTzdbIdentity")
            }
        }
        return try {
            CatalogIdentity(
                requireValue(revision, "$path.revision") { true },
                requireValue(profile, "$path.profile") { true },
                requireValue(engine, "$path.engine") { true },
                requireValue(dataIdentity, "$path.icuClDrTzdbIdentity") { true },
            )
        } catch (failure: IllegalArgumentException) {
            throw SourceRefusal(path, failure.message ?: "invalid identity")
        }
    }

    private fun localePolicy(path: String): LocalePolicy {
        var supported: Set<LocaleTag>? = null
        var defaultLocale: LocaleTag? = null
        var parents: Map<LocaleTag, LocaleTag>? = null
        var matchMode: LocaleMatchMode? = null
        var defaultOnMiss: DefaultOnMiss? = null
        objectFields(path, setOf("supported", "defaultLocale", "parents", "matchMode", "defaultOnMiss")) { name ->
            when (name) {
                "supported" -> supported = localeArray("$path.supported", localLimits.maxLocales).toSet()
                "defaultLocale" -> defaultLocale = locale("$path.defaultLocale")
                "parents" -> parents = parents("$path.parents")
                "matchMode" -> matchMode = enum("$path.matchMode")
                "defaultOnMiss" -> defaultOnMiss = enum("$path.defaultOnMiss")
            }
        }
        return try {
            LocalePolicy(
                requireValue(supported, "$path.supported") { it.isNotEmpty() },
                requireValue(defaultLocale, "$path.defaultLocale") { true },
                requireValue(parents, "$path.parents") { true },
                requireValue(matchMode, "$path.matchMode") { true },
                requireValue(defaultOnMiss, "$path.defaultOnMiss") { true },
            )
        } catch (failure: IllegalArgumentException) {
            throw SourceRefusal(path, failure.message ?: "invalid locale policy")
        }
    }

    private fun parents(path: String): Map<LocaleTag, LocaleTag> =
        array(path, localLimits.maxLocales) { itemPath ->
            var locale: LocaleTag? = null
            var parent: LocaleTag? = null
            objectFields(itemPath, setOf("locale", "parent")) { name ->
                when (name) {
                    "locale" -> locale = locale("$itemPath.locale")
                    "parent" -> parent = locale("$itemPath.parent")
                }
            }
            requireValue(locale, "$itemPath.locale") { true } to requireValue(parent, "$itemPath.parent") { true }
        }.also { values ->
            if (values.map(Pair<LocaleTag, LocaleTag>::first).toSet().size != values.size) refuse(path, "duplicate parent locale")
        }.toMap()

    private fun limits(path: String): I18nLimits {
        val values = mutableMapOf<String, Int>()
        val names = LIMIT_NAMES
        objectFields(path, names) { name -> values[name] = integer("$path.$name") }
        if (values.keys != names) refuse(path, "limits have every required field exactly once")
        return try {
            I18nLimits(
                values.getValue("maxCatalogBytes"),
                values.getValue("maxArtifactBytes"),
                values.getValue("maxMessages"),
                values.getValue("maxLocales"),
                values.getValue("maxArguments"),
                values.getValue("maxIdentifierBytes"),
                values.getValue("maxTemplateBytes"),
                values.getValue("maxDescriptionBytes"),
                values.getValue("maxOutputBytes"),
                values.getValue("maxOutputParts"),
                values.getValue("maxNestingDepth"),
                values.getValue("maxLocaleRangeBytes"),
                values.getValue("maxLocaleRanges"),
                values.getValue("maxExplanationBytes"),
            )
        } catch (failure: IllegalArgumentException) {
            throw SourceRefusal(path, failure.message ?: "invalid limits")
        }
    }

    private fun messages(path: String): List<MessageSpec> =
        array(path, localLimits.maxMessages) { itemPath -> message(itemPath) }.also { values ->
            if (values.map(MessageSpec::key).toSet().size != values.size) refuse(path, "duplicate message key")
        }

    private fun message(path: String): MessageSpec {
        var key: MessageKey? = null
        var revision: Int? = null
        var source: String? = null
        var description: String? = null
        var arguments: List<ArgumentSpec>? = null
        var output: OutputKind? = null
        var markup: Set<String>? = null
        var overridePolicy: OverridePolicy? = null
        var allowEmpty: Boolean? = null
        var public: Boolean? = null
        var translations: List<TranslationSpec>? = null
        objectFields(path, MESSAGE_NAMES) { name ->
            when (name) {
                "key" -> key = key("$path.key")
                "contractRevision" -> revision = integer("$path.contractRevision")
                "source" -> source = string("$path.source")
                "description" -> description = string("$path.description")
                "arguments" -> arguments = arguments("$path.arguments")
                "output" -> output = enum("$path.output")
                "markup" -> markup = stringArray("$path.markup", localLimits.maxArguments).toSet()
                "overridePolicy" -> overridePolicy = enum("$path.overridePolicy")
                "allowEmpty" -> allowEmpty = boolean("$path.allowEmpty")
                "public" -> public = boolean("$path.public")
                "translations" -> translations = translations("$path.translations")
            }
        }
        return try {
            MessageSpec(
                requireValue(key, "$path.key") { true },
                requireValue(revision, "$path.contractRevision") { true },
                requireValue(source, "$path.source") { true },
                requireValue(description, "$path.description") { true },
                requireValue(arguments, "$path.arguments") { true },
                requireValue(output, "$path.output") { true },
                requireValue(markup, "$path.markup") { true },
                requireValue(overridePolicy, "$path.overridePolicy") { true },
                requireValue(allowEmpty, "$path.allowEmpty") {
                    true
                },
                requireValue(public, "$path.public") { true },
                requireValue(translations, "$path.translations") { true },
            )
        } catch (failure: IllegalArgumentException) {
            throw SourceRefusal(path, failure.message ?: "invalid message")
        }
    }

    private fun arguments(path: String): List<ArgumentSpec> =
        array(path, localLimits.maxArguments) { itemPath ->
            var name: String? = null
            var type: ArgumentType? = null
            var required: Boolean? = null
            var nullable: Boolean? = null
            var enumValues: Set<String>? = null
            objectFields(itemPath, ARGUMENT_NAMES) { field ->
                when (field) {
                    "name" -> name = string("$itemPath.name")
                    "type" -> type = enum("$itemPath.type")
                    "required" -> required = boolean("$itemPath.required")
                    "nullable" -> nullable = boolean("$itemPath.nullable")
                    "enumValues" -> enumValues = stringArray("$itemPath.enumValues", localLimits.maxArguments).toSet()
                }
            }
            try {
                ArgumentSpec(
                    requireValue(name, "$itemPath.name") { true },
                    requireValue(type, "$itemPath.type") { true },
                    requireValue(required, "$itemPath.required") { true },
                    requireValue(nullable, "$itemPath.nullable") { true },
                    requireValue(enumValues, "$itemPath.enumValues") { true },
                )
            } catch (failure: IllegalArgumentException) {
                throw SourceRefusal(itemPath, failure.message ?: "invalid argument")
            }
        }.also { values -> if (values.map(ArgumentSpec::name).toSet().size != values.size) refuse(path, "duplicate argument name") }

    private fun translations(path: String): List<TranslationSpec> =
        array(path, localLimits.maxLocales) { itemPath ->
            var locale: LocaleTag? = null
            var text: String? = null
            var review: TranslationReview? = null
            var reviewedSource: SourceDigest? = null
            var reviewDigest: ReviewDigest? = null
            objectFields(itemPath, TRANSLATION_NAMES) { field ->
                when (field) {
                    "locale" -> locale = locale("$itemPath.locale")
                    "text" -> text = string("$itemPath.text")
                    "review" -> review = enum("$itemPath.review")
                    "reviewedSource" -> reviewedSource = SourceDigest(Digest.parse(string("$itemPath.reviewedSource")))
                    "reviewDigest" -> reviewDigest = ReviewDigest(Digest.parse(string("$itemPath.reviewDigest")))
                }
            }
            try {
                TranslationSpec(
                    requireValue(locale, "$itemPath.locale") { true },
                    requireValue(text, "$itemPath.text") { true },
                    requireValue(review, "$itemPath.review") { true },
                    reviewedSource,
                    reviewDigest,
                )
            } catch (failure: IllegalArgumentException) {
                throw SourceRefusal(itemPath, failure.message ?: "invalid translation")
            }
        }.also { values ->
            if (values.map(TranslationSpec::locale).toSet().size !=
                values.size
            ) {
                refuse(path, "duplicate translation locale")
            }
        }

    private fun localeArray(
        path: String,
        maximum: Int,
    ): List<LocaleTag> =
        array(path, maximum) { itemPath -> locale(itemPath) }.also { values ->
            if (values.toSet().size != values.size) refuse(path, "duplicate locale")
        }

    private fun stringArray(
        path: String,
        maximum: Int,
    ): List<String> =
        array(path, maximum) { itemPath -> string(itemPath) }.also { values ->
            if (values.toSet().size != values.size) refuse(path, "duplicate value")
        }

    private fun locale(path: String): LocaleTag =
        try {
            LocaleTag.parse(string(path))
        } catch (failure: IllegalArgumentException) {
            throw SourceRefusal(path, failure.message ?: "invalid locale")
        }

    private fun zone(path: String): ZoneId =
        try {
            ZoneId.of(string(path))
        } catch (failure: Exception) {
            throw SourceRefusal(path, "invalid IANA zone")
        }

    private fun key(path: String): MessageKey =
        try {
            MessageKey.parse(string(path))
        } catch (failure: IllegalArgumentException) {
            throw SourceRefusal(path, failure.message ?: "invalid message key")
        }

    private inline fun <reified T : Enum<T>> enum(path: String): T {
        val value = string(path)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw SourceRefusal(path, "unsupported ${T::class.simpleName} value")
    }

    private fun string(path: String): String {
        requireToken(parser.currentToken(), JsonToken.VALUE_STRING, path)
        return parser.string.also { value ->
            if (value.utf8Size() >
                localLimits.maxTemplateBytes
            ) {
                refuse(path, "string exceeds configured source bound")
            }
        }
    }

    private fun integer(path: String): Int {
        requireToken(parser.currentToken(), JsonToken.VALUE_NUMBER_INT, path)
        return try {
            parser.intValue
        } catch (_: Exception) {
            refuse(path, "integer is out of range")
        }
    }

    private fun boolean(path: String): Boolean =
        when (parser.currentToken()) {
            JsonToken.VALUE_TRUE -> true
            JsonToken.VALUE_FALSE -> false
            else -> refuse(path, "expected boolean")
        }

    private fun <T> array(
        path: String,
        maximum: Int,
        element: (String) -> T,
    ): List<T> {
        requireToken(parser.currentToken(), JsonToken.START_ARRAY, path)
        val values = mutableListOf<T>()
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (values.size == maximum) refuse(path, "array exceeds $maximum entries")
            values += element("$path[${values.size}]")
        }
        return values
    }

    private fun objectFields(
        path: String,
        allowed: Set<String>,
        field: (String) -> Unit,
    ) {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, path)
        val seen = mutableSetOf<String>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.PROPERTY_NAME, path)
            val name = parser.currentName()
            if (name !in allowed) refuse(path, "unknown field $name")
            if (!seen.add(name)) refuse(path, "duplicate field $name")
            if (parser.nextToken() == null) refuse(path, "field $name has no value")
            field(name)
        }
        if (seen != allowed && path == "$") refuse(path, "source has every required top-level field exactly once")
    }

    private fun <T> requireValue(
        value: T?,
        path: String,
        valid: (T) -> Boolean,
    ): T = value?.takeIf(valid) ?: refuse(path, "required value is missing or invalid")

    private fun requireToken(
        actual: JsonToken?,
        expected: JsonToken,
        path: String,
    ) {
        if (actual != expected) refuse(path, "expected $expected")
    }

    private fun refuse(
        path: String,
        message: String,
    ): Nothing = throw SourceRefusal(path, message)

    private companion object {
        val LIMIT_NAMES: Set<String> =
            setOf(
                "maxCatalogBytes",
                "maxArtifactBytes",
                "maxMessages",
                "maxLocales",
                "maxArguments",
                "maxIdentifierBytes",
                "maxTemplateBytes",
                "maxDescriptionBytes",
                "maxOutputBytes",
                "maxOutputParts",
                "maxNestingDepth",
                "maxLocaleRangeBytes",
                "maxLocaleRanges",
                "maxExplanationBytes",
            )
        val MESSAGE_NAMES: Set<String> =
            setOf(
                "key",
                "contractRevision",
                "source",
                "description",
                "arguments",
                "output",
                "markup",
                "overridePolicy",
                "allowEmpty",
                "public",
                "translations",
            )
        val ARGUMENT_NAMES: Set<String> = setOf("name", "type", "required", "nullable", "enumValues")
        val TRANSLATION_NAMES: Set<String> = setOf("locale", "text", "review", "reviewedSource", "reviewDigest")
    }
}
