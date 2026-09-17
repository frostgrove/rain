package com.gd.rain.i18n.integration

import com.gd.rain.i18n.ArgumentSpec
import com.gd.rain.i18n.CatalogOverlayRef
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageContractRef
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.OutputKind
import com.gd.rain.i18n.SourceDigest
import java.util.UUID

/**
 * A vendor-neutral TMS boundary. It exchanges only declared source contracts and unreviewed
 * translation candidates; compiling, review stamping and activation remain local kernel work.
 */
public interface TranslationManagementConnector {
    public fun upload(request: TranslationUpload): TranslationUploadOutcome

    public fun download(request: TranslationDownload): TranslationDownloadOutcome
}

/** A complete bounded batch of source contracts made available to a TMS. */
public data class TranslationUpload(
    public val catalog: CatalogRef,
    public val entries: List<TranslationSourceEntry>,
) {
    init {
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) { "a translation upload has 1..$MAX_ENTRIES entries" }
        require(entries.map(TranslationSourceEntry::key).toSet().size == entries.size) {
            "a translation upload has unique message keys"
        }
    }

    public companion object {
        public const val MAX_ENTRIES: Int = 10_000
    }
}

/** Source wording and contract provenance that a translator needs, without a runtime template. */
public data class TranslationSourceEntry(
    public val key: MessageKey,
    public val contract: MessageContractRef,
    public val sourceDigest: SourceDigest,
    public val source: String,
    public val description: String,
) {
    init {
        require(contract.key == key) { "a translation source entry contract belongs to its key" }
        require(source.utf8Size() <= MAX_SOURCE_BYTES) { "a translation source is at most $MAX_SOURCE_BYTES UTF-8 bytes" }
        require(description.utf8Size() <= MAX_DESCRIPTION_BYTES) {
            "a translation source description is at most $MAX_DESCRIPTION_BYTES UTF-8 bytes"
        }
    }

    public companion object {
        public const val MAX_SOURCE_BYTES: Int = 64 * 1024
        public const val MAX_DESCRIPTION_BYTES: Int = 16 * 1024
    }
}

/** A bounded opaque continuation token issued by a TMS connector. */
@JvmInline
public value class TranslationCursor(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value.utf8Size() <= MAX_BYTES) { "a translation cursor is 1..$MAX_BYTES UTF-8 bytes" }
    }

    public companion object {
        public const val MAX_BYTES: Int = 512
    }
}

/** Requests at most [limit] candidates for exact catalog and locale identities. */
public data class TranslationDownload(
    public val catalog: CatalogRef,
    public val locales: Set<LocaleTag>,
    public val after: TranslationCursor?,
    public val limit: Int,
) {
    init {
        require(locales.isNotEmpty() && locales.size <= MAX_LOCALES) { "a translation download has 1..$MAX_LOCALES locales" }
        require(limit in 1..MAX_PAGE_SIZE) { "a translation download limit is 1..$MAX_PAGE_SIZE" }
    }

    public companion object {
        public const val MAX_LOCALES: Int = 128
        public const val MAX_PAGE_SIZE: Int = 1_000
    }
}

/** An unreviewed TMS candidate. It is not eligible for a runtime catalog by itself. */
public data class TranslationCandidate(
    public val key: MessageKey,
    public val locale: LocaleTag,
    public val contract: MessageContractRef,
    public val sourceDigest: SourceDigest,
    public val text: String,
) {
    init {
        require(contract.key == key) { "a translation candidate contract belongs to its key" }
        require(text.utf8Size() <= MAX_TEXT_BYTES && !text.hasUnpairedSurrogate()) {
            "a translation candidate is valid UTF-16 and at most $MAX_TEXT_BYTES UTF-8 bytes"
        }
    }

    public companion object {
        public const val MAX_TEXT_BYTES: Int = 64 * 1024
    }
}

/** A stable ordered page; a connector cannot return more candidates than the request's ceiling. */
public data class TranslationDownloadPage(
    public val entries: List<TranslationCandidate>,
    public val next: TranslationCursor?,
) {
    init {
        require(entries.size <= TranslationDownload.MAX_PAGE_SIZE) {
            "a translation download page has at most ${TranslationDownload.MAX_PAGE_SIZE} entries"
        }
        require(entries.map { it.key to it.locale }.toSet().size == entries.size) {
            "a translation download page has unique key and locale pairs"
        }
    }
}

