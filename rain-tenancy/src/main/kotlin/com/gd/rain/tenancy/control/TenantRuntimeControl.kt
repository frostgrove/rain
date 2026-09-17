package com.gd.rain.tenancy.control

import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.cache.TenantCacheGeneration
import com.gd.rain.tenancy.settings.TenantSettingsPatch
import com.gd.rain.tenancy.settings.TenantSettingsVersion

/** Positive optimistic version of tenant runtime state, separate from lifecycle control version. */
@JvmInline
public value class TenantRuntimeVersion(
    public val value: Long,
) {
    init {
        require(value > 0) { "tenant runtime version is positive" }
    }
}

/** The version a runtime command observed; runtime state is never created implicitly by a mutation. */
@JvmInline
public value class ExpectedTenantRuntimeVersion(
    public val value: Long,
) {
    init {
        require(value > 0) { "expected tenant runtime version is positive" }
    }
}

/** Versioned state returned from a runtime command; it contains no raw tenant reference. */
public data class TenantRuntimeControlSnapshot(
    public val epoch: TenantEpoch,
    public val controlVersion: TenantControlVersion,
    public val cacheGeneration: TenantCacheGeneration,
    public val settingsVersion: TenantSettingsVersion,
    public val runtimeVersion: TenantRuntimeVersion,
)

/** Common fence for every runtime-state mutation. */
public data class TenantRuntimeControlCommand(
    public val ref: TenantRef,
    public val expectedControlVersion: ExpectedTenantControlVersion,
    public val expectedRuntimeVersion: ExpectedTenantRuntimeVersion,
    public val operationId: TenantOperationId,
)

/** A settings patch is typed and validated by its registered [TenantSettingsRegistry], never an arbitrary map. */
public data class TenantRuntimeSettingsCommand(
    public val command: TenantRuntimeControlCommand,
    public val patch: TenantSettingsPatch,
)

/** Idempotent outcome vocabulary for runtime state commands. */
public sealed interface TenantRuntimeControlResult {
    public data class Applied(
        public val snapshot: TenantRuntimeControlSnapshot,
    ) : TenantRuntimeControlResult

    public data class VersionConflict(
        public val current: TenantRuntimeControlSnapshot,
    ) : TenantRuntimeControlResult

    /** The tenant exists but is not active, so tuning it cannot change routable behavior. */
    public data class Inactive(
        public val current: TenantRuntimeControlSnapshot,
    ) : TenantRuntimeControlResult

    public data object NotFound : TenantRuntimeControlResult

    public data object OperationCollision : TenantRuntimeControlResult
}

/** Separate command port: lifecycle mutation does not grow a generic runtime-state upsert API. */
public interface TenantRuntimeControlPlane {
    public fun updateRuntimeSettings(command: TenantRuntimeSettingsCommand): TenantRuntimeControlResult

    /** Advances an opaque cache generation; it never scans a provider's arbitrary keys. */
    public fun invalidateTenantCache(command: TenantRuntimeControlCommand): TenantRuntimeControlResult
}
