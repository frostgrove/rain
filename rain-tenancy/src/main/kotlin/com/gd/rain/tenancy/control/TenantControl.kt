package com.gd.rain.tenancy.control

import com.gd.rain.tenancy.TenantDigest
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolutionDirectory
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A keyed index of a tenant reference for the control plane; its output is safe for storage, not for identity. */
public fun interface TenantReferenceDigest {
    public fun digest(ref: TenantRef): TenantDigest
}

/** HMAC reference index with a domain separate from scope, audit, cache, and event namespace digests. */
public class HmacTenantReferenceDigest(
    key: ByteArray,
) : TenantReferenceDigest {
    private val key: ByteArray =
        key.copyOf().also {
            require(it.size >= KEY_BYTES) { "tenant reference digest key is at least $KEY_BYTES bytes" }
        }

    override fun digest(ref: TenantRef): TenantDigest =
        TenantDigest(
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(DOMAIN + byteArrayOf(0) + ref.bytes())
            },
        )

    private companion object {
        const val KEY_BYTES: Int = 32
        val DOMAIN: ByteArray = "rain.tenancy.control-ref.v1".toByteArray(Charsets.UTF_8)
    }
}

/** A positive optimistic version from the control row. */
@JvmInline
public value class TenantControlVersion(
    public val value: Long,
) {
    init {
        require(value > 0) { "tenant control version is positive" }
    }
}

/** The version the caller observed. Zero is meaningful only for create-draft's absent-row expectation. */
@JvmInline
public value class ExpectedTenantControlVersion(
    public val value: Long,
) {
    init {
        require(value >= 0) { "expected tenant control version is non-negative" }
    }
}

/** A caller-minted id that makes one control command retryable without making control an upsert API. */
@JvmInline
public value class TenantOperationId(
    public val value: UUID,
) {
    init {
        require(value != UUID(0, 0)) { "tenant operation id is not nil" }
    }

    override fun toString(): String = "tenant-operation[$value]"
}

/** A state-changing command's closed vocabulary. It is also what an operation receipt fingerprints. */
public enum class TenantControlAction {
    CREATE_DRAFT,
    BEGIN_PROVISION,
    ACTIVATE,
    MAKE_READ_ONLY,
    RESUME_WRITES,
    SUSPEND,
    BEGIN_MIGRATION,
    FINISH_MIGRATION,
    BEGIN_DELETION,
    TOMBSTONE,
    RESTORE_NEW_EPOCH,
    ROTATE_PLACEMENT,
}

/** One resolved control row, including the version required by the next conditional command. */
public data class TenantControlSnapshot(
    public val resolution: TenantResolution,
    public val version: TenantControlVersion,
)

/** Input common to every command over an existing control row. */
public data class TenantControlCommand(
    public val ref: TenantRef,
    public val expectedVersion: ExpectedTenantControlVersion,
    public val operationId: TenantOperationId,
)

/** Creation has no raw-credential or arbitrary lifecycle escape hatch: every new row starts provisioning. */
public data class TenantCreateDraft(
    public val ref: TenantRef,
    public val operationId: TenantOperationId,
    public val placementVersion: Long = 1,
) {
    init {
        require(placementVersion > 0) { "tenant placement version is positive" }
    }
}

/** Restoring a tombstone makes a visibly new epoch and may select only a positive placement revision. */
public data class TenantRestoreAsNewEpoch(
    public val command: TenantControlCommand,
    public val placementVersion: Long,
) {
    init {
        require(placementVersion > 0) { "tenant placement version is positive" }
    }
}

/** The only low-cardinality outcomes a command can return; none exposes a raw tenant reference. */
public sealed interface TenantControlResult {
    public data class Created(
        public val snapshot: TenantControlSnapshot,
    ) : TenantControlResult

    public data class Transitioned(
        public val snapshot: TenantControlSnapshot,
    ) : TenantControlResult

    /** The caller can refresh from this fenced snapshot and choose a new operation id. */
    public data class VersionConflict(
        public val current: TenantControlSnapshot,
    ) : TenantControlResult

    public data object NotFound : TenantControlResult

    /** The row exists but its closed lifecycle graph does not admit this action. */
    public data class TransitionRefused(
        public val current: TenantControlSnapshot,
    ) : TenantControlResult

    /** Mandatory provisioning evidence for this epoch is absent, busy, or quarantined. */
    public data class ProvisioningIncomplete(
        public val current: TenantControlSnapshot,
    ) : TenantControlResult

