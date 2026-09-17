package com.gd.rain.tenancy.jobs

import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.EnqueueOutcome
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobPayloadCodec
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.WorkQueue
import com.gd.rain.jobs.context.TenantBindingMode
import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantGrantVerifier
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.TenantUnit
import com.gd.rain.tenancy.control.TenantReferenceDigest
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.transaction.support.TransactionOperations
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A caller-minted, retryable identity for one tenant-local durable job order. */
@JvmInline
public value class TenantJobOutboxId(
    public val value: UUID,
) {
    init {
        require(value != UUID(0, 0)) { "tenant job outbox id is not nil" }
    }

    override fun toString(): String = "tenant-job-outbox-id[$value]"
}

/**
 * Exact already-serialized job payload. The producer owns canonicalization for its job contract;
 * this type preserves those bytes and their digest without parsing or reserializing them.
 */
public class TenantJobOutboxOrder(
    public val id: TenantJobOutboxId,
    public val definition: String,
    serializedPayload: ByteArray,
    public val options: EnqueueOptions,
) {
    private val serializedPayload: ByteArray = serializedPayload.copyOf()
    internal val payloadDigest: ByteArray = sha256(this.serializedPayload)

    init {
        require(JobDefinition.NAME.matches(definition)) { "tenant job outbox definition is not stable" }
        require(this.serializedPayload.size in 1..MAX_PAYLOAD_BYTES) {
            "tenant job outbox payload is 1..$MAX_PAYLOAD_BYTES bytes"
        }
    }

    /** Returns the exact durable bytes for inspection or a separately declared relay codec. */
    public fun serializedPayload(): ByteArray = serializedPayload.copyOf()

    override fun toString(): String = "tenant-job-outbox-order[$id, definition=$definition, payload=redacted]"

    private companion object {
        const val MAX_PAYLOAD_BYTES: Int = 1024 * 1024
    }
}

/** An opaque continuation through one tenant database's ordered pending rows. */
public data class TenantJobOutboxCursor(
    public val createdAt: Instant,
    public val id: TenantJobOutboxId,
)

/** A finite pull request. A relay never turns a tenant outbox into an unbounded scan. */
public data class TenantJobOutboxPage(
    public val limit: Int,
    public val after: TenantJobOutboxCursor? = null,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "tenant job outbox page limit is 1..$MAX_LIMIT" }
    }

    private companion object {
        const val MAX_LIMIT: Int = 1_000
    }
}

/** One pending or delivered source row. Payload bytes stay private to the jobs adapter in this module. */
public class TenantJobOutboxEntry internal constructor(
    public val id: TenantJobOutboxId,
    public val epoch: TenantEpoch,
    public val definition: String,
    payload: ByteArray,
    internal val payloadDigest: ByteArray,
    public val options: EnqueueOptions,
    public val createdAt: Instant,
    public val notBefore: Instant,
) {
    private val payload: ByteArray = payload.copyOf()

    internal fun payloadJson(): String = payload.toString(Charsets.UTF_8)

    internal fun hasSameContents(
        other: TenantJobOutboxOrder,
        epoch: TenantEpoch,
        notBefore: Instant,
    ): Boolean =
        this.epoch == epoch &&
            definition == other.definition &&
            MessageDigest.isEqual(payloadDigest, other.payloadDigest) &&
            options == other.options &&
            this.notBefore == notBefore

    override fun toString(): String = "tenant-job-outbox-entry[$id, definition=$definition, payload=redacted]"
}

/** The durable result of putting an order in the tenant transaction. */
public sealed interface TenantJobOutboxEnqueueResult {
    public data class Queued(
        public val entry: TenantJobOutboxEntry,
    ) : TenantJobOutboxEnqueueResult

    /** An exact repetition retains the original source record and never creates a second relay item. */
    public data class Repeated(
        public val entry: TenantJobOutboxEntry,
    ) : TenantJobOutboxEnqueueResult

    /** Reuse of an outbox id for another payload, options, epoch, or destination is closed. */
    public data object Collision : TenantJobOutboxEnqueueResult
}

