package com.gd.rain.tenancy.control

import com.gd.rain.audit.AuditDetail
import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditEventType
import com.gd.rain.audit.AuditOutcome
import com.gd.rain.audit.AuditRecorder
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.tenancy.TenantDigest
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.cache.TenantCacheGeneration
import com.gd.rain.tenancy.jooq.Tables.TENANT
import com.gd.rain.tenancy.jooq.Tables.TENANT_OPERATION_RECEIPT
import com.gd.rain.tenancy.jooq.Tables.TENANT_RUNTIME_OPERATION_RECEIPT
import com.gd.rain.tenancy.jooq.Tables.TENANT_RUNTIME_SETTING
import com.gd.rain.tenancy.jooq.Tables.TENANT_RUNTIME_STATE
import com.gd.rain.tenancy.jooq.Tables.TENANT_TRANSITION
import com.gd.rain.tenancy.jooq.tables.records.TenantOperationReceiptRecord
import com.gd.rain.tenancy.jooq.tables.records.TenantRecord
import com.gd.rain.tenancy.jooq.tables.records.TenantRuntimeOperationReceiptRecord
import com.gd.rain.tenancy.jooq.tables.records.TenantRuntimeStateRecord
import com.gd.rain.tenancy.provisioning.TenantProvisioningActivationGate
import com.gd.rain.tenancy.settings.TenantSettingMutation
import com.gd.rain.tenancy.settings.TenantSettingsVersion
import org.jooq.DSLContext
import org.springframework.transaction.support.TransactionOperations
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The one audit type the tenancy control plane needs the application to declare as evidence. */
public object TenantControlAudit {
    public val TRANSITION: AuditEventType =
        AuditEventType(
            module = "tenancy",
            action = "transition",
            resourceKind = "tenant",
            detailKeys = setOf("action", "from", "to", "epoch", "operation", "placement_version", "version"),
        )

    public val RUNTIME: AuditEventType =
        AuditEventType(
            module = "tenancy",
            action = "runtime",
            resourceKind = "tenant",
            detailKeys =
                setOf(
                    "action",
                    "operation",
                    "epoch",
                    "control_version",
                    "runtime_version",
                    "cache_generation",
                    "settings_version",
                ),
        )
}

/**
 * PostgreSQL control plane with conditional lifecycle writes, immutable transition evidence, and
 * idempotent operation receipts. The audit recorder joins [transactions], so evidence and mutation
 * either commit together or neither does.
 */