    /** The same operation id was presented with a different tenant or command fingerprint. */
    public data object OperationCollision : TenantControlResult
}

/**
 * The only lifecycle mutation surface. There is deliberately no register/update/upsert method.
 *
 * Each successful command is conditional on [TenantControlCommand.expectedVersion], appends immutable
 * transition evidence, records an exact operation receipt, and is suitable for one transaction with audit.
 */
public interface TenantControlPlane : TenantResolutionDirectory {
    /** Durable lookup used by a resolver or authority; request-candidate resolution stays outside this control API. */
    override fun lookup(ref: TenantRef): TenantResolution?

    public fun createDraft(command: TenantCreateDraft): TenantControlResult

    public fun beginProvision(command: TenantControlCommand): TenantControlResult

    public fun activate(command: TenantControlCommand): TenantControlResult

    public fun makeReadOnly(command: TenantControlCommand): TenantControlResult

    public fun resumeWrites(command: TenantControlCommand): TenantControlResult

    public fun suspend(command: TenantControlCommand): TenantControlResult

    public fun beginMigration(command: TenantControlCommand): TenantControlResult

    public fun finishMigration(command: TenantControlCommand): TenantControlResult

    public fun beginDeletion(command: TenantControlCommand): TenantControlResult

    public fun tombstone(command: TenantControlCommand): TenantControlResult

    public fun restoreAsNewEpoch(command: TenantRestoreAsNewEpoch): TenantControlResult

    public fun rotatePlacement(command: TenantControlCommand): TenantControlResult
}

/** The lifecycle graph is data, so it is testable and no command can invent a target state. */
public object TenantLifecycleGraph {
    public fun target(
        action: TenantControlAction,
        current: TenantLifecycle,
    ): TenantLifecycle? =
        when (action) {
            TenantControlAction.BEGIN_PROVISION -> TenantLifecycle.PROVISIONING.takeIf { current == TenantLifecycle.PROVISIONING }
            TenantControlAction.ACTIVATE -> TenantLifecycle.ACTIVE.takeIf { current == TenantLifecycle.PROVISIONING }
            TenantControlAction.MAKE_READ_ONLY -> TenantLifecycle.READ_ONLY.takeIf { current == TenantLifecycle.ACTIVE }
            TenantControlAction.RESUME_WRITES -> TenantLifecycle.ACTIVE.takeIf { current == TenantLifecycle.READ_ONLY }
            TenantControlAction.SUSPEND -> TenantLifecycle.SUSPENDED.takeIf { current in SUSPENDABLE }
            TenantControlAction.BEGIN_MIGRATION -> TenantLifecycle.MIGRATING.takeIf { current in MIGRATABLE }
            TenantControlAction.FINISH_MIGRATION -> TenantLifecycle.ACTIVE.takeIf { current == TenantLifecycle.MIGRATING }
            TenantControlAction.BEGIN_DELETION -> TenantLifecycle.DELETING.takeIf { current in DELETABLE }
            TenantControlAction.TOMBSTONE -> TenantLifecycle.DELETED.takeIf { current == TenantLifecycle.DELETING }
            TenantControlAction.RESTORE_NEW_EPOCH -> TenantLifecycle.PROVISIONING.takeIf { current == TenantLifecycle.DELETED }
            TenantControlAction.ROTATE_PLACEMENT -> current.takeIf { current in PLACEMENT_ROTATABLE }
            TenantControlAction.CREATE_DRAFT -> null
        }

    private val SUSPENDABLE: Set<TenantLifecycle> =
        setOf(TenantLifecycle.PROVISIONING, TenantLifecycle.ACTIVE, TenantLifecycle.READ_ONLY, TenantLifecycle.MIGRATING)
    private val MIGRATABLE: Set<TenantLifecycle> = setOf(TenantLifecycle.ACTIVE, TenantLifecycle.READ_ONLY)
    private val DELETABLE: Set<TenantLifecycle> =
        setOf(
            TenantLifecycle.PROVISIONING,
            TenantLifecycle.ACTIVE,
            TenantLifecycle.READ_ONLY,
            TenantLifecycle.SUSPENDED,
            TenantLifecycle.MIGRATING,
        )
    private val PLACEMENT_ROTATABLE: Set<TenantLifecycle> = setOf(TenantLifecycle.ACTIVE, TenantLifecycle.READ_ONLY)
}