/** Transport outcomes stay separate from local review/compiler diagnostics. */
public sealed interface TranslationUploadOutcome {
    public data class Accepted(
        public val receipt: TranslationReceipt,
    ) : TranslationUploadOutcome

    public data class Refused(
        public val reason: TranslationConnectorRefusal,
    ) : TranslationUploadOutcome
}

public sealed interface TranslationDownloadOutcome {
    public data class Downloaded(
        public val page: TranslationDownloadPage,
    ) : TranslationDownloadOutcome

    public data class Refused(
        public val reason: TranslationConnectorRefusal,
    ) : TranslationDownloadOutcome
}

/** A bounded remote receipt for audit correlation, not a source of authorization. */
public data class TranslationReceipt(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value.utf8Size() <= MAX_BYTES) { "a translation receipt is 1..$MAX_BYTES UTF-8 bytes" }
    }

    public companion object {
        public const val MAX_BYTES: Int = 512
    }
}

/** Deliberately small, transport-safe reasons; vendor details remain in adapter logs. */
public enum class TranslationConnectorRefusal {
    UNAVAILABLE,
    UNAUTHORIZED,
    CATALOG_UNKNOWN,
    CURSOR_INVALID,
}

/**
 * The low-level browser/client contract. It publishes public message schemas only and explicitly
 * does not promise a browser formatter that is identical to the server formatter.
 */
public interface ClientCatalogContract {
    public fun manifest(snapshot: CatalogSnapshot): ClientCatalogManifest
}

/** A client-safe manifest identity and its public-only message contracts. */
public data class ClientCatalogManifest(
    public val catalog: CatalogRef,
    public val digest: Digest,
    public val formattingParity: Boolean,
    public val messages: List<ClientMessageContract>,
) {
    init {
        require(!formattingParity) { "the i18n client contract does not claim formatter parity" }
        require(messages.map(ClientMessageContract::key).toSet().size == messages.size) {
            "a client manifest has unique message keys"
        }
    }
}

/** A structural, template-free public message contract suitable for TypeScript export. */
public class ClientMessageContract(
    public val key: MessageKey,
    public val contract: MessageContractRef,
    arguments: List<ArgumentSpec>,
    public val output: OutputKind,
    markup: Set<String>,
) {
    public val arguments: List<ArgumentSpec> = arguments.toList()
    public val markup: Set<String> = markup.toSortedSet()

    init {
        require(contract.key == key) { "a client message contract belongs to its key" }
        require(
            this.arguments
                .map(ArgumentSpec::name)
                .toSet()
                .size == this.arguments.size,
        ) {
            "a client message contract has unique arguments"
        }
    }
}

/** A bounded, typed tenant identity at the administration boundary. */
@JvmInline
public value class TenantCatalogId(
    public val value: String,
) {
    init {
        require(IDENTIFIER.matches(value)) { "a tenant catalog id matches ${IDENTIFIER.pattern}" }
    }

    public companion object {
        public val IDENTIFIER: Regex = Regex("[a-z][a-z0-9_-]{0,127}")
    }
}

/**
 * A single tenant-overlay operation. Applications bind authorization to this exact typed command
 * before it reaches an adapter; adapters must not infer authority from a tenant id alone.
 */
public sealed interface TenantCatalogAdminCommand {
    public val tenant: TenantCatalogId
    public val operation: TenantCatalogOperationId
    public val expectedVersion: Long
}

public data class SetTenantOverlay(
    override val tenant: TenantCatalogId,
    override val operation: TenantCatalogOperationId,
    override val expectedVersion: Long,
    public val draft: TenantOverlayDraft,
) : TenantCatalogAdminCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
    }
}

public data class ReviewTenantOverlay(
    override val tenant: TenantCatalogId,
    override val operation: TenantCatalogOperationId,
    override val expectedVersion: Long,
    public val revision: String,
) : TenantCatalogAdminCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
        requireValidOverlayRevision(revision)
    }
}

public data class ActivateTenantOverlay(
    override val tenant: TenantCatalogId,
    override val operation: TenantCatalogOperationId,
    override val expectedVersion: Long,
    public val revision: String,
) : TenantCatalogAdminCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
        requireValidOverlayRevision(revision)
    }
}