public class JooqTenantControlPlane(
    private val dsl: DSLContext,
    private val transactions: TransactionOperations,
    private val referenceDigest: TenantReferenceDigest,
    private val audit: AuditRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val provisioning: TenantProvisioningActivationGate = TenantProvisioningActivationGate.NONE,
) : TenantControlPlane,
    TenantRuntimeControlPlane {
    override fun createDraft(command: TenantCreateDraft): TenantControlResult =
        inControlTransaction {
            val digest = referenceDigest.digest(command.ref)
            val action = TenantControlAction.CREATE_DRAFT
            val fingerprint = fingerprint(action, digest, expectedVersion = 0, command.placementVersion)
            when (val claim = receipt(command.operationId, digest, action, fingerprint)) {
                ReceiptClaim.Collision -> return@inControlTransaction TenantControlResult.OperationCollision
                is ReceiptClaim.Existing -> return@inControlTransaction claim.receipt.toResult(command.ref)
                ReceiptClaim.New -> Unit
            }

            val now = clock.instant()
            val snapshot =
                TenantControlSnapshot(
                    TenantResolution(command.ref, TenantLifecycle.PROVISIONING, TenantEpoch(1), command.placementVersion),
                    TenantControlVersion(1),
                )
            val inserted =
                dsl
                    .insertInto(TENANT)
                    .set(TENANT.REF_DIGEST, digest.copy())
                    .set(TENANT.LIFECYCLE, wire(snapshot.resolution.lifecycle))
                    .set(TENANT.EPOCH, snapshot.resolution.epoch.value)
                    .set(TENANT.PLACEMENT_VERSION, snapshot.resolution.placementVersion)
                    .set(TENANT.ROW_VERSION, snapshot.version.value)
                    .set(TENANT.CREATED_AT, at(now))
                    .set(TENANT.UPDATED_AT, at(now))
                    .onConflict(TENANT.REF_DIGEST)
                    .doNothing()
                    .execute()
            val result =
                if (inserted == 0) {
                    TenantControlResult.VersionConflict(checkNotNull(lock(command.ref, digest)))
                } else {
                    appendTransition(action, command.operationId, digest, null, snapshot, 0, now)
                    audit(action, command.operationId, digest, null, snapshot)
                    TenantControlResult.Created(snapshot)
                }
            complete(command.operationId, result)
            result
        }

    override fun beginProvision(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.BEGIN_PROVISION, command)

    override fun activate(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.ACTIVATE, command)

    override fun makeReadOnly(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.MAKE_READ_ONLY, command)

    override fun resumeWrites(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.RESUME_WRITES, command)

    override fun suspend(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.SUSPEND, command)

    override fun beginMigration(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.BEGIN_MIGRATION, command)

    override fun finishMigration(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.FINISH_MIGRATION, command)

    override fun beginDeletion(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.BEGIN_DELETION, command)

    override fun tombstone(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.TOMBSTONE, command)

    override fun restoreAsNewEpoch(command: TenantRestoreAsNewEpoch): TenantControlResult =
        apply(TenantControlAction.RESTORE_NEW_EPOCH, command.command, command.placementVersion)

    override fun rotatePlacement(command: TenantControlCommand): TenantControlResult = apply(TenantControlAction.ROTATE_PLACEMENT, command)

    override fun updateRuntimeSettings(command: TenantRuntimeSettingsCommand): TenantRuntimeControlResult {
        val base = command.command
        val digest = referenceDigest.digest(base.ref)
        val action = RuntimeAction.UPDATE_SETTINGS
        val fingerprint = runtimeFingerprint(action, digest, base, command.patch.changes)
        return inControlTransaction {
            when (val claim = runtimeReceipt(base.operationId, digest, action, fingerprint)) {
                RuntimeReceiptClaim.Collision -> return@inControlTransaction TenantRuntimeControlResult.OperationCollision
                is RuntimeReceiptClaim.Existing -> return@inControlTransaction claim.receipt.toRuntimeResult()
                RuntimeReceiptClaim.New -> Unit
            }
            val current = lockRuntime(base.ref, digest)
            val result =
                when {
                    current == null -> {
                        TenantRuntimeControlResult.NotFound
                    }

                    current.lifecycle != TenantLifecycle.ACTIVE -> {
                        TenantRuntimeControlResult.Inactive(current.snapshot)
                    }

                    current.snapshot.controlVersion.value != base.expectedControlVersion.value ||
                        current.snapshot.runtimeVersion.value != base.expectedRuntimeVersion.value -> {
                        TenantRuntimeControlResult.VersionConflict(current.snapshot)
                    }

                    else -> {
                        applySettings(digest, base, current.snapshot, command.patch.changes)
                    }
                }
            completeRuntime(base.operationId, result)
            result
        }
    }

    override fun invalidateTenantCache(command: TenantRuntimeControlCommand): TenantRuntimeControlResult {
        val digest = referenceDigest.digest(command.ref)
        val action = RuntimeAction.INVALIDATE_CACHE
        val fingerprint = runtimeFingerprint(action, digest, command, emptyMap())
        return inControlTransaction {
            when (val claim = runtimeReceipt(command.operationId, digest, action, fingerprint)) {
                RuntimeReceiptClaim.Collision -> return@inControlTransaction TenantRuntimeControlResult.OperationCollision
                is RuntimeReceiptClaim.Existing -> return@inControlTransaction claim.receipt.toRuntimeResult()
                RuntimeReceiptClaim.New -> Unit
            }
            val current = lockRuntime(command.ref, digest)
            val result =
                when {
                    current == null -> {
                        TenantRuntimeControlResult.NotFound
                    }

                    current.lifecycle != TenantLifecycle.ACTIVE -> {
                        TenantRuntimeControlResult.Inactive(current.snapshot)
                    }

                    current.snapshot.controlVersion.value != command.expectedControlVersion.value ||
                        current.snapshot.runtimeVersion.value != command.expectedRuntimeVersion.value -> {
                        TenantRuntimeControlResult.VersionConflict(current.snapshot)
                    }

                    else -> {
                        invalidateCache(digest, command, current.snapshot)
                    }
                }
            completeRuntime(command.operationId, result)
            result
        }
    }

    override fun lookup(ref: TenantRef): TenantResolution? {
        val digest = referenceDigest.digest(ref)
        return dsl
            .selectFrom(TENANT)
            .where(TENANT.REF_DIGEST.eq(digest.copy()))
            .fetchOne()
            ?.toSnapshot(ref)
            ?.resolution
    }

    private fun apply(
        action: TenantControlAction,
        command: TenantControlCommand,
        restoredPlacement: Long? = null,
    ): TenantControlResult =
        inControlTransaction {
            val digest = referenceDigest.digest(command.ref)
            val fingerprint = fingerprint(action, digest, command.expectedVersion.value, restoredPlacement)
            when (val claim = receipt(command.operationId, digest, action, fingerprint)) {
                ReceiptClaim.Collision -> return@inControlTransaction TenantControlResult.OperationCollision
                is ReceiptClaim.Existing -> return@inControlTransaction claim.receipt.toResult(command.ref)
                ReceiptClaim.New -> Unit
            }

            val current = lock(command.ref, digest)
            val result =
                when {
                    current == null -> TenantControlResult.NotFound
                    current.version.value != command.expectedVersion.value -> TenantControlResult.VersionConflict(current)
                    else -> transition(action, command, digest, current, restoredPlacement)
                }
            complete(command.operationId, result)
            result
        }

    private fun applySettings(
        digest: TenantDigest,
        command: TenantRuntimeControlCommand,
        current: TenantRuntimeControlSnapshot,
        changes: Map<String, TenantSettingMutation>,
    ): TenantRuntimeControlResult.Applied {
        val now = clock.instant()
        changes.toSortedMap().forEach { (key, change) ->
            when (change) {
                TenantSettingMutation.Remove -> {
                    dsl
                        .deleteFrom(TENANT_RUNTIME_SETTING)
                        .where(TENANT_RUNTIME_SETTING.REF_DIGEST.eq(digest.copy()))
                        .and(TENANT_RUNTIME_SETTING.SETTING_KEY.eq(key))
                        .execute()
                }

                is TenantSettingMutation.Value -> {
                    upsertSetting(digest, key, change.bytes, null, now)
                }

                is TenantSettingMutation.SecretReference -> {
                    upsertSetting(digest, key, null, change.value, now)
                }
            }
        }
        val next =
            current.copy(
                settingsVersion = TenantSettingsVersion(Math.addExact(current.settingsVersion.value, 1)),
                runtimeVersion = TenantRuntimeVersion(Math.addExact(current.runtimeVersion.value, 1)),
            )
        val written =
            dsl
                .update(TENANT_RUNTIME_STATE)
                .set(TENANT_RUNTIME_STATE.SETTINGS_VERSION, next.settingsVersion.value)
                .set(TENANT_RUNTIME_STATE.ROW_VERSION, next.runtimeVersion.value)
                .set(TENANT_RUNTIME_STATE.UPDATED_AT, at(now))
                .where(TENANT_RUNTIME_STATE.REF_DIGEST.eq(digest.copy()))
                .and(TENANT_RUNTIME_STATE.ROW_VERSION.eq(current.runtimeVersion.value))
                .execute()
        check(written == 1) { "tenant runtime state changed while locked" }
        auditRuntime(RuntimeAction.UPDATE_SETTINGS, command.operationId, digest, next)
        return TenantRuntimeControlResult.Applied(next)
    }

    private fun invalidateCache(
        digest: TenantDigest,
        command: TenantRuntimeControlCommand,
        current: TenantRuntimeControlSnapshot,
    ): TenantRuntimeControlResult.Applied {
        val now = clock.instant()
        val next =
            current.copy(
                cacheGeneration = TenantCacheGeneration(Math.addExact(current.cacheGeneration.value, 1)),
                runtimeVersion = TenantRuntimeVersion(Math.addExact(current.runtimeVersion.value, 1)),
            )
        val written =
            dsl
                .update(TENANT_RUNTIME_STATE)
                .set(TENANT_RUNTIME_STATE.CACHE_GENERATION, next.cacheGeneration.value)
                .set(TENANT_RUNTIME_STATE.ROW_VERSION, next.runtimeVersion.value)
                .set(TENANT_RUNTIME_STATE.UPDATED_AT, at(now))
                .where(TENANT_RUNTIME_STATE.REF_DIGEST.eq(digest.copy()))
                .and(TENANT_RUNTIME_STATE.ROW_VERSION.eq(current.runtimeVersion.value))
                .execute()
        check(written == 1) { "tenant runtime state changed while locked" }
        auditRuntime(RuntimeAction.INVALIDATE_CACHE, command.operationId, digest, next)
        return TenantRuntimeControlResult.Applied(next)
    }

    private fun upsertSetting(
        digest: TenantDigest,
        key: String,
        canonical: ByteArray?,
        secretReference: String?,
        now: Instant,
    ) {
        val previous =
            dsl
                .select(TENANT_RUNTIME_SETTING.VALUE_VERSION)
                .from(TENANT_RUNTIME_SETTING)
                .where(TENANT_RUNTIME_SETTING.REF_DIGEST.eq(digest.copy()))
                .and(TENANT_RUNTIME_SETTING.SETTING_KEY.eq(key))
                .fetchOne(TENANT_RUNTIME_SETTING.VALUE_VERSION)
        val version = Math.addExact(previous ?: 0, 1)
        dsl
            .insertInto(TENANT_RUNTIME_SETTING)
            .set(TENANT_RUNTIME_SETTING.REF_DIGEST, digest.copy())
            .set(TENANT_RUNTIME_SETTING.SETTING_KEY, key)
            .set(TENANT_RUNTIME_SETTING.CANONICAL_VALUE, canonical?.copyOf())
            .set(TENANT_RUNTIME_SETTING.SECRET_REF, secretReference)
            .set(TENANT_RUNTIME_SETTING.VALUE_VERSION, version)
            .set(TENANT_RUNTIME_SETTING.UPDATED_AT, at(now))
            .onConflict(TENANT_RUNTIME_SETTING.REF_DIGEST, TENANT_RUNTIME_SETTING.SETTING_KEY)
            .doUpdate()
            .set(TENANT_RUNTIME_SETTING.CANONICAL_VALUE, canonical?.copyOf())
            .set(TENANT_RUNTIME_SETTING.SECRET_REF, secretReference)
            .set(TENANT_RUNTIME_SETTING.VALUE_VERSION, version)
            .set(TENANT_RUNTIME_SETTING.UPDATED_AT, at(now))
            .execute()
    }

    private fun transition(
        action: TenantControlAction,
        command: TenantControlCommand,
        digest: TenantDigest,
        current: TenantControlSnapshot,
        restoredPlacement: Long?,
    ): TenantControlResult {
        val nextLifecycle =
            TenantLifecycleGraph.target(action, current.resolution.lifecycle)
                ?: return TenantControlResult.TransitionRefused(current)
        if (action == TenantControlAction.ACTIVATE && !provisioning.allows(current)) {
            return TenantControlResult.ProvisioningIncomplete(current)
        }
        val nextEpoch =
            if (action == TenantControlAction.RESTORE_NEW_EPOCH) {
                TenantEpoch(Math.addExact(current.resolution.epoch.value, 1))
            } else {
                current.resolution.epoch
            }
        val nextPlacement =
            when (action) {
                TenantControlAction.RESTORE_NEW_EPOCH -> {
                    requireNotNull(restoredPlacement) { "restore has a placement version" }
                    require(restoredPlacement > current.resolution.placementVersion) {
                        "a restored tenant placement version advances its tombstone placement"
                    }
                    restoredPlacement
                }

                TenantControlAction.ROTATE_PLACEMENT -> {
                    Math.addExact(current.resolution.placementVersion, 1)
                }

                else -> {
                    current.resolution.placementVersion
                }
            }
        val next =
            TenantControlSnapshot(
                TenantResolution(command.ref, nextLifecycle, nextEpoch, nextPlacement),
                TenantControlVersion(Math.addExact(current.version.value, 1)),
            )
        val now = clock.instant()
        val written =
            dsl
                .update(TENANT)
                .set(TENANT.LIFECYCLE, wire(nextLifecycle))
                .set(TENANT.EPOCH, nextEpoch.value)
                .set(TENANT.PLACEMENT_VERSION, nextPlacement)
                .set(TENANT.ROW_VERSION, next.version.value)
                .set(TENANT.UPDATED_AT, at(now))
                .where(TENANT.REF_DIGEST.eq(digest.copy()))
                .and(TENANT.ROW_VERSION.eq(current.version.value))
                .execute()
        if (written == 0) return TenantControlResult.VersionConflict(checkNotNull(lock(command.ref, digest)))
        appendTransition(action, command.operationId, digest, current.resolution.lifecycle, next, command.expectedVersion.value, now)
        audit(action, command.operationId, digest, current.resolution.lifecycle, next)
        return TenantControlResult.Transitioned(next)
    }

    /** Locks lifecycle first and runtime state second, matching every runtime command's lock order. */
    private fun lockRuntime(
        ref: TenantRef,
        digest: TenantDigest,
    ): LockedRuntime? {
        val tenant =
            dsl
                .selectFrom(TENANT)
                .where(TENANT.REF_DIGEST.eq(digest.copy()))
                .forUpdate()
                .fetchOne()
                ?: return null
        val runtime =
            checkNotNull(
                dsl
                    .selectFrom(TENANT_RUNTIME_STATE)
                    .where(TENANT_RUNTIME_STATE.REF_DIGEST.eq(digest.copy()))
                    .forUpdate()
                    .fetchOne(),
            ) { "tenant runtime state is missing" }
        check(runtime.epoch == tenant.epoch) { "tenant runtime epoch does not match lifecycle state" }
        return LockedRuntime(lifecycle(tenant.lifecycle), runtime.toRuntimeSnapshot(tenant))
    }

    private fun TenantRuntimeStateRecord.toRuntimeSnapshot(tenant: TenantRecord): TenantRuntimeControlSnapshot =
        TenantRuntimeControlSnapshot(
            TenantEpoch(epoch),
            TenantControlVersion(tenant.rowVersion),
            TenantCacheGeneration(cacheGeneration),
            TenantSettingsVersion(settingsVersion),
            TenantRuntimeVersion(rowVersion),
        )

    private fun runtimeReceipt(
        operationId: TenantOperationId,
        digest: TenantDigest,
        action: RuntimeAction,
        fingerprint: ByteArray,
    ): RuntimeReceiptClaim {
        val claimed =
            dsl
                .insertInto(TENANT_RUNTIME_OPERATION_RECEIPT)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.OPERATION_ID, operationId.value)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.FINGERPRINT, fingerprint)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.REF_DIGEST, digest.copy())
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.ACTION, action.wire)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.OUTCOME, RUNTIME_OUTCOME_NOT_FOUND)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.COMPLETED_AT, at(clock.instant()))
                .onConflict(TENANT_RUNTIME_OPERATION_RECEIPT.OPERATION_ID)
                .doNothing()
                .execute()
        if (claimed == 1) return RuntimeReceiptClaim.New
        val prior =
            checkNotNull(
                dsl
                    .selectFrom(TENANT_RUNTIME_OPERATION_RECEIPT)
                    .where(TENANT_RUNTIME_OPERATION_RECEIPT.OPERATION_ID.eq(operationId.value))
                    .fetchOne(),
            ) { "tenant runtime operation receipt disappeared" }
        return if (
            !MessageDigest.isEqual(prior.fingerprint, fingerprint) ||
            !MessageDigest.isEqual(prior.refDigest, digest.copy()) ||
            prior.action != action.wire
        ) {
            RuntimeReceiptClaim.Collision
        } else {
            RuntimeReceiptClaim.Existing(prior)
        }
    }

    private fun TenantRuntimeOperationReceiptRecord.toRuntimeResult(): TenantRuntimeControlResult {
        val snapshot =
            epoch?.let {
                TenantRuntimeControlSnapshot(
                    TenantEpoch(it),
                    TenantControlVersion(requireNotNull(controlVersion)),
                    TenantCacheGeneration(requireNotNull(cacheGeneration)),
                    TenantSettingsVersion(requireNotNull(settingsVersion)),
                    TenantRuntimeVersion(requireNotNull(runtimeVersion)),
                )
            }
        return when (outcome) {
            RUNTIME_OUTCOME_APPLIED -> TenantRuntimeControlResult.Applied(requireNotNull(snapshot))
            RUNTIME_OUTCOME_VERSION_CONFLICT -> TenantRuntimeControlResult.VersionConflict(requireNotNull(snapshot))
            RUNTIME_OUTCOME_INACTIVE -> TenantRuntimeControlResult.Inactive(requireNotNull(snapshot))
            RUNTIME_OUTCOME_NOT_FOUND -> TenantRuntimeControlResult.NotFound
            else -> error("tenant runtime operation receipt has unknown outcome $outcome")
        }
    }

    private fun completeRuntime(
        operationId: TenantOperationId,
        result: TenantRuntimeControlResult,
    ) {
        val snapshot = result.runtimeSnapshotOrNull()
        val written =
            dsl
                .update(TENANT_RUNTIME_OPERATION_RECEIPT)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.OUTCOME, result.runtimeWire())
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.EPOCH, snapshot?.epoch?.value)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.CONTROL_VERSION, snapshot?.controlVersion?.value)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.CACHE_GENERATION, snapshot?.cacheGeneration?.value)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.SETTINGS_VERSION, snapshot?.settingsVersion?.value)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.RUNTIME_VERSION, snapshot?.runtimeVersion?.value)
                .set(TENANT_RUNTIME_OPERATION_RECEIPT.COMPLETED_AT, at(clock.instant()))
                .where(TENANT_RUNTIME_OPERATION_RECEIPT.OPERATION_ID.eq(operationId.value))
                .execute()
        check(written == 1) { "tenant runtime operation receipt was not claimed" }
    }

    private fun auditRuntime(
        action: RuntimeAction,
        operationId: TenantOperationId,
        digest: TenantDigest,
        snapshot: TenantRuntimeControlSnapshot,
    ) {
        audit.record(
            AuditEvent(
                TenantControlAudit.RUNTIME,
                AuditOutcome.OK,
                digest.toString(),
                AuditDetail.of(
                    "action" to action.wire,
                    "operation" to operationId.value.toString(),
                    "epoch" to snapshot.epoch.value,
                    "control_version" to snapshot.controlVersion.value,
                    "runtime_version" to snapshot.runtimeVersion.value,
                    "cache_generation" to snapshot.cacheGeneration.value,
                    "settings_version" to snapshot.settingsVersion.value,
                ),
            ),
        )
    }

    private fun runtimeFingerprint(
        action: RuntimeAction,
        digest: TenantDigest,
        command: TenantRuntimeControlCommand,
        changes: Map<String, TenantSettingMutation>,
    ): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(RUNTIME_FINGERPRINT_DOMAIN)
            update(0)
            update(action.wire.toByteArray(Charsets.UTF_8))
            update(0)
            update(digest.copy())
            update(long(command.expectedControlVersion.value))
            update(long(command.expectedRuntimeVersion.value))
            changes.toSortedMap().forEach { (key, change) ->
                update(key.toByteArray(Charsets.UTF_8))
                update(0)
                when (change) {
                    TenantSettingMutation.Remove -> {
                        update(0)
                    }

                    is TenantSettingMutation.Value -> {
                        update(1)
                        update(change.bytes)
                    }

                    is TenantSettingMutation.SecretReference -> {
                        update(2)
                        update(change.value.toByteArray(Charsets.UTF_8))
                    }
                }
                update(0)
            }
            digest()
        }

    /** Claims a receipt before the row lock. A losing retry observes the first committed outcome. */
    private fun receipt(
        operationId: TenantOperationId,
        digest: TenantDigest,
        action: TenantControlAction,
        fingerprint: ByteArray,
    ): ReceiptClaim {
        val claimed =
            dsl
                .insertInto(TENANT_OPERATION_RECEIPT)
                .set(TENANT_OPERATION_RECEIPT.OPERATION_ID, operationId.value)
                .set(TENANT_OPERATION_RECEIPT.FINGERPRINT, fingerprint)
                .set(TENANT_OPERATION_RECEIPT.REF_DIGEST, digest.copy())
                .set(TENANT_OPERATION_RECEIPT.ACTION, wire(action))
                // This provisional valid outcome exists only inside this transaction and is overwritten before commit.
                .set(TENANT_OPERATION_RECEIPT.OUTCOME, OUTCOME_NOT_FOUND)
                .set(TENANT_OPERATION_RECEIPT.COMPLETED_AT, at(clock.instant()))
                .onConflict(TENANT_OPERATION_RECEIPT.OPERATION_ID)
                .doNothing()
                .execute()
        if (claimed == 1) return ReceiptClaim.New
        val prior =
            checkNotNull(
                dsl
                    .selectFrom(TENANT_OPERATION_RECEIPT)
                    .where(TENANT_OPERATION_RECEIPT.OPERATION_ID.eq(operationId.value))
                    .fetchOne(),
            ) { "tenant operation receipt disappeared" }
        return if (
            !MessageDigest.isEqual(prior.fingerprint, fingerprint) ||
            !MessageDigest.isEqual(prior.refDigest, digest.copy()) ||
            prior.action != wire(action)
        ) {
            ReceiptClaim.Collision
        } else {
            ReceiptClaim.Existing(prior)
        }
    }

    private fun TenantOperationReceiptRecord.toResult(ref: TenantRef): TenantControlResult {
        val snapshot =
            lifecycle?.let {
                TenantControlSnapshot(
                    TenantResolution(ref, lifecycle(it), TenantEpoch(requireNotNull(epoch)), requireNotNull(placementVersion)),
                    TenantControlVersion(requireNotNull(rowVersion)),
                )
            }
        return when (outcome) {
            OUTCOME_CREATED -> TenantControlResult.Created(requireNotNull(snapshot))
            OUTCOME_TRANSITIONED -> TenantControlResult.Transitioned(requireNotNull(snapshot))
            OUTCOME_VERSION_CONFLICT -> TenantControlResult.VersionConflict(requireNotNull(snapshot))
            OUTCOME_NOT_FOUND -> TenantControlResult.NotFound
            OUTCOME_TRANSITION_REFUSED -> TenantControlResult.TransitionRefused(requireNotNull(snapshot))
            OUTCOME_PROVISIONING_INCOMPLETE -> TenantControlResult.ProvisioningIncomplete(requireNotNull(snapshot))
            else -> error("tenant operation receipt has unknown outcome $outcome")
        }
    }

    private fun complete(
        operationId: TenantOperationId,
        result: TenantControlResult,
    ) {
        val snapshot = result.snapshotOrNull()
        val written =
            dsl
                .update(TENANT_OPERATION_RECEIPT)
                .set(TENANT_OPERATION_RECEIPT.OUTCOME, result.wire())
                .set(TENANT_OPERATION_RECEIPT.LIFECYCLE, snapshot?.resolution?.lifecycle?.let(::wire))
                .set(TENANT_OPERATION_RECEIPT.EPOCH, snapshot?.resolution?.epoch?.value)
                .set(TENANT_OPERATION_RECEIPT.PLACEMENT_VERSION, snapshot?.resolution?.placementVersion)
                .set(TENANT_OPERATION_RECEIPT.ROW_VERSION, snapshot?.version?.value)
                .set(TENANT_OPERATION_RECEIPT.COMPLETED_AT, at(clock.instant()))
                .where(TENANT_OPERATION_RECEIPT.OPERATION_ID.eq(operationId.value))
                .execute()
        check(written == 1) { "tenant operation receipt was not claimed" }
    }

    private fun lock(
        ref: TenantRef,
        digest: TenantDigest,
    ): TenantControlSnapshot? =
        dsl
            .selectFrom(TENANT)
            .where(TENANT.REF_DIGEST.eq(digest.copy()))
            .forUpdate()
            .fetchOne()
            ?.toSnapshot(ref)

    private fun TenantRecord.toSnapshot(ref: TenantRef): TenantControlSnapshot =
        TenantControlSnapshot(
            TenantResolution(ref, lifecycle(lifecycle), TenantEpoch(epoch), placementVersion),
            TenantControlVersion(rowVersion),
        )

    private fun appendTransition(
        action: TenantControlAction,
        operationId: TenantOperationId,
        digest: TenantDigest,
        from: TenantLifecycle?,
        next: TenantControlSnapshot,
        expectedVersion: Long,
        now: Instant,
    ) {
        dsl
            .insertInto(TENANT_TRANSITION)
            .set(TENANT_TRANSITION.ID, ids.next())
            .set(TENANT_TRANSITION.OPERATION_ID, operationId.value)
            .set(TENANT_TRANSITION.REF_DIGEST, digest.copy())
            .set(TENANT_TRANSITION.ACTION, wire(action))
            .set(TENANT_TRANSITION.FROM_LIFECYCLE, from?.let(::wire))
            .set(TENANT_TRANSITION.TO_LIFECYCLE, wire(next.resolution.lifecycle))
            .set(TENANT_TRANSITION.EXPECTED_VERSION, expectedVersion)
            .set(TENANT_TRANSITION.NEW_VERSION, next.version.value)
            .set(TENANT_TRANSITION.EPOCH, next.resolution.epoch.value)
            .set(TENANT_TRANSITION.PLACEMENT_VERSION, next.resolution.placementVersion)
            .set(TENANT_TRANSITION.OCCURRED_AT, at(now))
            .execute()
    }

    private fun audit(
        action: TenantControlAction,
        operationId: TenantOperationId,
        digest: TenantDigest,
        from: TenantLifecycle?,
        next: TenantControlSnapshot,
    ) {
        audit.record(
            AuditEvent(
                TenantControlAudit.TRANSITION,
                AuditOutcome.OK,
                digest.toString(),
                AuditDetail.of(
                    "action" to wire(action),
                    "from" to (from?.let(::wire) ?: "none"),
                    "to" to wire(next.resolution.lifecycle),
                    "epoch" to next.resolution.epoch.value,
                    "operation" to operationId.value.toString(),
                    "placement_version" to next.resolution.placementVersion,
                    "version" to next.version.value,
                ),
            ),
        )
    }

    private fun fingerprint(
        action: TenantControlAction,
        digest: TenantDigest,
        expectedVersion: Long,
        placement: Long?,
    ): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(FINGERPRINT_DOMAIN)
            update(0)
            update(wire(action).toByteArray(Charsets.UTF_8))
            update(0)
            update(digest.copy())
            update(long(expectedVersion))
            update(long(placement ?: NO_PLACEMENT))
            digest()
        }

    private fun <T> inControlTransaction(block: () -> T): T = checkNotNull(transactions.execute { block() })

    private companion object {
        const val OUTCOME_CREATED: String = "created"
        const val OUTCOME_TRANSITIONED: String = "transitioned"
        const val OUTCOME_VERSION_CONFLICT: String = "version_conflict"
        const val OUTCOME_NOT_FOUND: String = "not_found"
        const val OUTCOME_TRANSITION_REFUSED: String = "transition_refused"
        const val OUTCOME_PROVISIONING_INCOMPLETE: String = "provisioning_incomplete"
        const val RUNTIME_OUTCOME_APPLIED: String = "applied"
        const val RUNTIME_OUTCOME_VERSION_CONFLICT: String = "version_conflict"
        const val RUNTIME_OUTCOME_NOT_FOUND: String = "not_found"
        const val RUNTIME_OUTCOME_INACTIVE: String = "inactive"
        const val NO_PLACEMENT: Long = -1
        val FINGERPRINT_DOMAIN: ByteArray = "rain.tenancy.operation.v1".toByteArray(Charsets.UTF_8)
        val RUNTIME_FINGERPRINT_DOMAIN: ByteArray = "rain.tenancy.runtime-operation.v1".toByteArray(Charsets.UTF_8)
    }

    private sealed interface ReceiptClaim {
        data object New : ReceiptClaim

        data object Collision : ReceiptClaim

        data class Existing(
            val receipt: TenantOperationReceiptRecord,
        ) : ReceiptClaim
    }

    private data class LockedRuntime(
        val lifecycle: TenantLifecycle,
        val snapshot: TenantRuntimeControlSnapshot,
    )

    private enum class RuntimeAction(
        val wire: String,
    ) {
        UPDATE_SETTINGS("update_runtime_settings"),
        INVALIDATE_CACHE("invalidate_tenant_cache"),
    }

    private sealed interface RuntimeReceiptClaim {
        data object New : RuntimeReceiptClaim

        data object Collision : RuntimeReceiptClaim

        data class Existing(
            val receipt: TenantRuntimeOperationReceiptRecord,
        ) : RuntimeReceiptClaim
    }
}

