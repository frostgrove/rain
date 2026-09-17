package com.gd.rain.i18n

import java.time.ZoneId

/** The fixed set of values an argument declaration may accept. */
public enum class ArgumentType {
    TEXT,
    BOOLEAN,
    INTEGER,
    UNSIGNED_INTEGER,
    BIG_INTEGER,
    DECIMAL,
    MONEY,
    DATE,
    INSTANT,
    ENUM,
}

/** One named and versioned argument in a message contract. */
public data class ArgumentSpec(
    public val name: String,
    public val type: ArgumentType,
    public val required: Boolean = true,
    public val nullable: Boolean = false,
    public val enumValues: Set<String> = emptySet(),
) {
    init {
        require(MessageKey.IDENTIFIER.matches(name)) { "an argument name matches ${MessageKey.IDENTIFIER.pattern}" }
        require(type == ArgumentType.ENUM || enumValues.isEmpty()) { "only an enum argument declares enum values" }
        require(type != ArgumentType.ENUM || enumValues.isNotEmpty()) { "an enum argument declares at least one value" }
        require(enumValues.all(MessageKey.IDENTIFIER::matches)) { "an enum value matches ${MessageKey.IDENTIFIER.pattern}" }
    }
}

/** Whether a rendered message is text-only or a validated structural rich-part tree. */
public enum class OutputKind {
    PLAIN,
    RICH,
}

/** Which complete translation layer may replace a base message translation. */
public enum class OverridePolicy {
    NONE,
    APPLICATION,
    TENANT,
}

/** A translation's review state. Only [APPROVED] becomes part of a runtime snapshot. */
public enum class TranslationReview {
    APPROVED,
    REQUIRED,
    REJECTED,
}

/** A translation as it appears in source authoring data. */
public data class TranslationSpec(
    public val locale: LocaleTag,
    public val text: String,
    public val review: TranslationReview,
    public val reviewedSource: SourceDigest? = null,
    public val reviewDigest: ReviewDigest? = null,
) {
    init {
        require(!text.hasUnpairedSurrogate()) { "a translation has an unpaired UTF-16 surrogate" }
        val stamped = reviewedSource != null || reviewDigest != null
        require((review == TranslationReview.APPROVED) == stamped) {
            "only an approved translation carries source and review stamps"
        }
        require(reviewedSource == null || reviewDigest != null) { "an approved translation carries both stamps" }
    }
}

/** A message declaration before it is compiled into a snapshot. */
public data class MessageSpec(
    public val key: MessageKey,
    public val contractRevision: Int,
    public val source: String,
    public val description: String,
    public val arguments: List<ArgumentSpec> = emptyList(),
    public val output: OutputKind = OutputKind.PLAIN,
    public val markup: Set<String> = emptySet(),
    public val overridePolicy: OverridePolicy = OverridePolicy.NONE,
    public val allowEmpty: Boolean = false,
    public val public: Boolean = false,
    public val translations: List<TranslationSpec> = emptyList(),
) {
    init {
        require(contractRevision >= 1) { "a message contract revision is at least one" }
        require(!source.hasUnpairedSurrogate() && !description.hasUnpairedSurrogate()) {
            "message source and description have no unpaired UTF-16 surrogate"
        }
        require(markup.all(MessageKey.IDENTIFIER::matches)) { "a markup name matches ${MessageKey.IDENTIFIER.pattern}" }
        require(output == OutputKind.RICH || markup.isEmpty()) { "only a rich message declares markup" }
    }
}

/** Versioned identity of a catalog artifact and the deterministic engine/data inputs it requires. */
public data class CatalogIdentity(
    public val revision: String,
    public val profile: String = "rain-mf2/v1",
    public val engine: String = "rain-i18n/1",
    public val icuClDrTzdbIdentity: String,
) {
    init {
        require(validIdentity(revision)) { "a catalog revision is 1..128 printable ASCII characters" }
        require(validIdentity(profile)) { "a catalog profile is 1..128 printable ASCII characters" }
        require(validIdentity(engine)) { "a catalog engine is 1..128 printable ASCII characters" }
        require(validIdentity(icuClDrTzdbIdentity)) { "an ICU/CLDR/tzdb identity is 1..128 printable ASCII characters" }
    }

    private companion object {
        fun validIdentity(value: String): Boolean =
            value.isNotEmpty() && value.length <= 128 && value.all { character -> character.code in 0x21..0x7E }
    }
}