public data class RollbackTenantOverlay(
    override val tenant: TenantCatalogId,
    override val operation: TenantCatalogOperationId,
    override val expectedVersion: Long,
    public val targetRevision: String,
) : TenantCatalogAdminCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
        requireValidOverlayRevision(targetRevision)
    }
}

/** An opaque caller-supplied id used by a durable adapter to deduplicate exactly one command. */
@JvmInline
public value class TenantCatalogOperationId(
    public val value: UUID,
)

/** An unreviewed whole-message draft; it cannot be installed into a catalog directly. */
public data class TenantOverlayDraft(
    public val base: CatalogRef,
    public val revision: String,
    public val entries: List<TenantOverlayDraftEntry>,
) {
    init {
        requireValidOverlayRevision(revision)
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) { "a tenant overlay draft has 1..$MAX_ENTRIES entries" }
        require(entries.map { it.key to it.locale }.toSet().size == entries.size) {
            "a tenant overlay draft has unique key and locale pairs"
        }
    }

    public companion object {
        public const val MAX_ENTRIES: Int = 10_000
    }
}

/** One non-approved tenant translation, tied to the exact base source and binding contract. */
public data class TenantOverlayDraftEntry(
    public val key: MessageKey,
    public val locale: LocaleTag,
    public val text: String,
    public val contract: MessageContractRef,
    public val sourceDigest: SourceDigest,
) {
    init {
        require(contract.key == key) { "a tenant overlay draft contract belongs to its key" }
        require(text.utf8Size() <= TranslationCandidate.MAX_TEXT_BYTES && !text.hasUnpairedSurrogate()) {
            "a tenant overlay draft is valid UTF-16 and at most ${TranslationCandidate.MAX_TEXT_BYTES} UTF-8 bytes"
        }
    }
}

/** The single low-level tenant-admin SDK entry point. */
public interface TenantCatalogAdministration {
    public fun execute(command: TenantCatalogAdminCommand): TenantCatalogAdminOutcome
}

/** An application-owned policy that grants or denies one exact typed admin command. */
public fun interface TenantCatalogAuthorizer {
    public fun authorize(command: TenantCatalogAdminCommand): TenantCatalogAuthorization
}

public enum class TenantCatalogAuthorization {
    GRANTED,
    DENIED,
}

/** A snapshot lookup gives tenant administration exact-base semantics without global state. */
public fun interface CatalogSnapshotLookup {
    public fun find(reference: CatalogRef): CatalogSnapshot?
}

/** Closed administration result suitable for HTTP, CLI or a low-level SDK caller. */
public sealed interface TenantCatalogAdminOutcome {
    public data class Changed(
        public val version: Long,
        public val active: CatalogOverlayRef?,
    ) : TenantCatalogAdminOutcome

    public data class Conflict(
        public val currentVersion: Long,
    ) : TenantCatalogAdminOutcome

    public data object Forbidden : TenantCatalogAdminOutcome

    public data class Missing(
        public val revision: String,
    ) : TenantCatalogAdminOutcome

    public data class Invalid(
        public val problems: List<TenantCatalogProblem>,
    ) : TenantCatalogAdminOutcome
}

/** A deterministic, redaction-safe validation failure from tenant overlay review/activation. */
public data class TenantCatalogProblem(
    public val path: String,
    public val message: String,
)

/** Builds a public-only contract and intentionally leaves formatter ownership to the client. */
public object PublicClientCatalogContract : ClientCatalogContract {
    override fun manifest(snapshot: CatalogSnapshot): ClientCatalogManifest {
        val messages =
            snapshot.keys
                .sorted()
                .mapNotNull { key -> snapshot.message(key) }
                .filter { record -> record.spec.public }
                .map { record ->
                    ClientMessageContract(
                        record.contract.key,
                        record.contract,
                        record.spec.arguments,
                        record.spec.output,
                        record.spec.markup,
                    )
                }
        return ClientCatalogManifest(snapshot.reference, snapshot.digest, formattingParity = false, messages)
    }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                index += 2
            }

            character.isLowSurrogate() -> {
                return true
            }

            else -> {
                index++
            }
        }
    }
    return false
}

private fun requireValidOverlayRevision(value: String) {
    require(value.isNotEmpty() && value.length <= 128 && value.all { it.code in 0x21..0x7E }) {
        "a tenant overlay revision is 1..128 printable ASCII characters"
    }
}
