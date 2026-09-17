package com.gd.rain.event.postgres

import com.gd.rain.event.AppendFingerprint
import com.gd.rain.event.AppendResult
import com.gd.rain.event.CommitRange
import com.gd.rain.event.CommittedEventLog
import com.gd.rain.event.CommittedLogPage
import com.gd.rain.event.CompletedReceipt
import com.gd.rain.event.EncodedChanges
import com.gd.rain.event.EncodedFact
import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventLimits
import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventLogSettlement
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.EventOperationReceipts
import com.gd.rain.event.EventRefusal
import com.gd.rain.event.EventSnapshotStore
import com.gd.rain.event.EventStoreSupport
import com.gd.rain.event.EventTransaction
import com.gd.rain.event.FactType
import com.gd.rain.event.OperationKey
import com.gd.rain.event.ReceiptBinding
import com.gd.rain.event.ReceiptClaim
import com.gd.rain.event.ReceiptCompletion
import com.gd.rain.event.ReceiptRefusal
import com.gd.rain.event.ReceiptResponse
import com.gd.rain.event.ReceiptToken
import com.gd.rain.event.RequestFingerprint
import com.gd.rain.event.SnapshotCodecId
import com.gd.rain.event.SnapshotFingerprint
import com.gd.rain.event.SnapshotSaveResult
import com.gd.rain.event.SnapshotWrite
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StoredSnapshot
import com.gd.rain.event.StreamPage
import com.gd.rain.event.StreamRef
import com.gd.rain.event.appendFingerprint
import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionPlacement
import org.jooq.DSLContext
import org.springframework.dao.DataAccessException
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant

/** Supplies the already-active transaction placement to a PostgreSQL event adapter. */
public fun interface EventTransactionPlacement {
    public fun inspect(): TransactionPlacement
}

/**
 * PostgreSQL event store over the caller's Spring transaction.
 *
 * It never opens a transaction, retries a statement, or invokes a decision callback. The CTE in
 * [append] advances a stream and inserts an entire ordered batch atomically; zero inserted rows are
 * a confirmed optimistic conflict.
 */
