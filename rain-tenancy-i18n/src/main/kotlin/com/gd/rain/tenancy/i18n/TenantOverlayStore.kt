package com.gd.rain.tenancy.i18n

import com.gd.rain.i18n.CatalogOverlay
import com.gd.rain.i18n.CatalogOverlayCompilation
import com.gd.rain.i18n.CatalogOverlayCompiler
import com.gd.rain.i18n.CatalogOverlayRef
import com.gd.rain.i18n.CatalogOverlaySpec
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.OverlayLayer
import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantScope
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Opaque tenancy-owned overlay partition derived only from a minted scope.
 *
 * It carries a keyed namespace digest plus the tenant epoch; a restore creates a fresh partition
 * and therefore cannot reuse an older epoch's active overlay/cache identity. Raw tenant references
 * never enter this adapter's public or durable storage contracts.
 */
public class TenantOverlayScope private constructor(
    private val namespace: ByteArray,
    public val epoch: TenantEpoch,
) {
    init {
        require(namespace.size == NAMESPACE_BYTES) { "a tenant overlay namespace has $NAMESPACE_BYTES bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is TenantOverlayScope && epoch == other.epoch && namespace.contentEquals(other.namespace)

    override fun hashCode(): Int = 31 * namespace.contentHashCode() + epoch.hashCode()

    override fun toString(): String = "tenant-overlay-scope[epoch=${epoch.value}, redacted]"

    internal fun namespaceBytes(): ByteArray = namespace.copyOf()

    public companion object {
        /** Derives a partition only from the already-authorized opaque tenant scope. */
        public fun from(scope: TenantScope): TenantOverlayScope = TenantOverlayScope(scope.namespaceSeed(), scope.epoch)

        internal fun persisted(
            namespace: ByteArray,
            epoch: TenantEpoch,
        ): TenantOverlayScope = TenantOverlayScope(namespace, epoch)

        private const val NAMESPACE_BYTES: Int = 32
    }
}

/** Bounded durable audit principal for an overlay administration operation. */
public data class TenantOverlayActor(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "a tenant overlay actor is 1..$MAX_BYTES UTF-8 bytes"
        }
    }

    private companion object {
        const val MAX_BYTES: Int = 256
    }
}

/** Caller-supplied id that gives an exact tenant-overlay command one durable receipt. */
@JvmInline
public value class TenantOverlayOperation(
    public val value: UUID,
)

/** A command changes only one tenant epoch's presentation layer under optimistic version fencing. */
public sealed interface TenantOverlayCommand {
    public val scope: TenantOverlayScope
    public val expectedVersion: Long
    public val actor: TenantOverlayActor
    public val operation: TenantOverlayOperation
}

/** Stages a whole-message tenant candidate; it is not visible until review and activation. */
public data class SetTenantOverlayCommand(
    override val scope: TenantOverlayScope,
    override val expectedVersion: Long,
    override val actor: TenantOverlayActor,
    override val operation: TenantOverlayOperation,
    public val candidate: CatalogOverlaySpec,
) : TenantOverlayCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
        require(candidate.layer == OverlayLayer.TENANT) { "a tenancy adapter stages only tenant overlays" }
    }
}

/** Stamps a staged candidate as compiled/reviewed against its exact current base snapshot. */
public data class ReviewTenantOverlayCommand(
    override val scope: TenantOverlayScope,
    override val expectedVersion: Long,
    override val actor: TenantOverlayActor,
    override val operation: TenantOverlayOperation,
    public val revision: String,
) : TenantOverlayCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
        requireOverlayRevision(revision)
    }
}

/** Makes one reviewed exact-base overlay active for this tenant epoch. */
public data class ActivateTenantOverlayCommand(
    override val scope: TenantOverlayScope,
    override val expectedVersion: Long,
    override val actor: TenantOverlayActor,
    override val operation: TenantOverlayOperation,
    public val revision: String,
) : TenantOverlayCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
        requireOverlayRevision(revision)
    }
}

/** Restores a previously reviewed revision under the same epoch/version fence. */
public data class RollbackTenantOverlayCommand(
    override val scope: TenantOverlayScope,
    override val expectedVersion: Long,
    override val actor: TenantOverlayActor,
    override val operation: TenantOverlayOperation,
    public val targetRevision: String,
) : TenantOverlayCommand {
    init {
        require(expectedVersion >= 0) { "an expected tenant overlay version is non-negative" }
        requireOverlayRevision(targetRevision)
    }
}

