package com.gd.rain.tenancy.provisioning

import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.control.TenantControlSnapshot
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A fenced provisioning target; it carries no control-plane mutation or raw provisioning credential. */
public data class TenantProvisioningTarget(
    public val ref: TenantRef,
    public val epoch: TenantEpoch,
    public val placementVersion: Long,
) {
    init {
        require(placementVersion > 0) { "tenant provisioning placement version is positive" }
    }

    internal companion object {
        fun from(resolution: TenantResolution): TenantProvisioningTarget =
            TenantProvisioningTarget(resolution.ref, resolution.epoch, resolution.placementVersion)
    }
}

/** Opaque claim fence a step passes to its external idempotency protocol. */
public class TenantProvisioningFence internal constructor(
    private val value: Long,
    private val token: UUID,
) {
    internal fun value(): Long = value

    internal fun token(): UUID = token

    override fun toString(): String = "tenant-provisioning-fence[opaque]"
}

/** The only input to an application provisioning step. It cannot activate, mutate lifecycle, or retrieve a raw credential. */
public data class TenantProvisioningContext internal constructor(
    public val target: TenantProvisioningTarget,
    public val fence: TenantProvisioningFence,
)

/** A stable, explicit extension point for one idempotent provisioning prerequisite. */
public interface TenantProvisioningStep {
    /** Stable identifier used in durable state, ordering and deployment validation. */
    public val id: String

    /** Increment when the durable semantic contract of this exact step changes. */
    public val revision: Int get() = 1

    /** Step ids that must have completed before this step may run. */
    public val after: Set<String> get() = emptySet()

    /** Step ids that must run after this one. */
    public val before: Set<String> get() = emptySet()

    /**
     * Performs one idempotent external prerequisite. A retryable/closed exception records only
     * its bounded code; no exception detail enters the durable tenant control plane.
     */
    public fun run(context: TenantProvisioningContext): Unit
}

/** A bounded closed failure code from an application provisioning step. */
public open class TenantProvisioningStepException(
    public val code: String,
    message: String = "tenant provisioning step failed",
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    init {
        require(CODE.matches(code)) { "tenant provisioning failure code is not stable" }
    }

    internal companion object {
        val CODE: Regex = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    }
}

/** A failure that may be retried with the same target and a new durable fence. */
public class TenantProvisioningRetryableException(
    code: String,
    message: String = "tenant provisioning step is temporarily unavailable",
    cause: Throwable? = null,
) : TenantProvisioningStepException(code, message, cause)

/** A closed prerequisite failure. It quarantines the workflow; it never triggers automatic DROP compensation. */
public class TenantProvisioningClosedException(
    code: String,
    message: String = "tenant provisioning step failed permanently",
    cause: Throwable? = null,
) : TenantProvisioningStepException(code, message, cause)

/** Finite worker-owned bounds for one workflow pass. */
public data class TenantProvisioningRunSpec(
    public val leaseDuration: Duration,
    public val maximumSteps: Int,
) {
    init {
        require(leaseDuration > Duration.ZERO) { "tenant provisioning lease duration is positive" }
        require(maximumSteps > 0) { "tenant provisioning maximum steps is positive" }
    }
}

/** The explicit result consumed by a durable job adapter; it contains no reference or exception detail. */
public sealed interface TenantProvisioningRunResult {
    public data object Complete : TenantProvisioningRunResult

    public data class Waiting(
        public val step: String,
    ) : TenantProvisioningRunResult

    public data class Quarantined(
        public val step: String,
        public val failureCode: String,
    ) : TenantProvisioningRunResult
}

/** One durable ownership result for a provisioning step. */
public sealed interface TenantProvisioningClaim {
    public data object Complete : TenantProvisioningClaim

    public data class Busy(
        public val step: String,
    ) : TenantProvisioningClaim

    public data class Quarantined(
        public val step: String,
        public val failureCode: String,
    ) : TenantProvisioningClaim

