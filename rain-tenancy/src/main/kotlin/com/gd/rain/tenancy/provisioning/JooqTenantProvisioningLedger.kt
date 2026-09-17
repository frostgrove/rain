package com.gd.rain.tenancy.provisioning

import com.gd.rain.tenancy.TenantDigest
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.control.TenantReferenceDigest
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.transaction.support.TransactionOperations
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL provisioning ledger. It stores reference digests and bounded failure codes only;
 * custom steps receive neither control-plane credentials nor a lifecycle mutation API.
 */
public class JooqTenantProvisioningLedger(
    private val dsl: DSLContext,
    private val transactions: TransactionOperations,
    private val references: TenantReferenceDigest,
    private val tokens: () -> UUID = UUID::randomUUID,
) : TenantProvisioningLedger {
    override fun ensure(
        target: TenantProvisioningTarget,
        fingerprint: ByteArray,
        steps: List<String>,
        now: Instant,
    ) {
        require(fingerprint.size == FINGERPRINT_BYTES) { "tenant provisioning workflow fingerprint has an unknown format" }
        require(steps.isNotEmpty() && steps.distinct().size == steps.size && steps.all(STEP_ID_PATTERN::matches)) {
            "tenant provisioning workflow step ids are invalid"
        }
        inTransaction {
            val digest = references.digest(target.ref)
            val inserted =
                dsl
                    .insertInto(WORKFLOW)
                    .set(WORKFLOW_REF_DIGEST, digest.copy())
                    .set(WORKFLOW_EPOCH, target.epoch.value)
                    .set(WORKFLOW_PLACEMENT_VERSION, target.placementVersion)
                    .set(WORKFLOW_FINGERPRINT, fingerprint.copyOf())
                    .set(WORKFLOW_CREATED_AT, timestamp(now))
                    .onConflict(WORKFLOW_REF_DIGEST, WORKFLOW_EPOCH)
                    .doNothing()
                    .execute()
            if (inserted == 0) {
                val existing = checkNotNull(workflow(digest, target.epoch)) { "tenant provisioning workflow disappeared after conflict" }
                require(
                    existing.placementVersion == target.placementVersion &&
                        MessageDigest.isEqual(existing.fingerprint, fingerprint),
                ) { "tenant provisioning workflow changed during this tenant epoch" }
            }
            steps.forEach { step ->
                dsl
                    .insertInto(STEP)
                    .set(STEP_REF_DIGEST, digest.copy())
                    .set(STEP_EPOCH, target.epoch.value)
                    .set(STEP_ID, step)
                    .set(STEP_STATE, PENDING)
                    .set(STEP_FENCE, 0L)
                    .set(STEP_ATTEMPTS, 0)
                    .set(STEP_UPDATED_AT, timestamp(now))
                    .onConflict(STEP_REF_DIGEST, STEP_EPOCH, STEP_ID)
                    .doNothing()
                    .execute()
            }
        }
    }

    override fun claim(
        target: TenantProvisioningTarget,
        step: String,
        leaseDuration: Duration,
        now: Instant,
    ): TenantProvisioningClaim {
        require(STEP_ID_PATTERN.matches(step)) { "tenant provisioning step id is invalid" }
        require(leaseDuration > Duration.ZERO) { "tenant provisioning lease duration is positive" }
        return inTransaction {
            val digest = references.digest(target.ref)
            val current = checkNotNull(step(digest, target.epoch, step)) { "tenant provisioning step was not initialized" }
            when {
                current.state == SUCCEEDED -> {
                    TenantProvisioningClaim.Complete
                }

                current.state == FAILED -> {
                    TenantProvisioningClaim.Quarantined(step, checkNotNull(current.failureCode))
                }

                current.state == RUNNING && checkNotNull(current.leaseUntil).isAfter(now) -> {
                    TenantProvisioningClaim.Busy(step)
                }

                else -> {
                    val fence = Math.addExact(current.fence, 1)
                    val token = tokens().also { require(it != UUID(0, 0)) { "tenant provisioning lease token is not nil" } }
                    val written =
                        dsl
                            .update(STEP)
                            .set(STEP_STATE, RUNNING)
                            .set(STEP_FENCE, fence)
                            .set(STEP_LEASE_TOKEN, token)
                            .set(STEP_LEASE_UNTIL, timestamp(now.plus(leaseDuration)))
                            .set(STEP_ATTEMPTS, Math.addExact(current.attempts, 1))
                            .set(STEP_FAILURE_CODE, null as String?)
                            .set(STEP_UPDATED_AT, timestamp(now))
                            .where(STEP_REF_DIGEST.eq(digest.copy()))
                            .and(STEP_EPOCH.eq(target.epoch.value))
                            .and(STEP_ID.eq(step))
                            .and(STEP_FENCE.eq(current.fence))
                            .execute()
                    check(written == 1) { "tenant provisioning step changed while locked" }
                    TenantProvisioningClaim.Acquired(TenantProvisioningFence(fence, token))
                }
            }
        }
    }

    override fun succeed(
        target: TenantProvisioningTarget,
        step: String,
        fence: TenantProvisioningFence,
        now: Instant,
    ): Boolean = complete(target, step, fence, SUCCEEDED, null, now)

    override fun retry(
        target: TenantProvisioningTarget,
        step: String,
        fence: TenantProvisioningFence,
        failureCode: String,
        now: Instant,
    ): Boolean = complete(target, step, fence, PENDING, failureCode, now)

    override fun quarantine(
        target: TenantProvisioningTarget,
        step: String,
        fence: TenantProvisioningFence,
        failureCode: String,
        now: Instant,
    ): Boolean = complete(target, step, fence, FAILED, failureCode, now)

    override fun allSucceeded(
        target: TenantProvisioningTarget,
        fingerprint: ByteArray,
        steps: List<String>,
    ): Boolean {
        if (steps.isEmpty()) return true
        require(fingerprint.size == FINGERPRINT_BYTES) { "tenant provisioning workflow fingerprint has an unknown format" }
        require(
            steps.distinct().size == steps.size && steps.all(STEP_ID_PATTERN::matches),
        ) { "tenant provisioning workflow step ids are invalid" }
        val digest = references.digest(target.ref)
        val workflow = workflow(digest, target.epoch) ?: return false
        if (workflow.placementVersion != target.placementVersion || !MessageDigest.isEqual(workflow.fingerprint, fingerprint)) return false
        val succeeded =
            dsl
                .fetchCount(
                    STEP,
                    STEP_REF_DIGEST
                        .eq(digest.copy())
                        .and(STEP_EPOCH.eq(target.epoch.value))
                        .and(STEP_ID.`in`(steps))
                        .and(STEP_STATE.eq(SUCCEEDED)),
                )
        return succeeded == steps.size
    }

    private fun complete(
        target: TenantProvisioningTarget,
        step: String,
        fence: TenantProvisioningFence,
        state: String,
        failureCode: String?,
        now: Instant,
    ): Boolean {
        require(STEP_ID_PATTERN.matches(step)) { "tenant provisioning step id is invalid" }
        failureCode?.let { require(TenantProvisioningStepException.CODE.matches(it)) { "tenant provisioning failure code is invalid" } }
        val digest = references.digest(target.ref)
        return checkNotNull(
            transactions.execute {
                dsl
                    .update(STEP)
                    .set(STEP_STATE, state)
                    .set(STEP_LEASE_TOKEN, null as UUID?)
                    .set(STEP_LEASE_UNTIL, null as OffsetDateTime?)
                    .set(STEP_FAILURE_CODE, failureCode)
                    .set(STEP_UPDATED_AT, timestamp(now))
                    .where(STEP_REF_DIGEST.eq(digest.copy()))
                    .and(STEP_EPOCH.eq(target.epoch.value))
                    .and(STEP_ID.eq(step))
                    .and(STEP_STATE.eq(RUNNING))
                    .and(STEP_FENCE.eq(fence.value()))
                    .and(STEP_LEASE_TOKEN.eq(fence.token()))
                    .execute() == 1
            },
        )
    }

    private fun workflow(
        digest: TenantDigest,
        epoch: TenantEpoch,
    ): Workflow? =
        dsl
            .select(WORKFLOW_PLACEMENT_VERSION, WORKFLOW_FINGERPRINT)
            .from(WORKFLOW)
            .where(WORKFLOW_REF_DIGEST.eq(digest.copy()))
            .and(WORKFLOW_EPOCH.eq(epoch.value))
            .fetchOne { row ->
                Workflow(
                    checkNotNull(row[WORKFLOW_PLACEMENT_VERSION]) { "tenant provisioning workflow has no placement version" },
                    checkNotNull(row[WORKFLOW_FINGERPRINT]) { "tenant provisioning workflow has no fingerprint" },
                )
            }

    private fun step(
        digest: TenantDigest,
        epoch: TenantEpoch,
        id: String,
    ): StoredStep? =
        dsl
            .select(STEP_STATE, STEP_FENCE, STEP_LEASE_UNTIL, STEP_ATTEMPTS, STEP_FAILURE_CODE)
            .from(STEP)
            .where(STEP_REF_DIGEST.eq(digest.copy()))
            .and(STEP_EPOCH.eq(epoch.value))
            .and(STEP_ID.eq(id))
            .forUpdate()
            .fetchOne { row ->
                StoredStep(
                    checkNotNull(row[STEP_STATE]) { "tenant provisioning step has no state" },
                    checkNotNull(row[STEP_FENCE]) { "tenant provisioning step has no fence" },
                    row[STEP_LEASE_UNTIL]?.toInstant(),
                    checkNotNull(row[STEP_ATTEMPTS]) { "tenant provisioning step has no attempts" },
                    row[STEP_FAILURE_CODE],
                )
            }

    private fun <T> inTransaction(block: () -> T): T = checkNotNull(transactions.execute { block() })

    private data class Workflow(
        val placementVersion: Long,
        val fingerprint: ByteArray,
    )

    private data class StoredStep(
        val state: String,
        val fence: Long,
        val leaseUntil: Instant?,
        val attempts: Int,
        val failureCode: String?,
    )

    private companion object {
        const val FINGERPRINT_BYTES: Int = 32
        const val PENDING: String = "pending"
        const val RUNNING: String = "running"
        const val SUCCEEDED: String = "succeeded"
        const val FAILED: String = "failed"
        val STEP_ID_PATTERN: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
        val WORKFLOW: Table<Record> = DSL.table(DSL.name("rain_tenancy", "tenant_provision_workflow"))
        val WORKFLOW_REF_DIGEST: Field<ByteArray> = DSL.field(DSL.name("ref_digest"), ByteArray::class.java)
        val WORKFLOW_EPOCH: Field<Long> = DSL.field(DSL.name("tenant_epoch"), Long::class.java)
        val WORKFLOW_PLACEMENT_VERSION: Field<Long> = DSL.field(DSL.name("placement_version"), Long::class.java)
        val WORKFLOW_FINGERPRINT: Field<ByteArray> = DSL.field(DSL.name("workflow_fingerprint"), ByteArray::class.java)
        val WORKFLOW_CREATED_AT: Field<OffsetDateTime> = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)
        val STEP: Table<Record> = DSL.table(DSL.name("rain_tenancy", "tenant_provision_step"))
        val STEP_REF_DIGEST: Field<ByteArray> = DSL.field(DSL.name("ref_digest"), ByteArray::class.java)
        val STEP_EPOCH: Field<Long> = DSL.field(DSL.name("tenant_epoch"), Long::class.java)
        val STEP_ID: Field<String> = DSL.field(DSL.name("step_id"), String::class.java)
        val STEP_STATE: Field<String> = DSL.field(DSL.name("state"), String::class.java)
        val STEP_FENCE: Field<Long> = DSL.field(DSL.name("fence"), Long::class.java)
        val STEP_LEASE_TOKEN: Field<UUID> = DSL.field(DSL.name("lease_token"), UUID::class.java)
        val STEP_LEASE_UNTIL: Field<OffsetDateTime> = DSL.field(DSL.name("lease_until"), OffsetDateTime::class.java)
        val STEP_ATTEMPTS: Field<Int> = DSL.field(DSL.name("attempts"), Int::class.java)
        val STEP_FAILURE_CODE: Field<String> = DSL.field(DSL.name("last_failure_code"), String::class.java)
        val STEP_UPDATED_AT: Field<OffsetDateTime> = DSL.field(DSL.name("updated_at"), OffsetDateTime::class.java)
    }
}

private fun timestamp(value: Instant): OffsetDateTime = value.atOffset(ZoneOffset.UTC)