/** A store reaches the exact base snapshot through this narrow lookup; it never owns release authority. */
public fun interface TenantOverlayCatalogs {
    public fun snapshot(reference: CatalogRef): CatalogSnapshot?
}

/** The review/activation state of a stored immutable candidate revision. */
public enum class TenantOverlayRevisionState {
    DRAFT,
    REVIEWED,
}

/** A redaction-safe inspection of one revision; template contents are intentionally not returned. */
public data class TenantOverlayRevision(
    public val base: CatalogRef,
    /** Drafts have no content-addressed reference until they pass compilation/review. */
    public val reference: CatalogOverlayRef?,
    public val state: TenantOverlayRevisionState,
)

/** Current epoch-fenced tenant presentation state. */
public data class TenantOverlayCurrent(
    public val version: Long,
    public val active: CatalogOverlay?,
) {
    init {
        require(version >= 0) { "a tenant overlay version is non-negative" }
    }
}

/** One immutable audit observation; it never includes source text or a raw tenant reference. */
public data class TenantOverlayAudit(
    public val sequence: Long,
    public val scope: TenantOverlayScope,
    public val operation: TenantOverlayOperation,
    public val actor: TenantOverlayActor,
    public val kind: TenantOverlayAuditKind,
    public val revision: String,
    public val version: Long,
    public val recordedAt: Instant,
) {
    init {
        require(sequence >= 1 && version >= 1) { "tenant overlay audit sequence and version are positive" }
    }
}

public enum class TenantOverlayAuditKind {
    STAGED,
    REVIEWED,
    ACTIVATED,
    ROLLED_BACK,
}

/** A committed cache-invalidation/change-feed entry. Notifications only wake a cursor read. */
public data class TenantOverlayChange(
    public val cursor: Long,
    public val scope: TenantOverlayScope,
    public val kind: TenantOverlayAuditKind,
    public val active: CatalogOverlayRef?,
    public val version: Long,
    public val recordedAt: Instant,
) {
    init {
        require(cursor >= 1 && version >= 1) { "a tenant overlay change cursor and version are positive" }
    }
}

/** Bounded page of committed invalidation evidence. */
public data class TenantOverlayChangePage(
    public val changes: List<TenantOverlayChange>,
    public val afterCursor: Long,
    public val hasMore: Boolean,
) {
    init {
        require(afterCursor >= 0) { "a tenant overlay change cursor is non-negative" }
        require(changes.zipWithNext().all { (left, right) -> left.cursor < right.cursor }) {
            "tenant overlay changes are cursor ordered"
        }
    }
}

/** Closed command outcome; no error path invents another tenant's revision or a newer overlay. */
public sealed interface TenantOverlayOutcome {
    public data class Changed(
        public val version: Long,
        public val active: CatalogOverlayRef?,
    ) : TenantOverlayOutcome

    public data class Conflict(
        public val currentVersion: Long,
    ) : TenantOverlayOutcome

    public data class Missing(
        public val revision: String,
    ) : TenantOverlayOutcome

    public data class Invalid(
        public val problems: List<TenantOverlayProblem>,
    ) : TenantOverlayOutcome
}

/** A deterministic overlay validation finding, deliberately separate from the source text it describes. */
public data class TenantOverlayProblem(
    public val path: String,
    public val message: String,
)

/**
 * Low-level tenant↔i18n overlay lifecycle. Implementations own their transaction/audit/outbox
 * boundary; callers pass only an epoch-fenced [TenantOverlayScope] derived from a minted scope.
 */
public interface TenantOverlayStore {
    public fun execute(command: TenantOverlayCommand): TenantOverlayOutcome

    /** Returns an overlay only when its exact base catalog still equals [snapshot]. */
    public fun current(
        scope: TenantOverlayScope,
        snapshot: CatalogSnapshot,
    ): TenantOverlayCurrent

    public fun revisions(scope: TenantOverlayScope): List<TenantOverlayRevision>

    public fun audits(scope: TenantOverlayScope): List<TenantOverlayAudit>

    public fun readChanges(
        afterCursor: Long,
        limit: Int,
    ): TenantOverlayChangePage
}

/**
 * Low-level durable form of [TenantOverlayStore]. It follows Rain's transaction placement rule:
 * this adapter verifies and joins a caller-owned transaction but never opens one, retries one or
 * coordinates a jobs/events transaction on the caller's behalf.
 */
public interface TransactionalTenantOverlayStore : TenantOverlayStore {
    public val backing: BackingIdentity

    public fun <T> inCallerTransaction(block: () -> T): T
}

