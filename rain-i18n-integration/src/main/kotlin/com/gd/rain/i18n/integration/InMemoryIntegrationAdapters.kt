package com.gd.rain.i18n.integration

import com.gd.rain.i18n.CatalogDigests
import com.gd.rain.i18n.CatalogOverlay
import com.gd.rain.i18n.CatalogOverlayCompilation
import com.gd.rain.i18n.CatalogOverlayCompiler
import com.gd.rain.i18n.CatalogOverlaySpec
import com.gd.rain.i18n.OverlayLayer
import com.gd.rain.i18n.OverlayTranslationSpec
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Deterministic remote-release exchange fixture and a usable local low-level adapter.
 *
 * It keeps publication order as the source order, treats re-publication of the exact immutable
 * reference as idempotent, and never activates or trusts a release. Production connectors must
 * meet these same cursor and conflict properties before their packages reach a trusted loader.
 */
public class InMemoryCatalogReleaseExchange(
    private val maxReleases: Int = 10_000,
) : CatalogReleaseSource,
    CatalogReleaseSink {
    private val lock: ReentrantLock = ReentrantLock()
    private val releases: LinkedHashMap<com.gd.rain.i18n.CatalogRef, CatalogReleasePackage> = linkedMapOf()

    init {
        require(maxReleases in 1..100_000) { "an in-memory catalog release exchange holds 1..100000 releases" }
    }

    override fun fetch(request: CatalogReleaseFetch): CatalogReleaseFetchOutcome =
        lock.withLock {
            val keys = releases.keys.toList()
            val start =
                request.after?.let { cursor ->
                    val index = keys.indexOf(cursor)
                    if (index < 0) return CatalogReleaseFetchOutcome.Refused(CatalogReleaseExchangeRefusal.CURSOR_INVALID)
                    index + 1
                } ?: 0
            val page = releases.values.drop(start).take(request.limit)
            val next = page.lastOrNull()?.reference?.takeIf { start + page.size < releases.size }
            CatalogReleaseFetchOutcome.Fetched(CatalogReleasePage(page, next))
        }

    override fun publish(release: CatalogReleasePackage): CatalogReleasePublicationOutcome =
        lock.withLock {
            val existing = releases[release.reference]
            if (existing != null) {
                return if (existing.artifactBytes().contentEquals(release.artifactBytes()) && sameEnvelope(existing, release)) {
                    CatalogReleasePublicationOutcome.Published(receipt(release), alreadyPresent = true)
                } else {
                    CatalogReleasePublicationOutcome.Refused(CatalogReleaseExchangeRefusal.RELEASE_CONFLICT)
                }
            }
            if (releases.size >=
                maxReleases
            ) {
                return CatalogReleasePublicationOutcome.Refused(CatalogReleaseExchangeRefusal.CAPACITY_REACHED)
            }
            releases[release.reference] = release
            CatalogReleasePublicationOutcome.Published(receipt(release), alreadyPresent = false)
        }

    private fun receipt(release: CatalogReleasePackage): CatalogReleaseReceipt =
        CatalogReleaseReceipt("in_memory:${release.reference.digest.hex}")

    private fun sameEnvelope(
        left: CatalogReleasePackage,
        right: CatalogReleasePackage,
    ): Boolean =
        left.envelope.origin == right.envelope.origin &&
            left.envelope.artifactDigest == right.envelope.artifactDigest &&
            left.envelope.keyId == right.envelope.keyId &&
            left.envelope.algorithm == right.envelope.algorithm &&
            left.envelope.signatureBytes().contentEquals(right.envelope.signatureBytes())
}

/**
 * Deterministic in-memory TMS fixture for connector conformance tests. It deliberately stores
 * candidates as candidates: callers must locally review, compile and activate any downloaded text.
 */
public class InMemoryTranslationManagementConnector : TranslationManagementConnector {
    private val lock: ReentrantLock = ReentrantLock()
    private val sources: MutableMap<com.gd.rain.i18n.CatalogRef, List<TranslationSourceEntry>> = mutableMapOf()
    private val candidates: MutableMap<com.gd.rain.i18n.CatalogRef, MutableList<TranslationCandidate>> = mutableMapOf()

    override fun upload(request: TranslationUpload): TranslationUploadOutcome =
        lock.withLock {
            sources[request.catalog] = request.entries.sortedBy(TranslationSourceEntry::key)
            TranslationUploadOutcome.Accepted(TranslationReceipt("in_memory:${request.catalog.digest.hex}"))
        }

    /** Adds a received vendor candidate after independently checking that its source contract was uploaded. */
    public fun record(
        catalog: com.gd.rain.i18n.CatalogRef,
        candidate: TranslationCandidate,
    ): Boolean =
        lock.withLock {
            val declared = sources[catalog].orEmpty().firstOrNull { it.key == candidate.key } ?: return false
            if (declared.contract != candidate.contract || declared.sourceDigest != candidate.sourceDigest) return false
            val values = candidates.getOrPut(catalog) { mutableListOf() }
            values.removeAll { it.key == candidate.key && it.locale == candidate.locale }
            values += candidate
            true
        }