/** One finite ordered source page. [next] resumes after the last returned source row. */
public data class TenantJobOutboxBatch(
    public val entries: List<TenantJobOutboxEntry>,
    public val next: TenantJobOutboxCursor?,
) {
    init {
        require(entries.isNotEmpty() || next == null) { "an empty tenant job outbox batch has no next cursor" }
    }
}

/** Result of making the local source row acknowledge one durable central invocation. */
public sealed interface TenantJobOutboxAcknowledgeResult {
    public data object Acknowledged : TenantJobOutboxAcknowledgeResult

    public data object Repeated : TenantJobOutboxAcknowledgeResult

    public data object Collision : TenantJobOutboxAcknowledgeResult
}

/**
 * Tenant-database outbox for work that must eventually enter the control-plane jobs queue.
 *
 * It is deliberately separate from [WorkQueue]: a tenant database and the control-plane jobs
 * ledger have no shared transaction. [enqueue] records the exact source intent in the current
 * tenant unit; a relay later claims a central receipt, enqueues there, and acknowledges this row
 * in a different tenant transaction.
 */
public interface TenantJobOutbox {
    public fun enqueue(
        unit: TenantUnit,
        order: TenantJobOutboxOrder,
    ): TenantJobOutboxEnqueueResult

    public fun pending(
        unit: TenantUnit,
        page: TenantJobOutboxPage,
    ): TenantJobOutboxBatch

    public fun acknowledge(
        unit: TenantUnit,
        entry: TenantJobOutboxEntry,
        invocation: UUID,
    ): TenantJobOutboxAcknowledgeResult
}

/**
 * PostgreSQL source adapter. Its table belongs to the tenant database catalogue, not
 * `rain_tenancy`: provisioners must install this DDL in every fenced tenant database before an
 * application uses database-mode durable effects.
 */
public class PostgresTenantJobOutbox : TenantJobOutbox {
    override fun enqueue(
        unit: TenantUnit,
        order: TenantJobOutboxOrder,
    ): TenantJobOutboxEnqueueResult {
        requireWritable(unit)
        val now = unit.clock.instant()
        val notBefore = now.plus(order.options.after)
        val written =
            unit.dsl
                .insertInto(TABLE)
                .set(ID, order.id.value)
                .set(EPOCH, unit.scope.epoch.value)
                .set(DEFINITION, order.definition)
                .set(PAYLOAD, order.serializedPayload())
                .set(PAYLOAD_DIGEST, order.payloadDigest.copyOf())
                .set(DEDUPE_MODE, dedupeMode(order.options.dedupe))
                .set(DEDUPE_KEY, dedupeKey(order.options.dedupe))
                .set(
                    PRIORITY,
                    order.options.priority.value
                        .toShort(),
                ).set(SUBJECT_KEY, order.options.subjectKey?.value)
                .set(CREATED_AT, timestamp(now))
                .set(NOT_BEFORE, timestamp(notBefore))
                .set(STATUS, PENDING)
                .onConflict(ID)
                .doNothing()
                .execute()
        val entry = checkNotNull(find(unit.dsl, order.id)) { "a tenant job outbox row disappeared after insert" }
        return when {
            written == 1 -> TenantJobOutboxEnqueueResult.Queued(entry)
            entry.hasSameContents(order, unit.scope.epoch, notBefore) -> TenantJobOutboxEnqueueResult.Repeated(entry)
            else -> TenantJobOutboxEnqueueResult.Collision
        }
    }

    override fun pending(
        unit: TenantUnit,
        page: TenantJobOutboxPage,
    ): TenantJobOutboxBatch {
        requireWritable(unit)
        val now = timestamp(unit.clock.instant())
        val condition =
            page.after?.let { cursor ->
                CREATED_AT.gt(timestamp(cursor.createdAt)).or(CREATED_AT.eq(timestamp(cursor.createdAt)).and(ID.gt(cursor.id.value)))
            } ?: DSL.noCondition()
        val entries =
            unit.dsl
                .select(*FIELDS)
                .from(TABLE)
                .where(STATUS.eq(PENDING))
                .and(NOT_BEFORE.le(now))
                .and(condition)
                .orderBy(CREATED_AT.asc(), ID.asc())
                .limit(page.limit)
                .fetch(::entry)
        return TenantJobOutboxBatch(entries, entries.lastOrNull()?.let { TenantJobOutboxCursor(it.createdAt, it.id) })
    }