    public data class Acquired(
        public val fence: TenantProvisioningFence,
    ) : TenantProvisioningClaim
}

/**
 * Durable state port. Every method owns a short control-plane transaction; a custom step's actual
 * external work runs outside it and can be fenced by [TenantProvisioningFence].
 */
public interface TenantProvisioningLedger {
    public fun ensure(
        target: TenantProvisioningTarget,
        fingerprint: ByteArray,
        steps: List<String>,
        now: Instant,
    ): Unit

    public fun claim(
        target: TenantProvisioningTarget,
        step: String,
        leaseDuration: Duration,
        now: Instant,
    ): TenantProvisioningClaim

    /** Returns false when the lease was fenced by another worker before this completion. */
    public fun succeed(
        target: TenantProvisioningTarget,
        step: String,
        fence: TenantProvisioningFence,
        now: Instant,
    ): Boolean

    /** Returns false when the lease was fenced by another worker before this retry record. */
    public fun retry(
        target: TenantProvisioningTarget,
        step: String,
        fence: TenantProvisioningFence,
        failureCode: String,
        now: Instant,
    ): Boolean

    /** Returns false when the lease was fenced by another worker before this quarantine record. */
    public fun quarantine(
        target: TenantProvisioningTarget,
        step: String,
        fence: TenantProvisioningFence,
        failureCode: String,
        now: Instant,
    ): Boolean

    /** True only when every declared step for this exact epoch has durably completed. */
    public fun allSucceeded(
        target: TenantProvisioningTarget,
        fingerprint: ByteArray,
        steps: List<String>,
    ): Boolean
}

/** Gate passed to the lifecycle control plane so custom provisioning cannot be bypassed by a direct activate call. */
public fun interface TenantProvisioningActivationGate {
    public fun allows(snapshot: TenantControlSnapshot): Boolean

    public companion object {
        /** Valid only when no provisioning workflow is installed. */
        public val NONE: TenantProvisioningActivationGate = TenantProvisioningActivationGate { true }
    }
}

/**
 * Ordered durable orchestration over application-owned steps. It intentionally contains no
 * database create/drop, secret writer, scheduler, or lifecycle mutation capability; those remain
 * separate built-in/application steps and the closed control-plane command.
 */