    override fun download(request: TranslationDownload): TranslationDownloadOutcome =
        lock.withLock {
            if (request.catalog !in sources) return TranslationDownloadOutcome.Refused(TranslationConnectorRefusal.CATALOG_UNKNOWN)
            if (request.after != null) return TranslationDownloadOutcome.Refused(TranslationConnectorRefusal.CURSOR_INVALID)
            val entries =
                candidates[request.catalog]
                    .orEmpty()
                    .asSequence()
                    .filter { it.locale in request.locales }
                    .sortedWith(compareBy(TranslationCandidate::key, TranslationCandidate::locale))
                    .take(request.limit)
                    .toList()
            TranslationDownloadOutcome.Downloaded(TranslationDownloadPage(entries, null))
        }
}

/**
 * Deterministic tenant-admin adapter for SDK/conformance use. Durable tenancy storage supplies the
 * same command contract in production; this fixture has no background worker or ambient tenant state.
 */
public class InMemoryTenantCatalogAdministration(
    private val snapshots: CatalogSnapshotLookup,
    private val authorizer: TenantCatalogAuthorizer,
) : TenantCatalogAdministration {
    private val lock: ReentrantLock = ReentrantLock()
    private val states: MutableMap<TenantCatalogId, TenantState> = mutableMapOf()

    override fun execute(command: TenantCatalogAdminCommand): TenantCatalogAdminOutcome =
        lock.withLock {
            if (authorizer.authorize(command) == TenantCatalogAuthorization.DENIED) {
                return TenantCatalogAdminOutcome.Forbidden
            }
            val state = states.getOrPut(command.tenant) { TenantState() }
            state.operations[command.operation]?.let { executed ->
                return if (executed.command == command) {
                    executed.outcome
                } else {
                    TenantCatalogAdminOutcome.Invalid(
                        listOf(TenantCatalogProblem("operation", "was already used for a different command")),
                    )
                }
            }
            if (command.expectedVersion != state.version) return TenantCatalogAdminOutcome.Conflict(state.version)
            val outcome =
                when (command) {
                    is SetTenantOverlay -> set(state, command)
                    is ReviewTenantOverlay -> review(state, command)
                    is ActivateTenantOverlay -> activate(state, command.revision)
                    is RollbackTenantOverlay -> activate(state, command.targetRevision)
                }
            if (outcome is TenantCatalogAdminOutcome.Changed) {
                state.operations[command.operation] = CommandExecution(command, outcome)
            }
            outcome
        }

    /** The currently active immutable overlay for tests and non-durable sample adapters. */
    public fun active(tenant: TenantCatalogId): CatalogOverlay? = lock.withLock { states[tenant]?.active }

    private fun set(
        state: TenantState,
        command: SetTenantOverlay,
    ): TenantCatalogAdminOutcome {
        state.drafts[command.draft.revision] = command.draft
        state.approved.remove(command.draft.revision)
        if (state.active?.reference?.revision == command.draft.revision) state.active = null
        state.version++
        return changed(state)
    }

    private fun review(
        state: TenantState,
        command: ReviewTenantOverlay,
    ): TenantCatalogAdminOutcome {
        val draft = state.drafts[command.revision] ?: return TenantCatalogAdminOutcome.Missing(command.revision)
        val snapshot = snapshots.find(draft.base) ?: return TenantCatalogAdminOutcome.Missing(draft.base.revision)
        val source =
            CatalogOverlaySpec(
                draft.base,
                OverlayLayer.TENANT,
                draft.revision,
                draft.entries.map { entry ->
                    OverlayTranslationSpec(
                        entry.key,
                        entry.locale,
                        entry.text,
                        entry.contract,
                        entry.sourceDigest,
                        CatalogDigests.review(entry.sourceDigest, entry.locale, entry.text),
                    )
                },
            )
        return when (val compiled = CatalogOverlayCompiler.compile(snapshot, source)) {
            is CatalogOverlayCompilation.Compiled -> {
                state.approved[draft.revision] = compiled.overlay
                state.version++
                changed(state)
            }

            is CatalogOverlayCompilation.Refused -> {
                TenantCatalogAdminOutcome.Invalid(
                    compiled.problems.map { TenantCatalogProblem(it.path, it.message) },
                )
            }
        }
    }

    private fun activate(
        state: TenantState,
        revision: String,
    ): TenantCatalogAdminOutcome {
        val overlay = state.approved[revision] ?: return TenantCatalogAdminOutcome.Missing(revision)
        if (snapshots.find(overlay.base) == null) return TenantCatalogAdminOutcome.Missing(overlay.base.revision)
        state.active = overlay
        state.version++
        return changed(state)
    }

    private fun changed(state: TenantState): TenantCatalogAdminOutcome.Changed =
        TenantCatalogAdminOutcome.Changed(state.version, state.active?.reference)

    private class TenantState {
        var version: Long = 0
        val drafts: MutableMap<String, TenantOverlayDraft> = mutableMapOf()
        val approved: MutableMap<String, CatalogOverlay> = mutableMapOf()
        val operations: MutableMap<TenantCatalogOperationId, CommandExecution> = mutableMapOf()
        var active: CatalogOverlay? = null
    }

    private data class CommandExecution(
        val command: TenantCatalogAdminCommand,
        val outcome: TenantCatalogAdminOutcome.Changed,
    )
}
