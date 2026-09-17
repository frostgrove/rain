package com.gd.rain.i18n

import tools.jackson.core.JsonEncoding
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.ObjectWriteContext
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.json.JsonFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

/** A compiled catalog artifact finding; no rejected bytes become a visible snapshot. */
public data class CatalogArtifactProblem(
    public val path: String,
    public val message: String,
)

/** An artifact loader either returns one verified immutable snapshot or no snapshot. */
public sealed interface CatalogArtifactDecoding {
    public data class Decoded(
        public val snapshot: CatalogSnapshot,
        public val artifactDigest: Digest,
    ) : CatalogArtifactDecoding

    public data class Refused(
        public val problems: List<CatalogArtifactProblem>,
    ) : CatalogArtifactDecoding {
        init {
            require(problems.isNotEmpty()) { "a refused artifact decode names a problem" }
        }
    }
}

/**
 * Offline canonical `rain.i18n.catalog/v1` encoder/loader.
 *
 * The artifact embeds one already canonical source payload rather than accepting a generic object
 * graph. That makes its compiler input reproducible, lets the loader verify exact bytes, and keeps
 * the stable runtime format separate from authoring files and transport envelopes.
 */
public class CatalogArtifactCodec(
    private val localLimits: I18nLimits = I18nLimits(),
    private val runtimeIdentity: CatalogRuntimeIdentity? = null,
) {
    /** Compiles [source] before emitting a deterministic artifact; invalid source is never emitted. */
    public fun encode(source: CatalogSpec): ByteArray {
        val snapshot =
            when (val compilation = CatalogCompiler.compile(source)) {
                is CatalogCompilation.Compiled -> compilation.snapshot

                is CatalogCompilation.Refused -> throw IllegalArgumentException(
                    compilation.problems.joinToString { "${it.path}: ${it.message}" },
                )
            }
        require(localAllows(source.limits)) { "artifact limits widen local operator limits" }
        require(runtimeIdentity?.accepts(source.identity) != false) { "artifact identity differs from configured runtime" }
        val canonicalSource = CatalogSourceCodec(localLimits).encode(source)
        val output = ByteArrayOutputStream()
        JsonFactory().createGenerator(ObjectWriteContext.empty(), output, JsonEncoding.UTF8).use { generator ->
            generator.writeStartObject()
            generator.writeStringProperty("schema", SCHEMA)
            generator.writeStringProperty("revision", source.identity.revision)
            generator.writeStringProperty("profile", source.identity.profile)
            generator.writeStringProperty("engine", source.identity.engine)
            generator.writeStringProperty("icuClDrTzdbIdentity", source.identity.icuClDrTzdbIdentity)
            generator.writeStringProperty("snapshotDigest", snapshot.digest.hex)
            generator.writeStringProperty("sourceBase64", Base64.getEncoder().encodeToString(canonicalSource))
            generator.writeEndObject()
        }
        return output.toByteArray().also { bytes ->
            require(bytes.size <= localLimits.maxArtifactBytes) { "artifact exceeds ${localLimits.maxArtifactBytes} bytes" }
        }
    }

    /** Verifies strict syntax, canonical embedded source, identity, local ceilings and snapshot hash. */
    public fun decode(bytes: ByteArray): CatalogArtifactDecoding {
        if (bytes.size > localLimits.maxArtifactBytes) {
            return CatalogArtifactDecoding.Refused(
                listOf(CatalogArtifactProblem("$", "artifact exceeds ${localLimits.maxArtifactBytes} bytes")),
            )
        }
        return try {
            ArtifactReader(bytes, localLimits, runtimeIdentity).read()
        } catch (failure: ArtifactRefusal) {
            CatalogArtifactDecoding.Refused(listOf(CatalogArtifactProblem(failure.path, failure.message)))
        } catch (_: Exception) {
            CatalogArtifactDecoding.Refused(listOf(CatalogArtifactProblem("$", "malformed catalog artifact")))
        }
    }

    private fun localAllows(declared: I18nLimits): Boolean =
        declared.maxCatalogBytes <= localLimits.maxCatalogBytes &&
            declared.maxArtifactBytes <= localLimits.maxArtifactBytes &&
            declared.maxMessages <= localLimits.maxMessages &&
            declared.maxLocales <= localLimits.maxLocales &&
            declared.maxArguments <= localLimits.maxArguments &&
            declared.maxIdentifierBytes <= localLimits.maxIdentifierBytes &&
            declared.maxTemplateBytes <= localLimits.maxTemplateBytes &&
            declared.maxDescriptionBytes <= localLimits.maxDescriptionBytes &&
            declared.maxOutputBytes <= localLimits.maxOutputBytes &&
            declared.maxOutputParts <= localLimits.maxOutputParts &&
            declared.maxNestingDepth <= localLimits.maxNestingDepth &&
            declared.maxLocaleRangeBytes <= localLimits.maxLocaleRangeBytes &&
            declared.maxLocaleRanges <= localLimits.maxLocaleRanges &&
            declared.maxExplanationBytes <= localLimits.maxExplanationBytes

    private companion object {
        const val SCHEMA: String = "rain.i18n.catalog/v1"
    }
}

private class ArtifactRefusal(
    val path: String,
    override val message: String,
) : IllegalArgumentException(message)

