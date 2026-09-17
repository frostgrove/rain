package com.gd.rain.i18n

/** A closed presentation layer. Tenancy supplies tenant overlays through its own optional adapter. */
public enum class OverlayLayer {
    APPLICATION,
    TENANT,
    ;

    internal val precedence: Int
        get() =
            when (this) {
                APPLICATION -> 1
                TENANT -> 2
            }
}

/** The layer that produced the template selected for one rendered message. */
public enum class RenderLayer {
    CATALOG,
    APPLICATION,
    TENANT,
}

internal fun OverlayLayer.renderLayer(): RenderLayer =
    when (this) {
        OverlayLayer.APPLICATION -> RenderLayer.APPLICATION
        OverlayLayer.TENANT -> RenderLayer.TENANT
    }

/** Content-addressed identity of an immutable complete-translation overlay. */
public data class CatalogOverlayRef(
    public val layer: OverlayLayer,
    public val revision: String,
    public val digest: Digest,
) {
    init {
        require(revision.isNotEmpty() && revision.length <= 128 && revision.all { it.code in 0x21..0x7E }) {
            "an overlay revision is 1..128 printable ASCII characters"
        }
    }
}

/** One whole-message replacement, carrying every identity it is allowed to replace. */
public data class OverlayTranslationSpec(
    public val key: MessageKey,
    public val locale: LocaleTag,
    public val text: String,
    public val contract: MessageContractRef,
    public val sourceDigest: SourceDigest,
    public val reviewDigest: ReviewDigest,
) {
    init {
        require(!text.hasUnpairedSurrogate()) { "an overlay translation has no unpaired UTF-16 surrogate" }
    }
}

/** A bounded candidate layer for exactly one base catalog release. */
public data class CatalogOverlaySpec(
    public val base: CatalogRef,
    public val layer: OverlayLayer,
    public val revision: String,
    public val entries: List<OverlayTranslationSpec>,
) {
    init {
        require(entries.isNotEmpty()) { "an overlay contains at least one whole-message translation" }
        require(revision.isNotEmpty() && revision.length <= 128 && revision.all { it.code in 0x21..0x7E }) {
            "an overlay revision is 1..128 printable ASCII characters"
        }
    }
}

/** One deterministic overlay validation finding. */
public data class CatalogOverlayProblem(
    public val path: String,
    public val message: String,
) : Comparable<CatalogOverlayProblem> {
    override fun compareTo(other: CatalogOverlayProblem): Int =
        compareValuesBy(this, other, CatalogOverlayProblem::path, CatalogOverlayProblem::message)
}

/** An overlay is either fully compiled for its exact base or unavailable. */
public sealed interface CatalogOverlayCompilation {
    public data class Compiled(
        public val overlay: CatalogOverlay,
    ) : CatalogOverlayCompilation

    public data class Refused(
        public val problems: List<CatalogOverlayProblem>,
    ) : CatalogOverlayCompilation {
        init {
            require(problems.isNotEmpty()) { "a refused overlay compilation names at least one problem" }
        }
    }
}

/** A fully checked overlay; it can replace only templates, never a catalog's contracts or policy. */
public class CatalogOverlay internal constructor(
    public val base: CatalogRef,
    public val reference: CatalogOverlayRef,
    internal val translations: Map<OverlayKey, OverlayTranslation>,
) {
    public val layer: OverlayLayer get() = reference.layer

    internal fun translation(
        key: MessageKey,
        locale: LocaleTag,
    ): OverlayTranslation? = translations[OverlayKey(key, locale)]
}

internal data class OverlayKey(
    val key: MessageKey,
    val locale: LocaleTag,
) : Comparable<OverlayKey> {
    override fun compareTo(other: OverlayKey): Int = compareValuesBy(this, other, { it.key }, { it.locale })
}

internal data class OverlayTranslation(
    val template: Mf2Template,
)