/** An all-or-nothing source catalog. There is no classpath discovery or mutable registration. */
public data class CatalogSpec(
    public val identity: CatalogIdentity,
    public val sourceLocale: LocaleTag,
    public val localePolicy: LocalePolicy,
    public val requiredLocales: Set<LocaleTag> = emptySet(),
    public val defaultZone: ZoneId,
    public val limits: I18nLimits = I18nLimits(),
    public val messages: List<MessageSpec>,
) {
    init {
        require(sourceLocale in localePolicy.supported) { "the source locale is supported" }
        require(requiredLocales.all { it in localePolicy.supported }) { "a required locale is supported" }
        require(messages.isNotEmpty()) { "a catalog declares at least one message" }
    }
}

/** One deterministic compiler finding. Paths point into canonical source, not an implementation stack trace. */
public data class CatalogProblem(
    public val path: String,
    public val message: String,
) : Comparable<CatalogProblem> {
    override fun compareTo(other: CatalogProblem): Int = compareValuesBy(this, other, CatalogProblem::path, CatalogProblem::message)
}

/** A catalog compilation either produces one complete immutable snapshot or every structural problem. */
public sealed interface CatalogCompilation {
    public data class Compiled(
        public val snapshot: CatalogSnapshot,
    ) : CatalogCompilation

    public data class Refused(
        public val problems: List<CatalogProblem>,
    ) : CatalogCompilation {
        init {
            require(problems.isNotEmpty()) { "a refused compilation names at least one problem" }
        }
    }
}

/** Immutable compiled catalog data. Formatting and template compilation are attached by later kernel stages. */
public class CatalogSnapshot internal constructor(
    public val identity: CatalogIdentity,
    public val digest: Digest,
    public val sourceLocale: LocaleTag,
    public val localeResolver: LocaleResolver,
    public val requiredLocales: Set<LocaleTag>,
    public val defaultZone: ZoneId,
    public val limits: I18nLimits,
    /** A conservative deterministic footprint used by in-process retention limits. */
    public val estimatedBytes: Long,
    private val records: Map<MessageKey, MessageRecord>,
) {
    public val reference: CatalogRef get() = CatalogRef(identity.revision, digest)

    public val keys: Set<MessageKey> get() = records.keys

    public fun message(key: MessageKey): MessageRecord? = records[key]

    public fun contract(key: MessageKey): MessageContractRef? = records[key]?.contract

    /** Resolves a locale through this exact snapshot's immutable policy and ownership token. */
    public fun resolve(
        choices: List<LocaleChoice>,
        observer: I18nObserver = I18nObserver.NONE,
    ): LocaleResolution = localeResolver.resolve(choices).also { resolution -> observer.emit(resolution.observation()) }

    /** Resolves protocol language ranges through this exact snapshot's immutable policy. */
    public fun resolveAcceptLanguage(
        fields: List<String>,
        observer: I18nObserver = I18nObserver.NONE,
    ): LocaleResolution = localeResolver.resolveAcceptLanguage(fields).also { resolution -> observer.emit(resolution.observation()) }
}

private fun LocaleResolution.observation(): I18nObservation =
    when (this) {
        is LocaleResolution.Resolved -> {
            I18nObservation(I18nObservationOperation.LOCALE_RESOLUTION, I18nObservationOutcome.RESOLVED, reason)
        }

        is LocaleResolution.Refused -> {
            I18nObservation(I18nObservationOperation.LOCALE_RESOLUTION, I18nObservationOutcome.REFUSED, reason)
        }
    }

/** A message after all its source/review/contract identities have been verified. */
public data class MessageRecord internal constructor(
    public val spec: MessageSpec,
    public val contract: MessageContractRef,
    public val sourceDigest: SourceDigest,
    internal val sourceTemplate: Mf2Template,
    public val translations: Map<LocaleTag, ReviewedTranslation>,
)

/** An approved translation whose stamps matched the exact source wording at compilation time. */
public data class ReviewedTranslation internal constructor(
    public val text: String,
    public val sourceDigest: SourceDigest,
    public val reviewDigest: ReviewDigest,
    internal val template: Mf2Template,
)