    override fun acknowledge(
        unit: TenantUnit,
        entry: TenantJobOutboxEntry,
        invocation: UUID,
    ): TenantJobOutboxAcknowledgeResult {
        requireWritable(unit)
        require(invocation != UUID(0, 0)) { "tenant job invocation is not nil" }
        require(entry.epoch == unit.scope.epoch) { "tenant job outbox entry belongs to another epoch" }
        val now = unit.clock.instant()
        val written =
            unit.dsl
                .update(TABLE)
                .set(STATUS, DELIVERED)
                .set(DELIVERED_INVOCATION, invocation)
                .set(DELIVERED_AT, timestamp(now))
                .where(ID.eq(entry.id.value))
                .and(EPOCH.eq(entry.epoch.value))
                .and(DEFINITION.eq(entry.definition))
                .and(PAYLOAD_DIGEST.eq(entry.payloadDigest.copyOf()))
                .and(STATUS.eq(PENDING))
                .execute()
        if (written == 1) return TenantJobOutboxAcknowledgeResult.Acknowledged
        val stored = find(unit.dsl, entry.id) ?: return TenantJobOutboxAcknowledgeResult.Collision
        return if (
            stored.epoch == entry.epoch &&
            stored.definition == entry.definition &&
            MessageDigest.isEqual(stored.payloadDigest, entry.payloadDigest) &&
            deliveredInvocation(unit.dsl, entry.id) == invocation
        ) {
            TenantJobOutboxAcknowledgeResult.Repeated
        } else {
            TenantJobOutboxAcknowledgeResult.Collision
        }
    }

    private fun find(
        dsl: DSLContext,
        id: TenantJobOutboxId,
    ): TenantJobOutboxEntry? =
        dsl
            .select(*FIELDS)
            .from(TABLE)
            .where(ID.eq(id.value))
            .fetchOne(::entry)

    private fun deliveredInvocation(
        dsl: DSLContext,
        id: TenantJobOutboxId,
    ): UUID? =
        dsl
            .select(DELIVERED_INVOCATION)
            .from(TABLE)
            .where(ID.eq(id.value))
            .fetchOne(DELIVERED_INVOCATION)

    private fun entry(row: Record): TenantJobOutboxEntry {
        val createdAt = checkNotNull(row[CREATED_AT]) { "tenant job outbox row has no creation time" }.toInstant()
        val notBefore = checkNotNull(row[NOT_BEFORE]) { "tenant job outbox row has no eligibility time" }.toInstant()
        return TenantJobOutboxEntry(
            id = TenantJobOutboxId(checkNotNull(row[ID]) { "tenant job outbox row has no id" }),
            epoch = TenantEpoch(checkNotNull(row[EPOCH]) { "tenant job outbox row has no epoch" }),
            definition = checkNotNull(row[DEFINITION]) { "tenant job outbox row has no definition" },
            payload = checkNotNull(row[PAYLOAD]) { "tenant job outbox row has no payload" },
            payloadDigest = checkNotNull(row[PAYLOAD_DIGEST]) { "tenant job outbox row has no payload digest" },
            options =
                EnqueueOptions(
                    dedupe = dedupe(checkNotNull(row[DEDUPE_MODE]) { "tenant job outbox row has no dedupe mode" }, row[DEDUPE_KEY]),
                    priority = JobPriority(checkNotNull(row[PRIORITY]) { "tenant job outbox row has no priority" }.toInt()),
                    after = Duration.between(createdAt, notBefore),
                    subjectKey = row[SUBJECT_KEY]?.let(::SubjectKey),
                ),
            createdAt = createdAt,
            notBefore = notBefore,
        )
    }