/** Compiles all overlay entries before exposing any of them to a view. */
public object CatalogOverlayCompiler {
    public fun compile(
        snapshot: CatalogSnapshot,
        source: CatalogOverlaySpec,
    ): CatalogOverlayCompilation {
        val problems = mutableListOf<CatalogOverlayProblem>()
        if (source.entries.size > snapshot.limits.maxMessages) {
            return CatalogOverlayCompilation.Refused(
                listOf(CatalogOverlayProblem("entries", "exceeds the base catalog message limit")),
            )
        }
        if (source.base != snapshot.reference) {
            problems += CatalogOverlayProblem("base", "does not identify this exact catalog snapshot")
        }
        val entries = linkedMapOf<OverlayKey, OverlayTranslation>()
        source.entries.forEachIndexed { index, entry ->
            compileEntry(snapshot, source, entry, index, problems)?.let { compiled ->
                val key = OverlayKey(entry.key, entry.locale)
                if (entries.putIfAbsent(key, compiled) != null) {
                    problems += CatalogOverlayProblem("entries[$index]", "duplicates the key and locale of another overlay entry")
                }
            }
        }
        if (problems.isNotEmpty()) return CatalogOverlayCompilation.Refused(problems.sorted())

        val digest = Digest.sha256(canonical(source))
        return CatalogOverlayCompilation.Compiled(
            CatalogOverlay(
                source.base,
                CatalogOverlayRef(source.layer, source.revision, digest),
                entries.toSortedMap(),
            ),
        )
    }

    private fun compileEntry(
        snapshot: CatalogSnapshot,
        source: CatalogOverlaySpec,
        entry: OverlayTranslationSpec,
        index: Int,
        problems: MutableList<CatalogOverlayProblem>,
    ): OverlayTranslation? {
        val path = "entries[$index]"
        val record = snapshot.message(entry.key)
        if (record == null) {
            problems += CatalogOverlayProblem("$path.key", "is not declared by the base catalog")
            return null
        }
        if (!permits(record.spec.overridePolicy, source.layer)) {
            problems += CatalogOverlayProblem("$path.key", "does not permit this overlay layer")
        }
        if (entry.locale !in snapshot.localeResolver.policy.supported || entry.locale == snapshot.sourceLocale) {
            problems += CatalogOverlayProblem("$path.locale", "is not a non-source locale supported by the base catalog")
        }
        if (entry.contract != record.contract) {
            problems += CatalogOverlayProblem("$path.contract", "does not match the base message contract")
        }
        if (entry.sourceDigest != record.sourceDigest) {
            problems += CatalogOverlayProblem("$path.sourceDigest", "does not match the base source wording")
        }
        if (entry.reviewDigest != CatalogDigests.review(entry.sourceDigest, entry.locale, entry.text)) {
            problems += CatalogOverlayProblem("$path.reviewDigest", "does not match the overlay text and source")
        }
        if (entry.text.utf8Size() > snapshot.limits.maxTemplateBytes) {
            problems += CatalogOverlayProblem("$path.text", "exceeds the base catalog template limit")
        }
        if (entry.text.isEmpty() && !record.spec.allowEmpty) {
            problems += CatalogOverlayProblem("$path.text", "is empty but the base message does not allow an empty translation")
        }
        val template =
            when (
                val compilation =
                    Mf2Compiler.compile(
                        entry.text,
                        record.spec.arguments,
                        record.spec.output,
                        record.spec.markup,
                        snapshot.limits,
                    )
            ) {
                is TemplateCompilation.Compiled -> {
                    compilation.template
                }

                is TemplateCompilation.Refused -> {
                    compilation.problems.forEach { problem ->
                        problems +=
                            CatalogOverlayProblem("$path.text@${problem.offset}", problem.message)
                    }
                    null
                }
            }
        return template?.let(::OverlayTranslation)
    }

    private fun permits(
        policy: OverridePolicy,
        layer: OverlayLayer,
    ): Boolean =
        when (layer) {
            OverlayLayer.APPLICATION -> policy == OverridePolicy.APPLICATION
            OverlayLayer.TENANT -> policy == OverridePolicy.TENANT
        }

    private fun canonical(source: CatalogOverlaySpec): ByteArray {
        val form = CanonicalForm()
        form.text(source.base.revision)
        form.text(source.base.digest.hex)
        form.text(source.layer.name)
        form.text(source.revision)
        source.entries
            .sortedWith(compareBy(OverlayTranslationSpec::key, OverlayTranslationSpec::locale))
            .forEach { entry ->
                form.text(entry.key.value)
                form.text(entry.locale.value)
                form.number(entry.contract.revision)
                form.text(entry.contract.digest.value.hex)
                form.text(entry.sourceDigest.value.hex)
                form.text(entry.reviewDigest.value.hex)
                form.text(entry.text)
            }
        return form.bytes()
    }
}