/**
 * Deterministic reference implementation for adapter conformance and local tests.
 *
 * It has the same epoch fence, optimistic versioning, immutable audit/change evidence and operation
 * receipt semantics expected of the PostgreSQL adapter; it deliberately starts no watcher/thread.
 */
public class InMemoryTenantOverlayStore(
    private val catalogs: TenantOverlayCatalogs,
    private val clock: Clock,
) : TenantOverlayStore {
    private val lock: ReentrantLock = ReentrantLock()
    private val states: MutableMap<TenantOverlayScope, State> = mutableMapOf()
    private val changes: MutableList<TenantOverlayChange> = mutableListOf()
    private var nextAudit: Long = 1
    private var nextCursor: Long = 1

    override fun execute(command: TenantOverlayCommand): TenantOverlayOutcome =
        lock.withLock {
            val state = states.getOrPut(command.scope) { State() }
            val fingerprint = fingerprint(command)
            state.receipts[command.operation]?.let { receipt ->
                return if (receipt.fingerprint ==
                    fingerprint
                ) {
                    receipt.outcome
                } else {
                    invalid("operation", "was already used for a different command")
                }
            }
            if (command.expectedVersion != state.version) return TenantOverlayOutcome.Conflict(state.version)
            val outcome =
                when (command) {
                    is SetTenantOverlayCommand -> stage(state, command)
                    is ReviewTenantOverlayCommand -> review(state, command)
                    is ActivateTenantOverlayCommand -> activate(state, command, TenantOverlayAuditKind.ACTIVATED)
                    is RollbackTenantOverlayCommand -> rollback(state, command)
                }
            state.receipts[command.operation] = Receipt(fingerprint, outcome)
            outcome
        }

    override fun current(
        scope: TenantOverlayScope,
        snapshot: CatalogSnapshot,
    ): TenantOverlayCurrent =
        lock.withLock {
            val state = states[scope] ?: return TenantOverlayCurrent(0, null)
            val active = state.active?.takeIf { overlay -> overlay.base == snapshot.reference }
            TenantOverlayCurrent(state.version, active)
        }

    override fun revisions(scope: TenantOverlayScope): List<TenantOverlayRevision> =
        lock.withLock {
            states[scope]
                ?.revisions
                ?.values
                ?.sortedBy { revision -> revision.spec.revision }
                ?.map { revision -> TenantOverlayRevision(revision.spec.base, revision.overlayReference, revision.state) }
                .orEmpty()
        }

    override fun audits(scope: TenantOverlayScope): List<TenantOverlayAudit> = lock.withLock { states[scope]?.audits?.toList().orEmpty() }

    override fun readChanges(
        afterCursor: Long,
        limit: Int,
    ): TenantOverlayChangePage =
        lock.withLock {
            require(afterCursor >= 0) { "a tenant overlay change cursor is non-negative" }
            require(limit in 1..MAX_CHANGE_PAGE) { "a tenant overlay change page is 1..$MAX_CHANGE_PAGE" }
            val eligible = changes.filter { change -> change.cursor > afterCursor }
            val page = eligible.take(limit)
            TenantOverlayChangePage(page, afterCursor, eligible.size > page.size)
        }

    private fun stage(
        state: State,
        command: SetTenantOverlayCommand,
    ): TenantOverlayOutcome {
        val active = state.active?.reference?.revision
        if (active == command.candidate.revision) return invalid("candidate.revision", "is currently active and cannot be overwritten")
        if (command.candidate.revision in state.revisions) {
            return invalid("candidate.revision", "already identifies an immutable stored candidate")
        }
        state.revisions[command.candidate.revision] = Revision(command.candidate, TenantOverlayRevisionState.DRAFT, null)
        return changed(state, command, TenantOverlayAuditKind.STAGED, command.candidate.revision)
    }

    private fun review(
        state: State,
        command: ReviewTenantOverlayCommand,
    ): TenantOverlayOutcome {
        val revision = state.revisions[command.revision] ?: return TenantOverlayOutcome.Missing(command.revision)
        if (revision.state != TenantOverlayRevisionState.DRAFT) return invalid("revision", "was already reviewed")
        val snapshot = catalogs.snapshot(revision.spec.base) ?: return TenantOverlayOutcome.Missing(revision.spec.base.revision)
        return when (val compiled = CatalogOverlayCompiler.compile(snapshot, revision.spec)) {
            is CatalogOverlayCompilation.Compiled -> {
                state.revisions[command.revision] = revision.copy(state = TenantOverlayRevisionState.REVIEWED, overlay = compiled.overlay)
                changed(state, command, TenantOverlayAuditKind.REVIEWED, command.revision)
            }

            is CatalogOverlayCompilation.Refused -> {
                TenantOverlayOutcome.Invalid(compiled.problems.map { problem -> TenantOverlayProblem(problem.path, problem.message) })
            }
        }
    }

    private fun activate(
        state: State,
        command: ActivateTenantOverlayCommand,
        kind: TenantOverlayAuditKind,
    ): TenantOverlayOutcome {
        val revision = state.revisions[command.revision] ?: return TenantOverlayOutcome.Missing(command.revision)
        val overlay = revision.overlay ?: return TenantOverlayOutcome.Missing(command.revision)
        val snapshot = catalogs.snapshot(overlay.base) ?: return TenantOverlayOutcome.Missing(overlay.base.revision)
        if (CatalogOverlayCompiler.compile(snapshot, revision.spec) !is CatalogOverlayCompilation.Compiled) {
            return invalid("revision", "is no longer valid for its exact base snapshot")
        }
        state.active = overlay
        return changed(state, command, kind, command.revision)
    }

    private fun rollback(
        state: State,
        command: RollbackTenantOverlayCommand,
    ): TenantOverlayOutcome =
        activate(
            state,
            ActivateTenantOverlayCommand(command.scope, command.expectedVersion, command.actor, command.operation, command.targetRevision),
            TenantOverlayAuditKind.ROLLED_BACK,
        )

    private fun changed(
        state: State,
        command: TenantOverlayCommand,
        kind: TenantOverlayAuditKind,
        revision: String,
    ): TenantOverlayOutcome.Changed {
        state.version += 1
        val now = clock.instant()
        val active = state.active?.reference
        state.audits += TenantOverlayAudit(nextAudit++, command.scope, command.operation, command.actor, kind, revision, state.version, now)
        changes += TenantOverlayChange(nextCursor++, command.scope, kind, active, state.version, now)
        return TenantOverlayOutcome.Changed(state.version, active)
    }

    private fun invalid(
        path: String,
        message: String,
    ): TenantOverlayOutcome.Invalid = TenantOverlayOutcome.Invalid(listOf(TenantOverlayProblem(path, message)))

    private fun fingerprint(command: TenantOverlayCommand): Digest =
        Digest.sha256(
            buildString {
                append(command::class.qualifiedName).append('\u0000')
                append(command.actor.value).append('\u0000').append(command.expectedVersion).append('\u0000')
                when (command) {
                    is SetTenantOverlayCommand -> {
                        append(command.candidate.base.revision).append('\u0000').append(command.candidate.base.digest.hex).append('\u0000')
                        append(command.candidate.revision).append('\u0000')
                        command.candidate.entries.sortedWith(compareBy({ it.key }, { it.locale })).forEach { entry ->
                            append(entry.key.value).append('\u0000').append(entry.locale.value).append('\u0000')
                            append(entry.contract.revision).append('\u0000').append(entry.contract.digest.value.hex).append('\u0000')
                            append(entry.sourceDigest.value.hex).append('\u0000').append(entry.reviewDigest.value.hex).append('\u0000')
                            append(entry.text).append('\u0000')
                        }
                    }

                    is ReviewTenantOverlayCommand -> {
                        append(command.revision)
                    }

                    is ActivateTenantOverlayCommand -> {
                        append(command.revision)
                    }

                    is RollbackTenantOverlayCommand -> {
                        append(command.targetRevision)
                    }
                }
            }.toByteArray(Charsets.UTF_8),
        )

    private data class State(
        var version: Long = 0,
        val revisions: MutableMap<String, Revision> = mutableMapOf(),
        var active: CatalogOverlay? = null,
        val receipts: MutableMap<TenantOverlayOperation, Receipt> = mutableMapOf(),
        val audits: MutableList<TenantOverlayAudit> = mutableListOf(),
    )

    private data class Revision(
        val spec: CatalogOverlaySpec,
        val state: TenantOverlayRevisionState,
        val overlay: CatalogOverlay?,
    ) {
        val overlayReference: CatalogOverlayRef?
            get() = overlay?.reference
    }

    private data class Receipt(
        val fingerprint: Digest,
        val outcome: TenantOverlayOutcome,
    )

    private companion object {
        const val MAX_CHANGE_PAGE: Int = 16_384
    }
}

private fun requireOverlayRevision(value: String) {
    require(value.isNotEmpty() && value.length <= 128 && value.all { character -> character.code in 0x21..0x7e }) {
        "a tenant overlay revision is 1..128 printable ASCII characters"
    }
}