    private fun requireWritable(unit: TenantUnit): TransactionAuthority {
        require(unit.operation in WRITABLE_OPERATIONS) { "tenant job outbox requires a writable tenant unit" }
        return unit.requireCurrentTransaction()
    }

    private companion object {
        const val PENDING: String = "pending"
        const val DELIVERED: String = "delivered"
        val WRITABLE_OPERATIONS: Set<TenantOperation> = setOf(TenantOperation.WRITE, TenantOperation.DURABLE, TenantOperation.ADMIN)
        val TABLE: Table<Record> = DSL.table(DSL.name("rain_tenant_job_outbox"))
        val ID: Field<UUID> = DSL.field(DSL.name("id"), UUID::class.java)
        val EPOCH: Field<Long> = DSL.field(DSL.name("tenant_epoch"), Long::class.java)
        val DEFINITION: Field<String> = DSL.field(DSL.name("definition"), String::class.java)
        val PAYLOAD: Field<ByteArray> = DSL.field(DSL.name("payload"), ByteArray::class.java)
        val PAYLOAD_DIGEST: Field<ByteArray> = DSL.field(DSL.name("payload_digest"), ByteArray::class.java)
        val DEDUPE_MODE: Field<String> = DSL.field(DSL.name("dedupe_mode"), String::class.java)
        val DEDUPE_KEY: Field<String> = DSL.field(DSL.name("dedupe_key"), String::class.java)
        val PRIORITY: Field<Short> = DSL.field(DSL.name("priority"), Short::class.java)
        val SUBJECT_KEY: Field<String> = DSL.field(DSL.name("subject_key"), String::class.java)
        val CREATED_AT: Field<OffsetDateTime> = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)
        val NOT_BEFORE: Field<OffsetDateTime> = DSL.field(DSL.name("not_before"), OffsetDateTime::class.java)
        val STATUS: Field<String> = DSL.field(DSL.name("status"), String::class.java)
        val DELIVERED_INVOCATION: Field<UUID> = DSL.field(DSL.name("delivered_invocation"), UUID::class.java)
        val DELIVERED_AT: Field<OffsetDateTime> = DSL.field(DSL.name("delivered_at"), OffsetDateTime::class.java)
        val FIELDS: Array<Field<*>> =
            arrayOf(ID, EPOCH, DEFINITION, PAYLOAD, PAYLOAD_DIGEST, DEDUPE_MODE, DEDUPE_KEY, PRIORITY, SUBJECT_KEY, CREATED_AT, NOT_BEFORE)
    }
}

/** The tenant-database catalogue fragment required by [PostgresTenantJobOutbox]. */
public object TenantJobOutboxSchema {
    /** Idempotent bootstrap for a newly provisioned tenant database; normal upgrades use its catalogue migration. */
    public fun install(dsl: DSLContext) {
        dsl.execute(DDL)
    }

    private const val DDL: String =
        """
        CREATE TABLE IF NOT EXISTS rain_tenant_job_outbox (
          id UUID PRIMARY KEY,
          tenant_epoch BIGINT NOT NULL CHECK (tenant_epoch > 0),
          definition TEXT NOT NULL CHECK (definition ~ '^[a-z][a-z0-9.-]{0,127}$'),
          payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 1048576),
          payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest) = 32),
          dedupe_mode TEXT NOT NULL CHECK (dedupe_mode IN ('none', 'unique', 'collapse')),
          dedupe_key TEXT,
          priority SMALLINT NOT NULL,
          subject_key TEXT,
          created_at TIMESTAMPTZ NOT NULL,
          not_before TIMESTAMPTZ NOT NULL,
          status TEXT NOT NULL CHECK (status IN ('pending', 'delivered')),
          delivered_invocation UUID,
          delivered_at TIMESTAMPTZ,
          CONSTRAINT ck_rain_tenant_job_outbox_dedupe_shape CHECK (
            (dedupe_mode = 'none' AND dedupe_key IS NULL) OR
            (dedupe_mode IN ('unique', 'collapse') AND octet_length(dedupe_key) BETWEEN 1 AND 512)
          ),
          CONSTRAINT ck_rain_tenant_job_outbox_subject_shape CHECK (
            subject_key IS NULL OR octet_length(subject_key) BETWEEN 1 AND 256
          ),
          CONSTRAINT ck_rain_tenant_job_outbox_eligibility_shape CHECK (not_before >= created_at),
          CONSTRAINT ck_rain_tenant_job_outbox_delivery_shape CHECK (
            (status = 'pending' AND delivered_invocation IS NULL AND delivered_at IS NULL) OR
            (status = 'delivered' AND delivered_invocation IS NOT NULL AND delivered_at IS NOT NULL)
          )
        );
        CREATE INDEX IF NOT EXISTS ix_rain_tenant_job_outbox_pending
          ON rain_tenant_job_outbox (status, not_before, created_at, id);
        """
}