private class ArtifactReader(
    bytes: ByteArray,
    private val limits: I18nLimits,
    private val runtimeIdentity: CatalogRuntimeIdentity?,
) {
    private val artifactBytes: ByteArray = bytes.copyOf()

    private val parser: JsonParser =
        JsonFactory
            .builder()
            .streamReadConstraints(
                StreamReadConstraints
                    .builder()
                    .maxDocumentLength(limits.maxArtifactBytes.toLong())
                    .maxNestingDepth(limits.maxNestingDepth)
                    .maxTokenCount(32)
                    .maxStringLength(limits.maxArtifactBytes)
                    .maxNameLength(limits.maxIdentifierBytes)
                    .build(),
            ).build()
            .createParser(ObjectReadContext.empty(), artifactBytes, 0, artifactBytes.size)

    fun read(): CatalogArtifactDecoding =
        parser.use {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "$")
            var schema: String? = null
            var revision: String? = null
            var profile: String? = null
            var engine: String? = null
            var dataIdentity: String? = null
            var snapshotDigest: Digest? = null
            var sourceBase64: String? = null
            val expected = setOf("schema", "revision", "profile", "engine", "icuClDrTzdbIdentity", "snapshotDigest", "sourceBase64")
            objectFields("$", expected) { name ->
                when (name) {
                    "schema" -> schema = string("$.schema")
                    "revision" -> revision = string("$.revision")
                    "profile" -> profile = string("$.profile")
                    "engine" -> engine = string("$.engine")
                    "icuClDrTzdbIdentity" -> dataIdentity = string("$.icuClDrTzdbIdentity")
                    "snapshotDigest" -> snapshotDigest = digest("$.snapshotDigest")
                    "sourceBase64" -> sourceBase64 = string("$.sourceBase64")
                }
            }
            if (parser.nextToken() != null) refuse("$", "trailing JSON value")
            if (schema != "rain.i18n.catalog/v1") refuse("$.schema", "unsupported artifact schema")
            val sourceBytes =
                try {
                    Base64.getDecoder().decode(requireValue(sourceBase64, "$.sourceBase64"))
                } catch (_: IllegalArgumentException) {
                    refuse("$.sourceBase64", "invalid base64 source payload")
                }
            if (sourceBytes.size > limits.maxCatalogBytes) refuse("$.sourceBase64", "embedded source exceeds local catalog limit")
            val source =
                when (val decoded = CatalogSourceCodec(limits).decode(sourceBytes)) {
                    is CatalogSourceDecoding.Decoded -> {
                        decoded.source
                    }

                    is CatalogSourceDecoding.Refused -> {
                        val first = decoded.problems.first()
                        refuse("$.sourceBase64.${first.path}", first.message)
                    }
                }
            if (!CatalogSourceCodec(
                    limits,
                ).encode(source).contentEquals(sourceBytes)
            ) {
                refuse("$.sourceBase64", "embedded source is not canonical")
            }
            if (!localAllows(source.limits)) refuse("$.limits", "artifact limits widen local operator limits")
            val identity = source.identity
            if (identity.revision != requireValue(revision, "$.revision") ||
                identity.profile != requireValue(profile, "$.profile") ||
                identity.engine != requireValue(engine, "$.engine") ||
                identity.icuClDrTzdbIdentity != requireValue(dataIdentity, "$.icuClDrTzdbIdentity")
            ) {
                refuse("$", "artifact header identity differs from canonical source")
            }
            if (runtimeIdentity?.accepts(identity) == false) {
                refuse("$.identity", "artifact identity differs from configured runtime")
            }
            val snapshot = (CatalogCompiler.compile(source) as CatalogCompilation.Compiled).snapshot
            if (snapshot.digest !=
                requireValue(snapshotDigest, "$.snapshotDigest")
            ) {
                refuse("$.snapshotDigest", "does not match compiled catalog")
            }
            return CatalogArtifactDecoding.Decoded(snapshot, Digest.sha256(artifactBytes))
        }

    private fun localAllows(declared: I18nLimits): Boolean =
        declared.maxCatalogBytes <= limits.maxCatalogBytes && declared.maxArtifactBytes <= limits.maxArtifactBytes &&
            declared.maxMessages <= limits.maxMessages && declared.maxLocales <= limits.maxLocales &&
            declared.maxArguments <= limits.maxArguments &&
            declared.maxIdentifierBytes <= limits.maxIdentifierBytes && declared.maxTemplateBytes <= limits.maxTemplateBytes &&
            declared.maxDescriptionBytes <= limits.maxDescriptionBytes && declared.maxOutputBytes <= limits.maxOutputBytes &&
            declared.maxOutputParts <= limits.maxOutputParts && declared.maxNestingDepth <= limits.maxNestingDepth &&
            declared.maxLocaleRangeBytes <= limits.maxLocaleRangeBytes && declared.maxLocaleRanges <= limits.maxLocaleRanges &&
            declared.maxExplanationBytes <= limits.maxExplanationBytes

    private fun digest(path: String): Digest =
        try {
            Digest.parse(string(path))
        } catch (failure: IllegalArgumentException) {
            throw ArtifactRefusal(path, failure.message ?: "invalid digest")
        }

    private fun string(path: String): String {
        requireToken(parser.currentToken(), JsonToken.VALUE_STRING, path)
        return parser.string
    }

    private fun objectFields(
        path: String,
        expected: Set<String>,
        field: (String) -> Unit,
    ) {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, path)
        val seen = mutableSetOf<String>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.PROPERTY_NAME, path)
            val name = parser.currentName()
            if (name !in expected) refuse(path, "unknown field $name")
            if (!seen.add(name)) refuse(path, "duplicate field $name")
            if (parser.nextToken() == null) refuse(path, "field $name has no value")
            field(name)
        }
        if (seen != expected) refuse(path, "artifact has every required field exactly once")
    }

    private fun requireValue(
        value: String?,
        path: String,
    ): String = value ?: refuse(path, "required value is missing")

    private fun <T> requireValue(
        value: T?,
        path: String,
    ): T = value ?: refuse(path, "required value is missing")

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
    ): Nothing = throw ArtifactRefusal(path, message)
}