/** Compiles source declarations into an immutable snapshot, collecting all deterministic structural errors. */
public object CatalogCompiler {
    public fun compile(source: CatalogSpec): CatalogCompilation {
        val problems = mutableListOf<CatalogProblem>()
        val limits = source.limits
        val declaredBytes = declaredInputBytes(source)
        if (declaredBytes > limits.maxCatalogBytes) {
            problems += CatalogProblem("catalog", "declares more than " + limits.maxCatalogBytes + " UTF-8 bytes")
        }
        if (source.messages.size > limits.maxMessages) {
            problems += CatalogProblem("messages", "contains ${source.messages.size} messages, above ${limits.maxMessages}")
        }
        if (source.identity.profile != "rain-mf2/v1") {
            problems += CatalogProblem("identity.profile", "is ${source.identity.profile}; only rain-mf2/v1 is supported")
        }
        if (source.defaultZone.id.utf8Size() > limits.maxIdentifierBytes) {
            problems += CatalogProblem("defaultZone", "exceeds ${limits.maxIdentifierBytes} UTF-8 bytes")
        }

        val records = linkedMapOf<MessageKey, MessageRecord>()
        source.messages.forEachIndexed { index, message ->
            compileMessage(source, message, index, problems)?.let { record ->
                if (records.putIfAbsent(message.key, record) != null) {
                    problems += CatalogProblem("messages[$index].key", "duplicates ${message.key}")
                }
            }
        }
        if (problems.isNotEmpty()) return CatalogCompilation.Refused(problems.sorted())

        val canonical = catalogCanonical(source, records)
        val digest = Digest.sha256(canonical)
        return CatalogCompilation.Compiled(
            CatalogSnapshot(
                identity = source.identity,
                digest = digest,
                sourceLocale = source.sourceLocale,
                localeResolver = LocaleResolver(source.localePolicy, limits),
                requiredLocales = source.requiredLocales.toSortedSet(),
                defaultZone = source.defaultZone,
                limits = limits,
                estimatedBytes = Math.addExact(declaredBytes, canonical.size.toLong()),
                records = records.toSortedMap(),
            ),
        )
    }