/** Dispatcher from one tenant outbox source record into the control-plane jobs ledger. */
public fun interface TenantJobOutboxDispatcher {
    /** Called only while the control-plane delivery receipt transaction is active. */
    public fun dispatch(entry: TenantJobOutboxEntry): EnqueueOutcome
}

/**
 * Explicit jobs adapter. It accepts only definitions that require a tenant durable binding: a
 * database-mode source row must never become central work merely because a provider was omitted.
 */
public class JobsTenantJobOutboxDispatcher(
    private val queue: WorkQueue,
    private val codec: JobPayloadCodec,
    definitions: Collection<JobDefinition<*>>,
    private val clock: Clock,
) : TenantJobOutboxDispatcher {
    private val definitions: Map<String, JobDefinition<*>> = definitions.associateBy(JobDefinition<*>::name)

    init {
        require(this.definitions.size == definitions.size) { "a tenant job outbox definition is declared more than once" }
        require(this.definitions.values.all { it.tenantBinding == TenantBindingMode.REQUIRED }) {
            "a tenant job outbox definition requires a tenant durable binding"
        }
    }

    override fun dispatch(entry: TenantJobOutboxEntry): EnqueueOutcome {
        val definition = checkNotNull(definitions[entry.definition]) { "tenant job outbox definition ${entry.definition} is not installed" }
        return enqueue(definition, entry)
    }

    @Suppress("UNCHECKED_CAST")
    private fun enqueue(
        definition: JobDefinition<*>,
        entry: TenantJobOutboxEntry,
    ): EnqueueOutcome {
        val typed = definition as JobDefinition<Any>
        val payload = codec.decode(entry.payloadJson(), typed.payloadType)
        val remaining = Duration.between(clock.instant(), entry.notBefore).coerceAtLeast(Duration.ZERO)
        return queue.enqueue(typed, payload, entry.options.copy(after = remaining))
    }
}

/** Durable control-plane evidence that one local outbox id has one jobs invocation. */
public sealed interface TenantJobDeliveryReceiptOutcome {
    public data class Dispatched(
        public val invocation: UUID,
    ) : TenantJobDeliveryReceiptOutcome

    public data class Repeated(
        public val invocation: UUID,
    ) : TenantJobDeliveryReceiptOutcome

    /** The source id was reused against a different tenant, epoch, definition, or payload. */
    public data object Collision : TenantJobDeliveryReceiptOutcome
}

/**
 * Central receipt authority. [dispatch] executes only for a newly inserted receipt and in this
 * same control-plane transaction, so a job enqueue failure rolls back the claim as well.
 */
public fun interface TenantJobDeliveryReceiptStore {
    public fun deliver(
        scope: TenantScope,
        entry: TenantJobOutboxEntry,
        dispatch: () -> EnqueueOutcome,
    ): TenantJobDeliveryReceiptOutcome
}