public class PostgresEventStore(
    private val dsl: DSLContext,
    private val placement: EventTransactionPlacement,
    override val limits: EventLimits = EventLimits(),
) : EventStoreSupport(),
    EventOperationReceipts,
    EventSnapshotStore,
    CommittedEventLog {
    public constructor(
        dsl: DSLContext,
        transactionManager: PlatformTransactionManager,
        limits: EventLimits = EventLimits(),
    ) : this(dsl, EventTransactionPlacement { TransactionPlacement.inspect(dsl, transactionManager) }, limits)

    override val backing: BackingIdentity = placement.inspect().backing
    override val origin: EventLogOrigin by lazy {
        val logId = checkNotNull(dsl.fetchOne(LOG_ID)?.get("log_id", ByteArray::class.java)) { "event schema has no log id" }
        EventLogOrigin(EventLogId.of(logId))
    }
    private val active: ThreadLocal<EventTransaction?> = ThreadLocal.withInitial { null }

    override fun <T> inCallerTransaction(block: (EventTransaction) -> T): T {
        val currentPlacement = placement.inspect()
        check(currentPlacement.backing == backing) { "event store backing changed" }
        currentPlacement.requireAuthority()
        check(active.get() == null) { "event store transaction callbacks do not nest" }
        val transaction = newTransaction()
        active.set(transaction)
        return try {
            block(transaction)
        } finally {
            active.remove()
        }
    }

    override fun version(
        transaction: EventTransaction,
        stream: StreamRef,
    ): Long? {
        requireActive(transaction)
        return dsl.fetchOne(STREAM_VERSION, stream.namespace.copy(), stream.family, stream.key)?.get(0, Long::class.java)
    }

    override fun readStream(
        transaction: EventTransaction,
        stream: StreamRef,
        afterVersion: Long,
        limit: Int,
    ): StreamPage {
        requireActive(transaction)
        require(afterVersion >= 0) { "after version is not negative" }
        require(limit in 1..limits.streamPageSize) { "stream page limit is outside the configured bound" }
        val rows = dsl.fetch(STREAM_PAGE, stream.namespace.copy(), stream.family, stream.key, afterVersion, limit + 1)
        val events =
            rows.take(limit).map { row ->
                StoredEvent(
                    stream = stream,
                    streamVersion = checkNotNull(row.get("version", Long::class.java)),
                    position = checkNotNull(row.get("position", Long::class.java)),
                    fact = FactType(checkNotNull(row.get("type", String::class.java)), checkNotNull(row.get("revision", Int::class.java))),
                    payload =
                        com.gd.rain.event.EventBytes
                            .of(checkNotNull(row.get("payload", ByteArray::class.java))),
                    metadata =
                        com.gd.rain.event.EventBytes
                            .of(checkNotNull(row.get("metadata", ByteArray::class.java))),
                    recordedAt = checkNotNull(row.get("recorded_at", Instant::class.java)),
                )
            }
        return StreamPage(events, afterVersion, rows.size > limit)
    }

    override fun append(
        transaction: EventTransaction,
        expectedVersion: Long,
        changes: EncodedChanges,
        metadata: EventMetadata,
    ): AppendResult {
        requireActive(transaction)
        require(expectedVersion >= 0) { "expected version is not negative" }
        require(changes.facts.size <= limits.batchSize) { "event batch exceeds the configured bound" }
        require(metadata.canonicalBytes().size <= limits.metadataBytes) { "metadata exceeds the configured bound" }
        if (changes.facts.isEmpty()) {
            val actual = version(transaction, changes.stream) ?: 0L
            return if (actual == expectedVersion) AppendResult.Committed(CommitRange.EMPTY) else AppendResult.Conflict
        }
        return try {
            val row =
                checkNotNull(
                    dsl.fetchOne(
                        CONDITIONAL_APPEND,
                        changes.facts.map { it.type.name }.toTypedArray(),
                        changes.facts.map { it.type.revision }.toTypedArray(),
                        changes.facts.map { it.payload.copy() }.toTypedArray(),
                        changes.stream.namespace.copy(),
                        changes.stream.family,
                        changes.stream.key,
                        expectedVersion,
                        expectedVersion,
                        changes.stream.namespace.copy(),
                        changes.stream.family,
                        changes.stream.key,
                        changes.facts.size,
                        expectedVersion,
                        changes.stream.namespace.copy(),
                        changes.stream.family,
                        changes.stream.key,
                        metadata.canonicalBytes().copy(),
                    ),
                )
            val count = row.get("count", Int::class.java) ?: 0
            if (count == 0) return AppendResult.Conflict
            AppendResult.Committed(
                CommitRange(
                    firstPosition = checkNotNull(row.get("first_position", Long::class.java)),
                    lastPosition = checkNotNull(row.get("last_position", Long::class.java)),
                    firstVersion = checkNotNull(row.get("first_version", Long::class.java)),
                    lastVersion = checkNotNull(row.get("last_version", Long::class.java)),
                    count = count,
                ),
            )
        } catch (failure: DataAccessException) {
            throw PostgresEventStoreFailure(failure)
        }
    }

    override fun latest(
        transaction: EventTransaction,
        stream: StreamRef,
        atOrBeforeVersion: Long,
    ): StoredSnapshot? {
        requireActive(transaction)
        require(atOrBeforeVersion > 0) { "snapshot upper version is positive" }
        return dsl
            .fetchOne(SNAPSHOT_LATEST, stream.namespace.copy(), stream.family, stream.key, atOrBeforeVersion)
            ?.let { row -> snapshot(stream, row) }
    }

    override fun save(
        transaction: EventTransaction,
        write: SnapshotWrite,
    ): SnapshotSaveResult {
        requireActive(transaction)
        val persisted =
            dsl.fetchOne(SNAPSHOT_STREAM_VERSION, write.stream.namespace.copy(), write.stream.family, write.stream.key, write.version)
                ?: throw EventRefusal.VersionNotFound
        check(persisted.get("version", Long::class.java) == write.version) { "snapshot source version changed" }
        val inserted =
            dsl.fetchOne(
                SNAPSHOT_INSERT,
                write.stream.namespace.copy(),
                write.stream.family,
                write.stream.key,
                write.version,
                write.fingerprint.copy(),
                write.codec.type,
                write.codec.revision,
                write.payload.copy(),
                write.checksum.copy(),
            )
        if (inserted != null) return SnapshotSaveResult.Saved(snapshot(write.stream, inserted))
        val existing =
            checkNotNull(
                dsl.fetchOne(SNAPSHOT_EXACT, write.stream.namespace.copy(), write.stream.family, write.stream.key, write.version),
            ) { "event snapshot disappeared after a conflicting insert" }
        val stored = snapshot(write.stream, existing)
        return if (
            stored.fingerprint == write.fingerprint &&
            stored.codec == write.codec &&
            stored.payload == write.payload &&
            stored.checksum() == write.checksum
        ) {
            SnapshotSaveResult.Repeated(stored)
        } else {
            SnapshotSaveResult.Collision
        }
    }

    override fun prune(
        transaction: EventTransaction,
        stream: StreamRef,
        keep: Int,
        batch: Int,
    ): Int {
        requireActive(transaction)
        require(keep >= 1) { "snapshot retention keep is positive" }
        require(batch in 1..MAX_SNAPSHOT_PRUNE_BATCH) { "snapshot retention batch is 1..$MAX_SNAPSHOT_PRUNE_BATCH" }
        return dsl.execute(SNAPSHOT_PRUNE, stream.namespace.copy(), stream.family, stream.key, keep, batch)
    }

    override fun initialCursor(): EventLogCursor = EventLogCursor.start(origin)

    override fun readCommitted(
        cursor: EventLogCursor,
        limit: Int,
    ): CommittedLogPage {
        require(cursor.origin == origin) { "event log cursor belongs to another log" }
        require(limit in 1..limits.logPageSize) { "event log page limit is outside the configured bound" }
        val boundary = checkNotNull(dsl.fetchOne(LOG_BOUNDARY)) { "event log settlement boundary was not returned" }
        val bound = checkNotNull(boundary.get("bound_xid", String::class.java)) { "event log settlement has no xid bound" }
        val reach = checkNotNull(boundary.get("reach", Long::class.java)) { "event log settlement has no reach" }
        val rows = dsl.fetch(COMMITTED_LOG_PAGE, cursor.deliveredPosition, reach, bound, limit + 1)
        val events = rows.take(limit).map(::storedEvent)
        val next =
            EventLogCursor(
                origin,
                events.lastOrNull()?.position ?: cursor.deliveredPosition,
                EventLogSettlement(bound, reach),
            )
        return CommittedLogPage(events, next, rows.size > limit)
    }

    override fun claim(
        transaction: EventTransaction,
        namespace: EventNamespace,
        operation: OperationKey,
        request: RequestFingerprint,
    ): ReceiptClaim {
        requireActive(transaction)
        val inserted = dsl.fetchOne(CLAIM_RECEIPT, namespace.copy(), operation.text(), request.copy().copy())
        if (inserted != null) {
            return ReceiptClaim.Claimed(
                ReceiptToken(ReceiptBinding(transaction.storeId, transaction.nonce, namespace, operation, request)),
            )
        }
        val row =
            checkNotNull(dsl.fetchOne(READ_RECEIPT, namespace.copy(), operation.text())) {
                "an event receipt disappeared after its conflicting claim"
            }
        if (RequestFingerprint.stored(checkNotNull(row.get("request_fingerprint", ByteArray::class.java))) != request) {
            throw ReceiptRefusal.Collision
        }
        if (row.get("complete", Boolean::class.java) != true) throw ReceiptRefusal.Incomplete
        return ReceiptClaim.Repeated(receipt(row, namespace))
    }

    override fun complete(
        transaction: EventTransaction,
        token: ReceiptToken,
        completion: ReceiptCompletion,
    ): CompletedReceipt {
        requireActive(transaction)
        require(token.binding.storeId == transaction.storeId && token.binding.nonce == transaction.nonce) {
            "receipt token is from another event transaction"
        }
        require(completion.stream.namespace == token.binding.namespace) { "receipt completion targets another namespace" }
        require(completion.metadata.operation == token.binding.operation) { "receipt completion has another operation key" }
        require((completion.response?.payload?.size ?: 0) <= limits.responseBytes) { "receipt response exceeds the configured bound" }
        require(verifiedAppend(completion) == completion.append) { "receipt completion does not match committed event rows" }
        val response = completion.response
        val written =
            dsl.fetchOne(
                COMPLETE_RECEIPT,
                completion.append.copy().copy(),
                completion.stream.family,
                completion.stream.key,
                completion.range.firstPosition,
                completion.range.lastPosition,
                completion.range.firstVersion,
                completion.range.lastVersion,
                completion.range.count,
                response?.type,
                response?.revision,
                response?.payload?.copy(),
                token.binding.namespace.copy(),
                token.binding.operation.text(),
                token.binding.request
                    .copy()
                    .copy(),
            )
        if (written == null) throw ReceiptRefusal.Incomplete
        return CompletedReceipt(completion.stream, completion.range, completion.append, response)
    }

    private fun requireActive(transaction: EventTransaction) {
        requireTransaction(transaction)
        check(isSameTransaction(transaction, checkNotNull(active.get()))) { "event transaction is stale" }
        placement.inspect().requireAuthority()
    }

    private fun receipt(
        row: org.jooq.Record,
        namespace: EventNamespace,
    ): CompletedReceipt {
        val count = checkNotNull(row.get("event_count", Int::class.java))
        val range =
            if (count == 0) {
                CommitRange.EMPTY
            } else {
                CommitRange(
                    checkNotNull(row.get("first_position", Long::class.java)),
                    checkNotNull(row.get("last_position", Long::class.java)),
                    checkNotNull(row.get("first_version", Long::class.java)),
                    checkNotNull(row.get("last_version", Long::class.java)),
                    count,
                )
            }
        val responseType = row.get("response_type", String::class.java)
        val responseRevision = row.get("response_revision", Int::class.java)
        val responsePayload = row.get("response_payload", ByteArray::class.java)
        val response =
            if (responseType == null && responseRevision == null && responsePayload == null) {
                null
            } else {
                ReceiptResponse(
                    checkNotNull(responseType) { "a completed receipt response has no type" },
                    checkNotNull(responseRevision) { "a completed receipt response has no revision" },
                    EventBytes.of(checkNotNull(responsePayload) { "a completed receipt response has no payload" }),
                )
            }
        return CompletedReceipt(
            StreamRef(
                namespace,
                checkNotNull(row.get("family", String::class.java)) { "a completed receipt has no family" },
                checkNotNull(row.get("stream_key", String::class.java)) { "a completed receipt has no stream key" },
            ),
            range,
            AppendFingerprint(EventBytes.of(checkNotNull(row.get("append_fingerprint", ByteArray::class.java)))),
            response,
        )
    }

    private fun storedEvent(row: org.jooq.Record): StoredEvent =
        StoredEvent(
            StreamRef(
                EventNamespace.of(checkNotNull(row.get("namespace", ByteArray::class.java)) { "event record has no namespace" }),
                checkNotNull(row.get("family", String::class.java)) { "event record has no family" },
                checkNotNull(row.get("stream_key", String::class.java)) { "event record has no stream key" },
            ),
            checkNotNull(row.get("version", Long::class.java)) { "event record has no version" },
            checkNotNull(row.get("position", Long::class.java)) { "event record has no position" },
            FactType(
                checkNotNull(row.get("type", String::class.java)) { "event record has no type" },
                checkNotNull(row.get("revision", Int::class.java)) { "event record has no revision" },
            ),
            EventBytes.of(checkNotNull(row.get("payload", ByteArray::class.java)) { "event record has no payload" }),
            EventBytes.of(checkNotNull(row.get("metadata", ByteArray::class.java)) { "event record has no metadata" }),
            checkNotNull(row.get("recorded_at", Instant::class.java)) { "event record has no recorded time" },
        )

    private fun snapshot(
        stream: StreamRef,
        row: org.jooq.Record,
    ): StoredSnapshot =
        StoredSnapshot(
            stream,
            checkNotNull(row.get("version", Long::class.java)) { "event snapshot has no version" },
            SnapshotFingerprint.of(checkNotNull(row.get("fingerprint", ByteArray::class.java)) { "event snapshot has no fingerprint" }),
            SnapshotCodecId(
                checkNotNull(row.get("codec_type", String::class.java)) { "event snapshot has no codec type" },
                checkNotNull(row.get("codec_revision", Int::class.java)) { "event snapshot has no codec revision" },
            ),
            EventBytes.of(checkNotNull(row.get("payload", ByteArray::class.java)) { "event snapshot has no payload" }),
            EventBytes.of(checkNotNull(row.get("checksum", ByteArray::class.java)) { "event snapshot has no checksum" }),
            checkNotNull(row.get("created_at", Instant::class.java)) { "event snapshot has no creation time" },
        )

    private fun verifiedAppend(completion: ReceiptCompletion): AppendFingerprint {
        if (completion.range.count == 0) return appendFingerprint(completion.stream, emptyList(), completion.metadata)
        val range = completion.range
        val rows =
            dsl.fetch(
                RECEIPT_EVENTS,
                completion.stream.namespace.copy(),
                completion.stream.family,
                completion.stream.key,
                range.firstVersion,
                range.lastVersion,
            )
        require(rows.size == range.count) { "receipt completion event count does not match committed rows" }
        val expectedMetadata = completion.metadata.canonicalBytes()
        val facts =
            rows.mapIndexed { index, row ->
                require(row.get("version", Long::class.java) == range.firstVersion!! + index) {
                    "receipt completion versions are not dense"
                }
                require(EventBytes.of(checkNotNull(row.get("metadata", ByteArray::class.java))) == expectedMetadata) {
                    "receipt completion metadata does not match committed rows"
                }
                EncodedFact(
                    FactType(checkNotNull(row.get("type", String::class.java)), checkNotNull(row.get("revision", Int::class.java))),
                    EventBytes.of(checkNotNull(row.get("payload", ByteArray::class.java))),
                )
            }
        require(rows.first().get("position", Long::class.java) == range.firstPosition) {
            "receipt completion first position does not match committed rows"
        }
        require(rows.last().get("position", Long::class.java) == range.lastPosition) {
            "receipt completion last position does not match committed rows"
        }
        return appendFingerprint(completion.stream, facts, completion.metadata)
    }

    private companion object {
        const val LOG_ID: String = "SELECT log_id FROM rain_event.event_schema_meta WHERE singleton"

        const val LOG_BOUNDARY: String = """
            SELECT pg_snapshot_xmin(pg_current_snapshot())::text AS bound_xid,
                   last_value::bigint AS reach
            FROM rain_event.event_record_position_seq
            """

        const val COMMITTED_LOG_PAGE: String = """
            SELECT position, namespace, family, stream_key, version, type, revision, payload, metadata, recorded_at
            FROM rain_event.event_record
            WHERE position > ?
              AND position <= ?
              AND writer_xid < ?::text::xid8
            ORDER BY position
            LIMIT ?
            """

        const val STREAM_VERSION: String = """
            SELECT version
            FROM rain_event.event_stream
            WHERE namespace = ? AND family = ? AND stream_key = ?
            """

        const val STREAM_PAGE: String = """
            SELECT position, version, type, revision, payload, metadata, recorded_at
            FROM rain_event.event_record
            WHERE namespace = ? AND family = ? AND stream_key = ? AND version > ?
            ORDER BY version
            LIMIT ?
            """

        const val CONDITIONAL_APPEND: String = """
            WITH input(type, revision, payload, ordinal) AS (
                SELECT type, revision, payload, ordinal
                FROM unnest(?::varchar[], ?::integer[], ?::bytea[]) WITH ORDINALITY AS source(type, revision, payload, ordinal)
            ),
            batch AS (
                SELECT count(*)::bigint AS count FROM input
            ),
            updated AS (
                UPDATE rain_event.event_stream stream
                SET version = stream.version + batch.count
                FROM batch
                WHERE stream.namespace = ?
                  AND stream.family = ?
                  AND stream.stream_key = ?
                  AND stream.version = ?
                  AND ?::bigint > 0
                RETURNING stream.version - batch.count AS base_version
            ),
            created AS (
                INSERT INTO rain_event.event_stream(namespace, family, stream_key, version)
                SELECT ?, ?, ?, ?::bigint
                WHERE ?::bigint = 0
                ON CONFLICT (namespace, family, stream_key) DO NOTHING
                RETURNING 0::bigint AS base_version
            ),
            admitted AS (
                SELECT base_version FROM updated
                UNION ALL
                SELECT base_version FROM created
            ),
            inserted AS (
                INSERT INTO rain_event.event_record(namespace, family, stream_key, version, type, revision, payload, metadata)
                SELECT ?, ?, ?, admitted.base_version + input.ordinal, input.type, input.revision, input.payload, ?
                FROM admitted CROSS JOIN input
                ORDER BY input.ordinal
                RETURNING position, version
            )
            SELECT min(position) AS first_position,
                   max(position) AS last_position,
                   min(version) AS first_version,
                   max(version) AS last_version,
                   count(*)::integer AS count
            FROM inserted
            """

        const val CLAIM_RECEIPT: String = """
            INSERT INTO rain_event.event_receipt(namespace, operation_key, request_fingerprint)
            VALUES (?, ?, ?)
            ON CONFLICT (namespace, operation_key) DO NOTHING
            RETURNING operation_key
            """

        const val READ_RECEIPT: String = """
            SELECT request_fingerprint, append_fingerprint, family, stream_key,
                   first_position, last_position, first_version, last_version, event_count,
                   response_type, response_revision, response_payload, complete
            FROM rain_event.event_receipt
            WHERE namespace = ? AND operation_key = ?
            """

        const val COMPLETE_RECEIPT: String = """
            UPDATE rain_event.event_receipt
            SET append_fingerprint = ?,
                family = ?,
                stream_key = ?,
                first_position = ?,
                last_position = ?,
                first_version = ?,
                last_version = ?,
                event_count = ?,
                response_type = ?,
                response_revision = ?,
                response_payload = ?,
                complete = true
            WHERE namespace = ?
              AND operation_key = ?
              AND request_fingerprint = ?
              AND complete = false
            RETURNING operation_key
            """

        const val RECEIPT_EVENTS: String = """
            SELECT position, version, type, revision, payload, metadata
            FROM rain_event.event_record
            WHERE namespace = ?
              AND family = ?
              AND stream_key = ?
              AND version BETWEEN ? AND ?
            ORDER BY version
            """

        const val SNAPSHOT_LATEST: String = """
            SELECT version, fingerprint, codec_type, codec_revision, payload, checksum, created_at
            FROM rain_event.event_snapshot
            WHERE namespace = ? AND family = ? AND stream_key = ? AND version <= ?
            ORDER BY version DESC
            LIMIT 1
            """

        const val SNAPSHOT_STREAM_VERSION: String = """
            SELECT version
            FROM rain_event.event_record
            WHERE namespace = ? AND family = ? AND stream_key = ? AND version = ?
            """

        const val SNAPSHOT_INSERT: String = """
            INSERT INTO rain_event.event_snapshot(
              namespace, family, stream_key, version, fingerprint, codec_type, codec_revision, payload, checksum
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (namespace, family, stream_key, version) DO NOTHING
            RETURNING version, fingerprint, codec_type, codec_revision, payload, checksum, created_at
            """

        const val SNAPSHOT_EXACT: String = """
            SELECT version, fingerprint, codec_type, codec_revision, payload, checksum, created_at
            FROM rain_event.event_snapshot
            WHERE namespace = ? AND family = ? AND stream_key = ? AND version = ?
            """

        const val SNAPSHOT_PRUNE: String = """
            WITH obsolete AS (
              SELECT ctid
              FROM rain_event.event_snapshot
              WHERE namespace = ? AND family = ? AND stream_key = ?
              ORDER BY version DESC
              OFFSET ?
              LIMIT ?
            )
            DELETE FROM rain_event.event_snapshot snapshot
            USING obsolete
            WHERE snapshot.ctid = obsolete.ctid
            """

        const val MAX_SNAPSHOT_PRUNE_BATCH: Int = 10_000
    }
}

/** Backend failure after a database call; callers must not infer that an append wrote zero rows. */
public class PostgresEventStoreFailure(
    cause: DataAccessException,
) : RuntimeException("event store operation failed", cause)