public class TenantProvisioningWorkflow(
    steps: Collection<TenantProvisioningStep>,
    private val ledger: TenantProvisioningLedger,
    private val clock: Clock,
) : TenantProvisioningActivationGate {
    private val ordered: List<TenantProvisioningStep> = order(steps)
    private val stepIds: List<String> = ordered.map(TenantProvisioningStep::id)
    private val fingerprint: ByteArray = fingerprint(ordered)

    /** Runs at most [TenantProvisioningRunSpec.maximumSteps] acquired steps and never activates the tenant itself. */
    public fun run(
        target: TenantProvisioningTarget,
        spec: TenantProvisioningRunSpec,
    ): TenantProvisioningRunResult {
        val now = clock.instant()
        ledger.ensure(target, fingerprint.copyOf(), stepIds, now)
        var started = 0
        for (step in ordered) {
            if (started == spec.maximumSteps) return TenantProvisioningRunResult.Waiting(step.id)
            when (val claim = ledger.claim(target, step.id, spec.leaseDuration, clock.instant())) {
                TenantProvisioningClaim.Complete -> {
                    continue
                }

                is TenantProvisioningClaim.Busy -> {
                    return TenantProvisioningRunResult.Waiting(claim.step)
                }

                is TenantProvisioningClaim.Quarantined -> {
                    return TenantProvisioningRunResult.Quarantined(claim.step, claim.failureCode)
                }

                is TenantProvisioningClaim.Acquired -> {
                    started += 1
                    val result = execute(step, target, claim.fence)
                    if (result != null) return result
                }
            }
        }
        return if (ledger.allSucceeded(
                target,
                fingerprint.copyOf(),
                stepIds,
            )
        ) {
            TenantProvisioningRunResult.Complete
        } else {
            TenantProvisioningRunResult.Waiting(stepIds.last())
        }
    }

    /** Exact graph + revision fingerprint prevents a changed deployment from reinterpreting an in-flight epoch. */
    public fun fingerprint(): ByteArray = fingerprint.copyOf()

    override fun allows(snapshot: TenantControlSnapshot): Boolean {
        val target = TenantProvisioningTarget.from(snapshot.resolution)
        return ledger.allSucceeded(target, fingerprint.copyOf(), stepIds)
    }

    private fun execute(
        step: TenantProvisioningStep,
        target: TenantProvisioningTarget,
        fence: TenantProvisioningFence,
    ): TenantProvisioningRunResult? =
        try {
            step.run(TenantProvisioningContext(target, fence))
            if (ledger.succeed(target, step.id, fence, clock.instant())) null else TenantProvisioningRunResult.Waiting(step.id)
        } catch (failure: TenantProvisioningClosedException) {
            if (ledger.quarantine(target, step.id, fence, failure.code, clock.instant())) {
                TenantProvisioningRunResult.Quarantined(step.id, failure.code)
            } else {
                TenantProvisioningRunResult.Waiting(step.id)
            }
        } catch (failure: TenantProvisioningRetryableException) {
            ledger.retry(target, step.id, fence, failure.code, clock.instant())
            TenantProvisioningRunResult.Waiting(step.id)
        } catch (_: Exception) {
            ledger.retry(target, step.id, fence, UNEXPECTED_FAILURE, clock.instant())
            TenantProvisioningRunResult.Waiting(step.id)
        }

    private fun order(steps: Collection<TenantProvisioningStep>): List<TenantProvisioningStep> {
        val byId = steps.associateBy(TenantProvisioningStep::id)
        require(byId.isNotEmpty()) { "a tenant provisioning workflow declares at least one step" }
        require(byId.size == steps.size) { "a tenant provisioning step id is declared more than once" }
        require(byId.values.all { STEP_ID.matches(it.id) && it.revision > 0 }) { "a tenant provisioning step id or revision is invalid" }
        val prerequisites = byId.mapValues { (_, step) -> step.after.toMutableSet() }
        byId.forEach { (id, step) ->
            step.before.forEach { later ->
                require(later in byId) { "tenant provisioning step $id names unknown dependency $later" }
                prerequisites.getValue(later) += id
            }
        }
        prerequisites.forEach { (id, required) ->
            require(required.all { it in byId }) { "tenant provisioning step $id names an unknown dependency" }
        }
        val remaining = prerequisites.mapValuesTo(linkedMapOf()) { (_, required) -> required.toMutableSet() }
        val ordered = mutableListOf<TenantProvisioningStep>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filterValues(Set<String>::isEmpty).keys.sorted()
            require(ready.isNotEmpty()) { "tenant provisioning step graph has a cycle" }
            ready.forEach { id ->
                ordered += byId.getValue(id)
                remaining.remove(id)
            }
            remaining.values.forEach { it.removeAll(ready.toSet()) }
        }
        return ordered
    }

    private fun fingerprint(steps: List<TenantProvisioningStep>): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update("rain.tenancy.provisioning.v1".toByteArray(Charsets.UTF_8))
            steps.forEach { step ->
                update(0)
                update(step.id.toByteArray(Charsets.UTF_8))
                update(0)
                update(step.revision.toString().toByteArray(Charsets.UTF_8))
                step.after.sorted().forEach { dependency ->
                    update(0)
                    update("after".toByteArray(Charsets.UTF_8))
                    update(0)
                    update(dependency.toByteArray(Charsets.UTF_8))
                }
                step.before.sorted().forEach { dependency ->
                    update(0)
                    update("before".toByteArray(Charsets.UTF_8))
                    update(0)
                    update(dependency.toByteArray(Charsets.UTF_8))
                }
            }
            digest()
        }

    private companion object {
        const val UNEXPECTED_FAILURE: String = "unexpected"
        val STEP_ID: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}