/** PostgreSQL control-plane receipt store for [TenantJobOutbox]. */
public class JooqTenantJobDeliveryReceiptStore(
    private val dsl: DSLContext,
    private val transactions: TransactionOperations,
    private val references: TenantReferenceDigest,
    private val clock: Clock,
) : TenantJobDeliveryReceiptStore {
    override fun deliver(
        scope: TenantScope,
        entry: TenantJobOutboxEntry,
        dispatch: () -> EnqueueOutcome,
    ): TenantJobDeliveryReceiptOutcome {
        require(scope.epoch == entry.epoch) { "tenant job outbox receipt has another tenant epoch" }
        return checkNotNull(
            transactions.execute {
                val digest = references.digest(scope.ref)
                val inserted =
                    dsl
                        .insertInto(TABLE)
                        .set(OUTBOX_ID, entry.id.value)
                        .set(REF_DIGEST, digest.copy())
                        .set(EPOCH, entry.epoch.value)
                        .set(DEFINITION, entry.definition)
                        .set(PAYLOAD_DIGEST, entry.payloadDigest.copyOf())
                        .set(DISPATCHED_AT, timestamp(clock.instant()))
                        .onConflict(OUTBOX_ID)
                        .doNothing()
                        .execute()
                if (inserted == 1) {
                    val invocation = dispatch().invocation
                    val written =
                        dsl
                            .update(TABLE)
                            .set(INVOCATION_ID, invocation)
                            .where(OUTBOX_ID.eq(entry.id.value))
                            .and(INVOCATION_ID.isNull)
                            .execute()
                    check(written == 1) { "tenant job delivery receipt changed during dispatch" }
                    TenantJobDeliveryReceiptOutcome.Dispatched(invocation)
                } else {
                    val stored = checkNotNull(find(entry.id)) { "tenant job delivery receipt disappeared after conflict" }
                    if (stored.matches(digest.copy(), entry)) {
                        TenantJobDeliveryReceiptOutcome.Repeated(
                            checkNotNull(stored.invocation) { "tenant job delivery receipt is incomplete" },
                        )
                    } else {
                        TenantJobDeliveryReceiptOutcome.Collision
                    }
                }
            },
        )
    }

    private fun find(id: TenantJobOutboxId): Receipt? =
        dsl
            .select(REF_DIGEST, EPOCH, DEFINITION, PAYLOAD_DIGEST, INVOCATION_ID)
            .from(TABLE)
            .where(OUTBOX_ID.eq(id.value))
            .fetchOne { row ->
                Receipt(
                    digest = checkNotNull(row[REF_DIGEST]) { "tenant job delivery receipt has no tenant digest" },
                    epoch = TenantEpoch(checkNotNull(row[EPOCH]) { "tenant job delivery receipt has no epoch" }),
                    definition = checkNotNull(row[DEFINITION]) { "tenant job delivery receipt has no definition" },
                    payloadDigest = checkNotNull(row[PAYLOAD_DIGEST]) { "tenant job delivery receipt has no payload digest" },
                    invocation = row[INVOCATION_ID],
                )
            }

    private class Receipt(
        private val digest: ByteArray,
        private val epoch: TenantEpoch,
        private val definition: String,
        private val payloadDigest: ByteArray,
        val invocation: UUID?,
    ) {
        fun matches(
            expectedDigest: ByteArray,
            entry: TenantJobOutboxEntry,
        ): Boolean =
            MessageDigest.isEqual(digest, expectedDigest) &&
                epoch == entry.epoch &&
                definition == entry.definition &&
                MessageDigest.isEqual(payloadDigest, entry.payloadDigest)
    }

    private companion object {
        val TABLE: Table<Record> = DSL.table(DSL.name("rain_tenancy", "tenant_delivery_receipt"))
        val OUTBOX_ID: Field<UUID> = DSL.field(DSL.name("outbox_id"), UUID::class.java)
        val REF_DIGEST: Field<ByteArray> = DSL.field(DSL.name("ref_digest"), ByteArray::class.java)
        val EPOCH: Field<Long> = DSL.field(DSL.name("tenant_epoch"), Long::class.java)
        val DEFINITION: Field<String> = DSL.field(DSL.name("definition"), String::class.java)
        val PAYLOAD_DIGEST: Field<ByteArray> = DSL.field(DSL.name("payload_digest"), ByteArray::class.java)
        val INVOCATION_ID: Field<UUID> = DSL.field(DSL.name("invocation_id"), UUID::class.java)
        val DISPATCHED_AT: Field<OffsetDateTime> = DSL.field(DSL.name("dispatched_at"), OffsetDateTime::class.java)
    }
}