    private fun compileMessage(
        source: CatalogSpec,
        message: MessageSpec,
        index: Int,
        problems: MutableList<CatalogProblem>,
    ): MessageRecord? {
        val prefix = "messages[$index]"
        val limits = source.limits
        if (message.key.value.utf8Size() > limits.maxIdentifierBytes) {
            problems += CatalogProblem("$prefix.key", "exceeds ${limits.maxIdentifierBytes} UTF-8 bytes")
        }
        if (message.source.utf8Size() > limits.maxTemplateBytes) {
            problems += CatalogProblem("$prefix.source", "exceeds ${limits.maxTemplateBytes} UTF-8 bytes")
        }
        if (message.description.utf8Size() > limits.maxDescriptionBytes) {
            problems += CatalogProblem("$prefix.description", "exceeds ${limits.maxDescriptionBytes} UTF-8 bytes")
        }
        if (message.source.isEmpty() && !message.allowEmpty) {
            problems += CatalogProblem("$prefix.source", "is empty but allowEmpty is false")
        }
        if (message.arguments.size > limits.maxArguments) {
            problems += CatalogProblem("$prefix.arguments", "contains more than ${limits.maxArguments} arguments")
        }
        message.arguments.groupBy(ArgumentSpec::name).filterValues { it.size > 1 }.keys.sorted().forEach { name ->
            problems += CatalogProblem("$prefix.arguments", "declares argument $name more than once")
        }
        message.markup.forEach { tag ->
            if (tag.utf8Size() > limits.maxIdentifierBytes) {
                problems += CatalogProblem("$prefix.markup", "tag $tag exceeds ${limits.maxIdentifierBytes} UTF-8 bytes")
            }
        }

        val sourceDigest = CatalogDigests.source(source.identity.profile, source.sourceLocale, message.source, message.description)
        val contract = MessageContractRef(message.key, message.contractRevision, CatalogDigests.contract(message))
        val sourceTemplate = compileTemplate(message.source, message, "$prefix.source", source.limits, problems)
        val translations = linkedMapOf<LocaleTag, ReviewedTranslation>()
        message.translations.forEachIndexed { translationIndex, translation ->
            val translationPath = "$prefix.translations[$translationIndex]"
            if (translation.locale !in source.localePolicy.supported || translation.locale == source.sourceLocale) {
                problems += CatalogProblem("$translationPath.locale", "is not a non-source supported locale")
            }
            if (translation.text.utf8Size() > limits.maxTemplateBytes) {
                problems += CatalogProblem("$translationPath.text", "exceeds ${limits.maxTemplateBytes} UTF-8 bytes")
            }
            if (translation.text.isEmpty() && !message.allowEmpty) {
                problems += CatalogProblem("$translationPath.text", "is empty but allowEmpty is false")
            }
            if (translation.review == TranslationReview.APPROVED) {
                val reviewedSource = requireNotNull(translation.reviewedSource)
                val reviewDigest = requireNotNull(translation.reviewDigest)
                if (reviewedSource != sourceDigest) {
                    problems += CatalogProblem("$translationPath.reviewedSource", "does not match current source wording")
                }
                val expected = CatalogDigests.review(reviewedSource, translation.locale, translation.text)
                if (reviewDigest != expected) {
                    problems += CatalogProblem("$translationPath.reviewDigest", "does not match translation text and source")
                }
                val template = compileTemplate(translation.text, message, "$translationPath.text", source.limits, problems)
                val record = template?.let { ReviewedTranslation(translation.text, reviewedSource, reviewDigest, it) }
                if (record != null && translations.putIfAbsent(translation.locale, record) != null) {
                    problems += CatalogProblem("$translationPath.locale", "duplicates ${translation.locale}")
                }
            } else if (translations.containsKey(translation.locale)) {
                problems += CatalogProblem("$translationPath.locale", "duplicates ${translation.locale}")
            }
        }
        source.requiredLocales.filter { it != source.sourceLocale && it !in translations }.sorted().forEach { locale ->
            problems += CatalogProblem("$prefix.translations", "has no approved translation for required locale $locale")
        }
        return sourceTemplate?.let { MessageRecord(message, contract, sourceDigest, it, translations.toSortedMap()) }
    }

    private fun compileTemplate(
        text: String,
        message: MessageSpec,
        path: String,
        limits: I18nLimits,
        problems: MutableList<CatalogProblem>,
    ): Mf2Template? =
        when (val compilation = Mf2Compiler.compile(text, message.arguments, message.output, message.markup, limits)) {
            is TemplateCompilation.Compiled -> {
                compilation.template
            }

            is TemplateCompilation.Refused -> {
                compilation.problems.forEach { problem -> problems += CatalogProblem("$path@${problem.offset}", problem.message) }
                null
            }
        }

    private fun catalogCanonical(
        source: CatalogSpec,
        records: Map<MessageKey, MessageRecord>,
    ): ByteArray {
        val canonical = CanonicalForm()
        canonical.text(source.identity.revision)
        canonical.text(source.identity.profile)
        canonical.text(source.identity.engine)
        canonical.text(source.identity.icuClDrTzdbIdentity)
        canonical.text(source.sourceLocale.value)
        source.localePolicy.supported
            .sorted()
            .forEach { locale -> canonical.text(locale.value) }
        canonical.text(source.localePolicy.defaultLocale.value)
        source.localePolicy.parents.toSortedMap().forEach { (locale, parent) ->
            canonical.text(locale.value)
            canonical.text(parent.value)
        }
        canonical.text(source.localePolicy.matchMode.name)
        canonical.text(source.localePolicy.defaultOnMiss.name)
        source.requiredLocales.sorted().forEach { locale -> canonical.text(locale.value) }
        canonical.text(source.defaultZone.id)
        appendLimits(canonical, source.limits)
        records.toSortedMap().forEach { (key, record) ->
            canonical.text(key.value)
            canonical.text(record.contract.digest.value.hex)
            canonical.text(record.sourceDigest.value.hex)
            record.translations.forEach { (locale, translation) ->
                canonical.text(locale.value)
                canonical.text(translation.reviewDigest.value.hex)
            }
        }
        return canonical.bytes()
    }