private fun TenantControlResult.snapshotOrNull(): TenantControlSnapshot? =
    when (this) {
        is TenantControlResult.Created -> snapshot

        is TenantControlResult.Transitioned -> snapshot

        is TenantControlResult.VersionConflict -> current

        is TenantControlResult.TransitionRefused -> current

        is TenantControlResult.ProvisioningIncomplete -> current

        TenantControlResult.NotFound,
        TenantControlResult.OperationCollision,
        -> null
    }

private fun TenantControlResult.wire(): String =
    when (this) {
        is TenantControlResult.Created -> "created"
        is TenantControlResult.Transitioned -> "transitioned"
        is TenantControlResult.VersionConflict -> "version_conflict"
        TenantControlResult.NotFound -> "not_found"
        is TenantControlResult.TransitionRefused -> "transition_refused"
        is TenantControlResult.ProvisioningIncomplete -> "provisioning_incomplete"
        TenantControlResult.OperationCollision -> error("an operation collision has no receipt outcome")
    }

private fun TenantRuntimeControlResult.runtimeSnapshotOrNull(): TenantRuntimeControlSnapshot? =
    when (this) {
        is TenantRuntimeControlResult.Applied -> snapshot

        is TenantRuntimeControlResult.VersionConflict -> current

        is TenantRuntimeControlResult.Inactive -> current

        TenantRuntimeControlResult.NotFound,
        TenantRuntimeControlResult.OperationCollision,
        -> null
    }

private fun TenantRuntimeControlResult.runtimeWire(): String =
    when (this) {
        is TenantRuntimeControlResult.Applied -> "applied"
        is TenantRuntimeControlResult.VersionConflict -> "version_conflict"
        is TenantRuntimeControlResult.Inactive -> "inactive"
        TenantRuntimeControlResult.NotFound -> "not_found"
        TenantRuntimeControlResult.OperationCollision -> error("an operation collision has no receipt outcome")
    }

private fun wire(value: TenantLifecycle): String = value.name.lowercase()

private fun wire(value: TenantControlAction): String = value.name.lowercase()

private fun lifecycle(value: String): TenantLifecycle = TenantLifecycle.entries.single { wire(it) == value }

private fun at(value: Instant) = value.atOffset(ZoneOffset.UTC)

private fun long(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
