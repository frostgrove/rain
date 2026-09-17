package com.gd.rain.i18n.persistence

import com.gd.rain.i18n.ArtifactEnvelope
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.CatalogTrustPolicy
import com.gd.rain.i18n.CatalogTrustRefusal
import com.gd.rain.persistence.tx.BackingIdentity
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A bounded application-defined head namespace; it is deliberately not a tenant identifier. */
public data class CatalogReleaseScope(
    public val value: String,
) {
    init {
        require(value.matches(IDENTIFIER)) { "a catalog release scope is a stable 1..128 character identifier" }
    }

    private companion object {
        val IDENTIFIER: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** A bounded audit principal supplied by the application, never an authentication token or free-form payload. */
public data class CatalogReleaseActor(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 256) {
            "a catalog release actor is 1..256 UTF-8 bytes"
        }
    }
}

/** A bounded application audit-correlation key whose scope is the catalog head namespace. */
public data class CatalogReleaseOperation(
    public val value: String,
) {
    init {
        require(value.matches(IDENTIFIER)) { "a catalog release operation is a stable 1..128 character identifier" }
    }

    private companion object {
        val IDENTIFIER: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/**
 * Raw artifact ingress for the durable store. The adapter re-verifies it through its configured
 * [com.gd.rain.i18n.TrustedCatalogLoader] before it writes any row and once more on every load.
 */
public class CatalogArtifactSubmission(
    artifact: ByteArray,
    public val trustPolicy: CatalogTrustPolicy,
    public val envelope: ArtifactEnvelope? = null,
    maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES,
) {
    private val artifact: ByteArray = artifact.copyOf()

    init {
        require(maxArtifactBytes > 0) { "maxArtifactBytes is positive" }
        require(this.artifact.isNotEmpty() && this.artifact.size <= maxArtifactBytes) {
            "a catalog artifact is 1..$maxArtifactBytes bytes"
        }
    }

    public fun artifactBytes(): ByteArray = artifact.copyOf()

    private companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES: Int = 16 * 1024 * 1024
    }
}

/** Finite durable lifecycle limits. Artifact grammar/data ceilings stay owned by the kernel loader. */
public data class CatalogPersistenceLimits(
    public val maxRetained: Int = 8,
    public val maxPins: Int = 1_024,
    public val maxPinLifetime: Duration = Duration.ofDays(30),
    public val maxChangePageSize: Int = 1_024,
) {
    init {
        require(maxRetained in 1..4_096) { "maxRetained is 1..4096" }
        require(maxPins in 1..1_048_576) { "maxPins is 1..1048576" }
        require(maxPinLifetime > Duration.ZERO && maxPinLifetime <= Duration.ofDays(3650)) {
            "maxPinLifetime is positive and at most 3650 days"
        }
        require(maxChangePageSize in 1..16_384) { "maxChangePageSize is 1..16384" }
    }
}

/** The durable counterpart to an in-process head token; version makes ABA attempts fail. */
public data class DurableCatalogHead(
    public val scope: CatalogReleaseScope,
    public val reference: CatalogRef,
    public val version: Long,
) {
    init {
        require(version >= 1) { "a durable catalog head version is positive" }
    }
}

/** A snapshot and its CAS token read from one durable transaction. */
public data class DurableCatalogCurrent(
    public val head: DurableCatalogHead,
    public val snapshot: CatalogSnapshot,
)

/** Reading the durable head is also a trust/identity boundary, so absence is not an exception. */
public sealed interface DurableCatalogCurrentLoad {
    public data class Loaded(
        public val current: DurableCatalogCurrent,
    ) : DurableCatalogCurrentLoad

    public data object Missing : DurableCatalogCurrentLoad

    public data class Refused(
        public val reason: CatalogTrustRefusal,
    ) : DurableCatalogCurrentLoad
}

/** Immutable publication and activation input. A null expected head means create this scope only. */
public data class CatalogReleaseCommand(
    public val scope: CatalogReleaseScope,
    public val expected: DurableCatalogHead?,
    public val artifact: CatalogArtifactSubmission,
    public val actor: CatalogReleaseActor,
    public val operation: CatalogReleaseOperation,
) {
    init {
        require(expected == null || expected.scope == scope) { "the expected head belongs to this catalog scope" }
    }
}

/** Rollback selects one previously retained exact release under the same opaque-head CAS rule. */
public data class CatalogRollbackCommand(
    public val scope: CatalogReleaseScope,
    public val expected: DurableCatalogHead,
    public val target: CatalogRef,
    public val actor: CatalogReleaseActor,
    public val operation: CatalogReleaseOperation,
) {
    init {
        require(expected.scope == scope) { "the expected head belongs to this catalog scope" }
    }
}

/** A finite durable lease for one exact catalog release. */
public data class DurableCatalogPin(
    public val id: UUID,
    public val scope: CatalogReleaseScope,
    public val reference: CatalogRef,
    public val owner: String,
    public val expiresAt: Instant,
) {
    init {
        require(owner.isNotBlank() && owner.toByteArray(Charsets.UTF_8).size <= 256) {
            "a catalog pin owner is 1..256 UTF-8 bytes"
        }
    }
}

/** A pin request can never mean an unbounded/forever retention lease. */
public data class CatalogPinCommand(
    public val scope: CatalogReleaseScope,
    public val reference: CatalogRef,
    public val owner: String,
    public val lifetime: Duration,
    public val actor: CatalogReleaseActor,
    public val operation: CatalogReleaseOperation,
) {
    init {
        require(owner.isNotBlank() && owner.toByteArray(Charsets.UTF_8).size <= 256) {
            "a catalog pin owner is 1..256 UTF-8 bytes"
        }
    }
}

/** Releasing a durable pin is auditable rather than an unauthenticated delete by opaque UUID. */
public data class CatalogPinReleaseCommand(
    public val pin: DurableCatalogPin,
    public val actor: CatalogReleaseActor,
    public val operation: CatalogReleaseOperation,
)

/** A bounded, auditable expiry sweep. The store never deletes a live pin because a caller supplied a future cutoff. */
public data class CatalogPinSweepCommand(
    public val scope: CatalogReleaseScope,
    public val expiredAtOrBefore: Instant,
    public val limit: Int,
    public val actor: CatalogReleaseActor,
    public val operation: CatalogReleaseOperation,
) {
    init {
        require(limit in 1..10_000) { "a catalog pin sweep limit is 1..10000" }
    }
}

/** The closed reason why a durable transition cannot change its head. */
public enum class CatalogPersistenceLimitReason {
    RETENTION_HELD_BY_PINS,
    PIN_COUNT,
    PIN_LIFETIME,
}

/** Classification is explicit: a store never converts a trust/load refusal into a stale conflict. */
public sealed interface DurableCatalogTransition {
    public data class Updated(
        public val head: DurableCatalogHead,
    ) : DurableCatalogTransition

    public data class Conflict(
        public val current: DurableCatalogHead?,
    ) : DurableCatalogTransition

    public data class Missing(
        public val reference: CatalogRef,
    ) : DurableCatalogTransition

    public data class Limit(
        public val reason: CatalogPersistenceLimitReason,
    ) : DurableCatalogTransition

    public data class Refused(
        public val reason: CatalogTrustRefusal,
    ) : DurableCatalogTransition
}

/** Result of loading a retained release. Raw database bytes never leave this type. */
public sealed interface DurableCatalogLoad {
    public data class Loaded(
        public val snapshot: CatalogSnapshot,
    ) : DurableCatalogLoad

    public data class Missing(
        public val reference: CatalogRef,
    ) : DurableCatalogLoad

    public data class Refused(
        public val reason: CatalogTrustRefusal,
    ) : DurableCatalogLoad
}

/** A committed, cursor-addressed change. Notification transports may only wake a reader of this log. */
public data class CatalogChange(
    public val cursor: Long,
    public val scope: CatalogReleaseScope,
    public val kind: CatalogChangeKind,
    public val reference: CatalogRef,
    public val headVersion: Long?,
    public val recordedAt: Instant,
) {
    init {
        require(cursor >= 1) { "a catalog change cursor is positive" }
        require(headVersion == null || headVersion >= 1) { "a catalog change head version is positive" }
    }
}

public enum class CatalogChangeKind {
    HEAD_CHANGED,
    RELEASE_PRUNED,
}

/** A finite cursor page; callers must advance from [afterCursor] rather than subscribe to an ambient feed. */
public data class CatalogChangePage(
    public val changes: List<CatalogChange>,
    public val afterCursor: Long,
    public val hasMore: Boolean,
) {
    init {
        require(afterCursor >= 0) { "a catalog change cursor is not negative" }
        require(changes.zipWithNext().all { (left, right) -> left.cursor < right.cursor }) {
            "catalog changes are strictly cursor ordered"
        }
    }
}

/**
 * Low-level durable catalog SDK. Each method runs only inside an application-owned transaction;
 * it never begins, commits, retries or propagates one into jobs/events/tenancy.
 */
public interface CatalogReleaseStore {
    public val backing: BackingIdentity
    public val limits: CatalogPersistenceLimits

    public fun <T> inCallerTransaction(block: (CatalogReleaseTransaction) -> T): T

    public fun current(
        transaction: CatalogReleaseTransaction,
        scope: CatalogReleaseScope,
    ): DurableCatalogCurrentLoad

    public fun load(
        transaction: CatalogReleaseTransaction,
        scope: CatalogReleaseScope,
        reference: CatalogRef,
    ): DurableCatalogLoad

    public fun publishAndActivate(
        transaction: CatalogReleaseTransaction,
        command: CatalogReleaseCommand,
    ): DurableCatalogTransition

    public fun rollback(
        transaction: CatalogReleaseTransaction,
        command: CatalogRollbackCommand,
    ): DurableCatalogTransition

    public fun pin(
        transaction: CatalogReleaseTransaction,
        command: CatalogPinCommand,
    ): CatalogPinResult

    public fun release(
        transaction: CatalogReleaseTransaction,
        command: CatalogPinReleaseCommand,
    ): Boolean

    /** Removes at most [CatalogPinSweepCommand.limit] already expired pins and writes immutable audit evidence. */
    public fun sweepExpiredPins(
        transaction: CatalogReleaseTransaction,
        command: CatalogPinSweepCommand,
    ): Int

    public fun prune(
        transaction: CatalogReleaseTransaction,
        scope: CatalogReleaseScope,
        actor: CatalogReleaseActor,
        operation: CatalogReleaseOperation,
    ): List<CatalogRef>

    public fun readChanges(
        transaction: CatalogReleaseTransaction,
        afterCursor: Long,
        limit: Int,
    ): CatalogChangePage
}

public sealed interface CatalogPinResult {
    public data class Pinned(
        public val pin: DurableCatalogPin,
    ) : CatalogPinResult

    public data class Missing(
        public val reference: CatalogRef,
    ) : CatalogPinResult

    public data class Limit(
        public val reason: CatalogPersistenceLimitReason,
    ) : CatalogPinResult
}

/** An opaque caller-transaction token. Store implementations alone may mint one. */
public class CatalogReleaseTransaction internal constructor(
    internal val storeId: UUID,
    public val backing: BackingIdentity,
    internal val nonce: UUID,
)

/** Reusable token integrity checks for durable store implementations. */
public abstract class CatalogReleaseStoreSupport : CatalogReleaseStore {
    private val storeId: UUID = UUID.randomUUID()

    protected fun newTransaction(): CatalogReleaseTransaction = CatalogReleaseTransaction(storeId, backing, UUID.randomUUID())

    protected fun requireTransaction(transaction: CatalogReleaseTransaction) {
        check(transaction.storeId == storeId) { "catalog release transaction belongs to another store" }
        check(transaction.backing == backing) { "catalog release transaction belongs to another backing" }
    }

    protected fun isSameTransaction(
        left: CatalogReleaseTransaction,
        right: CatalogReleaseTransaction,
    ): Boolean = left.storeId == right.storeId && left.nonce == right.nonce && left.backing == right.backing
}