    private fun appendLimits(
        canonical: CanonicalForm,
        limits: I18nLimits,
    ) {
        canonical.number(limits.maxCatalogBytes)
        canonical.number(limits.maxArtifactBytes)
        canonical.number(limits.maxMessages)
        canonical.number(limits.maxLocales)
        canonical.number(limits.maxArguments)
        canonical.number(limits.maxIdentifierBytes)
        canonical.number(limits.maxTemplateBytes)
        canonical.number(limits.maxDescriptionBytes)
        canonical.number(limits.maxOutputBytes)
        canonical.number(limits.maxOutputParts)
        canonical.number(limits.maxNestingDepth)
        canonical.number(limits.maxLocaleRangeBytes)
        canonical.number(limits.maxLocaleRanges)
        canonical.number(limits.maxExplanationBytes)
    }

    private fun declaredInputBytes(source: CatalogSpec): Long {
        var bytes = 0L

        fun add(value: String) {
            bytes = Math.addExact(bytes, value.utf8Size().toLong())
        }
        add(source.identity.revision)
        add(source.identity.profile)
        add(source.identity.engine)
        add(source.identity.icuClDrTzdbIdentity)
        add(source.sourceLocale.value)
        source.localePolicy.supported.forEach { locale -> add(locale.value) }
        add(source.localePolicy.defaultLocale.value)
        source.localePolicy.parents.forEach { (locale, parent) ->
            add(locale.value)
            add(parent.value)
        }
        source.requiredLocales.forEach { locale -> add(locale.value) }
        add(source.defaultZone.id)
        source.messages.forEach { message ->
            add(message.key.value)
            add(message.source)
            add(message.description)
            message.arguments.forEach { argument ->
                add(argument.name)
                argument.enumValues.forEach(::add)
            }
            message.markup.forEach(::add)
            message.translations.forEach { translation ->
                add(translation.locale.value)
                add(translation.text)
            }
        }
        return bytes
    }
}

/** Content-addressed immutable catalog identity used by controllers, pins and durable delivery. */
public data class CatalogRef(
    public val revision: String,
    public val digest: Digest,
) {
    init {
        require(revision.isNotEmpty() && revision.length <= 128) { "a catalog reference revision is 1..128 characters" }
    }
}

/** Canonical identity derivation shared by compiler, authoring tool and generated bindings. */
public object CatalogDigests {
    public fun contract(spec: MessageSpec): ContractDigest {
        val form = CanonicalForm()
        form.text(spec.key.value)
        form.number(spec.contractRevision)
        form.text(spec.output.name)
        form.text(spec.overridePolicy.name)
        form.boolean(spec.allowEmpty)
        form.boolean(spec.public)
        spec.markup.sorted().forEach(form::text)
        spec.arguments.sortedBy(ArgumentSpec::name).forEach { argument ->
            form.text(argument.name)
            form.text(argument.type.name)
            form.boolean(argument.required)
            form.boolean(argument.nullable)
            argument.enumValues.sorted().forEach(form::text)
        }
        return ContractDigest(Digest.sha256(form.bytes()))
    }

    public fun source(
        profile: String,
        sourceLocale: LocaleTag,
        source: String,
        description: String,
    ): SourceDigest {
        val form = CanonicalForm()
        form.text(profile)
        form.text(sourceLocale.value)
        form.text(source)
        form.text(description)
        return SourceDigest(Digest.sha256(form.bytes()))
    }

    public fun review(
        sourceDigest: SourceDigest,
        locale: LocaleTag,
        text: String,
    ): ReviewDigest {
        val form = CanonicalForm()
        form.text(sourceDigest.value.hex)
        form.text(locale.value)
        form.text(text)
        return ReviewDigest(Digest.sha256(form.bytes()))
    }
}

/** Length-prefixed UTF-8 fields prevent ambiguity without relying on a locale-sensitive formatter. */
internal class CanonicalForm {
    private val buffer: StringBuilder = StringBuilder()

    fun text(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        buffer.append(encoded.size).append(':').append(value)
    }

    fun number(value: Int) {
        text(value.toString())
    }

    fun boolean(value: Boolean) {
        text(if (value) "1" else "0")
    }

    fun bytes(): ByteArray = buffer.toString().toByteArray(Charsets.UTF_8)
}