/** One bounded relay request for one grant-authorized tenant lane. */
public data class TenantJobOutboxRelayRequest(
    public val grant: TenantGrant,
    public val tenant: TenantRef,
    public val page: TenantJobOutboxPage,
)

/** The relay's explicit partial outcome; a collision halts the tenant lane without source acknowledgement. */
public sealed interface TenantJobOutboxRelayResult {
    public data class Delivered(
        public val count: Int,
        public val next: TenantJobOutboxCursor?,
    ) : TenantJobOutboxRelayResult

    public data class Halted(
        public val delivered: Int,
        public val next: TenantJobOutboxCursor?,
    ) : TenantJobOutboxRelayResult
}

/**
 * Bounded bridge across the two database transactions. It never keeps a tenant transaction open
 * while it claims/enqueues in the control plane: source read, central receipt+job enqueue, and
 * source acknowledgement are three separately fenced steps.
 */
public class TenantJobOutboxRelay(
    private val dataPlane: TenantDataPlane,
    private val grants: TenantGrantVerifier,
    private val outbox: TenantJobOutbox,
    private val receipts: TenantJobDeliveryReceiptStore,
    private val dispatcher: TenantJobOutboxDispatcher,
) {
    public fun relay(request: TenantJobOutboxRelayRequest): TenantJobOutboxRelayResult {
        val scope = grants.verify(request.grant, request.tenant, TenantOperation.ADMIN)
        val batch = dataPlane.admin(request.grant, request.tenant) { unit -> outbox.pending(unit, request.page) }
        var delivered = 0
        var next = request.page.after
        for (entry in batch.entries) {
            val receipt = TenantContext.bind(scope).use { receipts.deliver(scope, entry) { dispatcher.dispatch(entry) } }
            val invocation =
                when (receipt) {
                    is TenantJobDeliveryReceiptOutcome.Dispatched -> receipt.invocation
                    is TenantJobDeliveryReceiptOutcome.Repeated -> receipt.invocation
                    TenantJobDeliveryReceiptOutcome.Collision -> return TenantJobOutboxRelayResult.Halted(delivered, next)
                }
            val acknowledged =
                dataPlane.admin(request.grant, request.tenant) { unit ->
                    outbox.acknowledge(unit, entry, invocation)
                }
            if (acknowledged == TenantJobOutboxAcknowledgeResult.Collision) {
                return TenantJobOutboxRelayResult.Halted(delivered, next)
            }
            delivered += 1
            next = TenantJobOutboxCursor(entry.createdAt, entry.id)
        }
        return TenantJobOutboxRelayResult.Delivered(delivered, batch.next)
    }
}

private fun dedupeMode(dedupe: Dedupe): String =
    when (dedupe) {
        Dedupe.None -> "none"
        is Dedupe.Unique -> "unique"
        is Dedupe.Collapse -> "collapse"
    }

private fun dedupeKey(dedupe: Dedupe): String? =
    when (dedupe) {
        Dedupe.None -> null
        is Dedupe.Unique -> dedupe.key
        is Dedupe.Collapse -> dedupe.key
    }

private fun dedupe(
    mode: String,
    key: String?,
): Dedupe =
    when (mode) {
        "none" -> {
            check(key == null) { "tenant job outbox none dedupe has a key" }
            Dedupe.None
        }

        "unique" -> {
            Dedupe.Unique(checkNotNull(key) { "tenant job outbox unique dedupe has no key" })
        }

        "collapse" -> {
            Dedupe.Collapse(checkNotNull(key) { "tenant job outbox collapse dedupe has no key" })
        }

        else -> {
            error("unknown tenant job outbox dedupe mode")
        }
    }

private fun timestamp(value: Instant): OffsetDateTime = value.atOffset(ZoneOffset.UTC)

private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
