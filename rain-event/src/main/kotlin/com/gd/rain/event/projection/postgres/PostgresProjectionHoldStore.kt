package com.gd.rain.event.projection.postgres

import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.FactType
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StreamRef
import com.gd.rain.event.projection.ProjectionFailureCode
import com.gd.rain.event.projection.ProjectionHold
import com.gd.rain.event.projection.ProjectionHoldEnqueue
import com.gd.rain.event.projection.ProjectionHoldEviction
import com.gd.rain.event.projection.ProjectionHoldLimits
import com.gd.rain.event.projection.ProjectionHoldState
import com.gd.rain.event.projection.ProjectionHoldStoreSupport
import com.gd.rain.event.projection.ProjectionHole
import com.gd.rain.event.projection.ProjectionHoleAcknowledgement
import com.gd.rain.event.projection.ProjectionHoleId
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLetter
import com.gd.rain.event.projection.ProjectionOperator
import com.gd.rain.event.projection.ProjectionRedriveAcknowledge
import com.gd.rain.event.projection.ProjectionRedriveClaim
import com.gd.rain.event.projection.ProjectionRedriveFailure
import com.gd.rain.event.projection.ProjectionRedriveLease
import com.gd.rain.event.projection.ProjectionRedriveLetter
import com.gd.rain.event.projection.ProjectionRedriveRelease
import com.gd.rain.event.projection.ProjectionSequenceId
import com.gd.rain.event.projection.ProjectionTransactionPlacement
import com.gd.rain.event.projection.SameUnitProjectionHoldStore
import com.gd.rain.persistence.tx.TransactionPlacement
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL parking/redrive adapter.
 *
 * The live path conditions every mutation on the active checkpoint lease. The redrive path has a
 * distinct row fence: it can never race a later redrive, while a live pass continues to append letters behind it.
 * This class opens neither a transaction nor a retry loop; all methods participate in the caller's proved unit.
 */
public class PostgresProjectionHoldStore(
    private val dsl: DSLContext,
    private val origin: EventLogOrigin,
    private val placement: ProjectionTransactionPlacement? = null,
) : ProjectionHoldStoreSupport() {
    public constructor(
        dsl: DSLContext,
        origin: EventLogOrigin,
        transactionManager: PlatformTransactionManager,
    ) : this(dsl, origin, ProjectionTransactionPlacement { TransactionPlacement.inspect(dsl, transactionManager) })

    override fun inspectPlacement(): TransactionPlacement =
        checkNotNull(placement) { "projection hold store was not configured for same-unit verification" }.inspect()

    override fun enqueue(
        lease: com.gd.rain.event.projection.ProjectionLease,
        letter: ProjectionLetter,
        failure: ProjectionFailureCode?,
        limits: ProjectionHoldLimits,
    ): ProjectionHoldEnqueue {
        if (!currentCheckpointLease(lease)) return ProjectionHoldEnqueue.LostLease
        var hold = lockHold(lease.lane, letter.sequence)
        if (hold == null) {
            if (failure == null) return ProjectionHoldEnqueue.NotHeld
            dsl.execute(
                INSERT_HOLD,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                letter.sequence.copy(),
                letter.event.position,
                failure.text(),
            )
            hold = checkNotNull(lockHold(lease.lane, letter.sequence)) { "inserted projection hold disappeared" }
        }
        val existing = letterRow(lease.lane, letter.sequence, letter.event.position)
        if (existing != null) {
            check(EventBytes.of(checkNotNull(existing.get("checksum", ByteArray::class.java))) == letter.checksum) {
                "projection letter position has a different immutable envelope"
            }
            return ProjectionHoldEnqueue.Queued(hold, repeated = true)
        }
        val lastPosition = lastLetterPosition(lease.lane, letter.sequence)
        require(lastPosition == null || letter.event.position > lastPosition) {
            "projection letters must append in strict event-log order"
        }
        if (hold.letterCount == limits.maxLetters || hold.letterBytes > limits.maxBytes - letter.retainedBytes) {
            return ProjectionHoldEnqueue.CapacityExceeded(hold)
        }
        dsl.execute(
            INSERT_LETTER,
            lease.lane.projection.text(),
            lease.lane.generation.value,
            lease.lane.partition.depth,
            lease.lane.partition.prefix,
            letter.sequence.copy(),
            letter.event.position,
            letter.event.stream.namespace
                .copy(),
            letter.event.stream.family,
            letter.event.stream.key,
            letter.event.streamVersion,
            letter.event.fact.name,
            letter.event.fact.revision,
            letter.event.payload.copy(),
            letter.event.metadata.copy(),
            letter.event.recordedAt,
            letter.checksum.copy(),
        )
        val updated =
            dsl.fetchOne(
                INCREMENT_HOLD,
                letter.retainedBytes,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                letter.sequence.copy(),
            ) ?: error("projection hold disappeared while appending a letter")
        return ProjectionHoldEnqueue.Queued(hold(updated), repeated = false)
    }

    override fun claimRedrive(
        lane: ProjectionLane,
        now: Instant,
        leaseFor: Duration,
    ): ProjectionRedriveClaim {
        val leaseMillis = leaseFor.toMillis().also { require(it > 0) { "projection redrive lease duration is below one millisecond" } }
        require(!leaseFor.isNegative && !leaseFor.isZero) { "projection redrive lease duration is positive" }
        val token = UUID.randomUUID()
        val claimed =
            dsl.fetchOne(
                CLAIM_REDRIVE,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                origin.logId.copy(),
                token,
                leaseMillis,
            )
        if (claimed != null) {
            val hold = hold(claimed)
            return ProjectionRedriveClaim.Acquired(
                hold,
                newRedriveLease(lane, hold.sequence, hold.fence, token),
            )
        }
        val busyAt =
            dsl
                .fetchOne(
                    BUSY_REDRIVE,
                    lane.projection.text(),
                    lane.generation.value,
                    lane.partition.depth,
                    lane.partition.prefix,
                    origin.logId.copy(),
                )?.get("lease_until", Instant::class.java)
        return if (busyAt == null) ProjectionRedriveClaim.Idle else ProjectionRedriveClaim.Busy(busyAt)
    }

    override fun letters(
        lease: ProjectionRedriveLease,
        limit: Int,
    ): List<ProjectionRedriveLetter> {
        requireLease(lease)
        require(limit in 1..MAX_REDRIVE_LETTERS) { "projection redrive letter read limit is outside 1..$MAX_REDRIVE_LETTERS" }
        check(currentRedriveLease(lease)) { "projection redrive lease is no longer current" }
        return dsl
            .fetch(
                READ_LETTERS,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.sequence.copy(),
                limit,
            ).map(::redriveLetter)
    }

    override fun acknowledge(
        lease: ProjectionRedriveLease,
        position: Long,
    ): ProjectionRedriveAcknowledge {
        requireLease(lease)
        require(position > 0) { "projection redrive acknowledgement position is positive" }
        val hold = lockRedriveHold(lease) ?: return ProjectionRedriveAcknowledge.LostLease
        val head =
            dsl.fetchOne(
                LOCK_HEAD_LETTER,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.sequence.copy(),
            ) ?: error("projection hold has no queue head")
        val actual = checkNotNull(head.get("position", Long::class.java))
        require(actual == position) { "projection redrive may only acknowledge the queue head" }
        val bytes = retainedBytes(head)
        dsl.execute(
            DELETE_LETTER,
            lease.lane.projection.text(),
            lease.lane.generation.value,
            lease.lane.partition.depth,
            lease.lane.partition.prefix,
            lease.sequence.copy(),
            position,
        )
        val remaining = hold.letterCount - 1
        check(remaining >= 0 && hold.letterBytes >= bytes) { "projection hold counters do not match stored letters" }
        if (remaining == 0) {
            dsl.execute(
                DELETE_EMPTY_HOLD,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.sequence.copy(),
                lease.token,
                lease.fence,
            )
        } else {
            dsl.execute(
                DECREMENT_HOLD,
                bytes,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.sequence.copy(),
                lease.token,
                lease.fence,
            )
        }
        return ProjectionRedriveAcknowledge.Advanced(remaining)
    }

    override fun fail(
        lease: ProjectionRedriveLease,
        failure: ProjectionFailureCode,
    ): ProjectionRedriveFailure {
        requireLease(lease)
        val hold = lockRedriveHold(lease) ?: return ProjectionRedriveFailure.LOST_LEASE
        val head =
            dsl.fetchOne(
                LOCK_HEAD_LETTER,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.sequence.copy(),
            ) ?: error("projection hold has no queue head")
        dsl.execute(
            RECORD_REDRIVE_FAILURE,
            failure.text(),
            lease.lane.projection.text(),
            lease.lane.generation.value,
            lease.lane.partition.depth,
            lease.lane.partition.prefix,
            lease.sequence.copy(),
            checkNotNull(head.get("position", Long::class.java)),
        )
        dsl.execute(
            RELEASE_REDRIVE,
            lease.lane.projection.text(),
            lease.lane.generation.value,
            lease.lane.partition.depth,
            lease.lane.partition.prefix,
            lease.sequence.copy(),
            lease.token,
            hold.fence,
        )
        return ProjectionRedriveFailure.RECORDED
    }

    override fun release(lease: ProjectionRedriveLease): ProjectionRedriveRelease {
        requireLease(lease)
        val released =
            dsl.execute(
                RELEASE_REDRIVE,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.sequence.copy(),
                lease.token,
                lease.fence,
            )
        return if (released == 1) ProjectionRedriveRelease.RELEASED else ProjectionRedriveRelease.LOST_LEASE
    }

    override fun evict(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoldEviction {
        requireReason(reason)
        val hold = lockHoldNowait(lane, sequence) ?: return ProjectionHoldEviction.Missing
        if (hold.state == ProjectionHoldState.REDRIVING && activeRedrive(hold)) {
            return ProjectionHoldEviction.Busy(checkNotNull(redriveUntil(lane, sequence)))
        }
        val range =
            dsl.fetchOne(
                LETTER_RANGE,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                sequence.copy(),
            ) ?: error("projection hold has no retained letters")
        val hole =
            dsl.fetchOne(
                INSERT_HOLE,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                sequence.copy(),
                checkNotNull(range.get("first_position", Long::class.java)),
                checkNotNull(range.get("last_position", Long::class.java)),
                operator.type,
                operator.id,
                reason,
            ) ?: error("projection hole insert did not return its record")
        dsl.execute(
            DELETE_HOLD,
            lane.projection.text(),
            lane.generation.value,
            lane.partition.depth,
            lane.partition.prefix,
            sequence.copy(),
        )
        return ProjectionHoldEviction.Evicted(hole(hole))
    }

    override fun acknowledgeHole(
        id: ProjectionHoleId,
        operator: ProjectionOperator,
        reason: String,
    ): ProjectionHoleAcknowledgement {
        requireReason(reason)
        val acknowledged = dsl.fetchOne(ACKNOWLEDGE_HOLE, operator.type, operator.id, reason, id.value)
        if (acknowledged != null) return ProjectionHoleAcknowledgement.Acknowledged(hole(acknowledged))
        val exists = dsl.fetchOne(FETCH_HOLE, id.value) ?: return ProjectionHoleAcknowledgement.Missing
        return if (exists.get("acknowledged_at", Instant::class.java) == null) {
            error("projection hole acknowledgement lost its conditional update")
        } else {
            ProjectionHoleAcknowledgement.AlreadyAcknowledged
        }
    }

    override fun holds(
        lane: ProjectionLane,
        limit: Int,
    ): List<ProjectionHold> {
        require(limit in 1..MAX_HOLD_STATUS) { "projection hold status limit is outside 1..$MAX_HOLD_STATUS" }
        return dsl
            .fetch(
                READ_HOLDS,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                origin.logId.copy(),
                limit,
            ).map(::hold)
    }

    private fun currentCheckpointLease(lease: com.gd.rain.event.projection.ProjectionLease): Boolean =
        dsl.fetchOne(
            CURRENT_CHECKPOINT_LEASE,
            lease.lane.projection.text(),
            lease.lane.generation.value,
            lease.lane.partition.depth,
            lease.lane.partition.prefix,
            origin.logId.copy(),
            lease.token,
            lease.fence,
        ) != null

    private fun currentRedriveLease(lease: ProjectionRedriveLease): Boolean =
        dsl.fetchOne(
            CURRENT_REDRIVE_LEASE,
            lease.lane.projection.text(),
            lease.lane.generation.value,
            lease.lane.partition.depth,
            lease.lane.partition.prefix,
            lease.sequence.copy(),
            lease.token,
            lease.fence,
        ) != null

    private fun lockHold(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
    ): ProjectionHold? =
        dsl
            .fetchOne(
                LOCK_HOLD,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                sequence.copy(),
            )?.let(::hold)

    private fun lockHoldNowait(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
    ): ProjectionHold? =
        dsl
            .fetchOne(
                LOCK_HOLD_NOWAIT,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                sequence.copy(),
            )?.let(::hold)

    private fun lockRedriveHold(lease: ProjectionRedriveLease): ProjectionHold? =
        dsl
            .fetchOne(
                LOCK_REDRIVE_HOLD,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.sequence.copy(),
                lease.token,
                lease.fence,
            )?.let(::hold)

    private fun letterRow(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
        position: Long,
    ): Record? =
        dsl.fetchOne(
            FETCH_LETTER,
            lane.projection.text(),
            lane.generation.value,
            lane.partition.depth,
            lane.partition.prefix,
            sequence.copy(),
            position,
        )

    private fun lastLetterPosition(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
    ): Long? =
        dsl
            .fetchOne(
                LAST_LETTER_POSITION,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                sequence.copy(),
            )?.get("position", Long::class.java)

    private fun hold(row: Record): ProjectionHold =
        ProjectionHold(
            lane(row),
            sequence(row),
            checkNotNull(row.get("first_position", Long::class.java)),
            ProjectionFailureCode.of(checkNotNull(row.get("failure_code", String::class.java))),
            when (checkNotNull(row.get("state", String::class.java))) {
                "held" -> ProjectionHoldState.HELD
                "redriving" -> ProjectionHoldState.REDRIVING
                else -> error("projection hold state is invalid")
            },
            checkNotNull(row.get("letter_count", Int::class.java)),
            checkNotNull(row.get("letter_bytes", Int::class.java)),
            checkNotNull(row.get("fence", Long::class.java)),
            checkNotNull(row.get("created_at", Instant::class.java)),
            checkNotNull(row.get("updated_at", Instant::class.java)),
        )

    private fun lane(row: Record): ProjectionLane =
        ProjectionLane(
            com.gd.rain.event.projection.ProjectionName
                .of(checkNotNull(row.get("projection_name", String::class.java))),
            com.gd.rain.event.projection
                .ProjectionGeneration(checkNotNull(row.get("generation", Long::class.java))),
            com.gd.rain.event.projection.ProjectionPartition(
                checkNotNull(row.get("partition_depth", Int::class.java)),
                checkNotNull(row.get("partition_prefix", Long::class.java)),
            ),
        )

    private fun sequence(row: Record): ProjectionSequenceId =
        ProjectionSequenceId.fromDigest(checkNotNull(row.get("sequence_digest", ByteArray::class.java)))

    private fun redriveLetter(row: Record): ProjectionRedriveLetter {
        val sequence = ProjectionSequenceId.fromDigest(checkNotNull(row.get("sequence_digest", ByteArray::class.java)))
        val event =
            StoredEvent(
                StreamRef(
                    EventNamespace.of(checkNotNull(row.get("namespace", ByteArray::class.java))),
                    checkNotNull(row.get("family", String::class.java)),
                    checkNotNull(row.get("stream_key", String::class.java)),
                ),
                checkNotNull(row.get("stream_version", Long::class.java)),
                checkNotNull(row.get("position", Long::class.java)),
                FactType(
                    checkNotNull(row.get("type", String::class.java)),
                    checkNotNull(row.get("revision", Int::class.java)),
                ),
                EventBytes.of(checkNotNull(row.get("payload", ByteArray::class.java))),
                EventBytes.of(checkNotNull(row.get("metadata", ByteArray::class.java))),
                checkNotNull(row.get("recorded_at", Instant::class.java)),
            )
        val letter = ProjectionLetter(sequence, event)
        check(letter.checksum == EventBytes.of(checkNotNull(row.get("checksum", ByteArray::class.java)))) {
            "projection letter checksum does not match its immutable envelope"
        }
        val code = row.get("last_failure_code", String::class.java)?.let(ProjectionFailureCode::of)
        return ProjectionRedriveLetter(
            letter,
            checkNotNull(row.get("attempts", Int::class.java)),
            code,
            row.get("last_failed_at", Instant::class.java),
        )
    }

    private fun hole(row: Record): ProjectionHole =
        ProjectionHole(
            ProjectionHoleId(checkNotNull(row.get("hole_id", Long::class.java))),
            lane(row),
            sequence(row),
            checkNotNull(row.get("first_position", Long::class.java)),
            checkNotNull(row.get("last_position", Long::class.java)),
            ProjectionOperator(
                checkNotNull(row.get("operator_type", String::class.java)),
                checkNotNull(row.get("operator_id", String::class.java)),
            ),
            checkNotNull(row.get("reason", String::class.java)),
            checkNotNull(row.get("created_at", Instant::class.java)),
            row.get("acknowledged_at", Instant::class.java),
        )

    private fun retainedBytes(row: Record): Int =
        ProjectionLetter(
            ProjectionSequenceId.fromDigest(checkNotNull(row.get("sequence_digest", ByteArray::class.java))),
            StoredEvent(
                StreamRef(
                    EventNamespace.of(checkNotNull(row.get("namespace", ByteArray::class.java))),
                    checkNotNull(row.get("family", String::class.java)),
                    checkNotNull(row.get("stream_key", String::class.java)),
                ),
                checkNotNull(row.get("stream_version", Long::class.java)),
                checkNotNull(row.get("position", Long::class.java)),
                FactType(checkNotNull(row.get("type", String::class.java)), checkNotNull(row.get("revision", Int::class.java))),
                EventBytes.of(checkNotNull(row.get("payload", ByteArray::class.java))),
                EventBytes.of(checkNotNull(row.get("metadata", ByteArray::class.java))),
                checkNotNull(row.get("recorded_at", Instant::class.java)),
            ),
        ).retainedBytes

    private fun activeRedrive(hold: ProjectionHold): Boolean =
        hold.state == ProjectionHoldState.REDRIVING && redriveUntil(hold.lane, hold.sequence)?.isAfter(Instant.now()) == true

    private fun redriveUntil(
        lane: ProjectionLane,
        sequence: ProjectionSequenceId,
    ): Instant? =
        dsl
            .fetchOne(
                REDRIVE_UNTIL,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                sequence.copy(),
            )?.get("lease_until", Instant::class.java)

    private fun requireLease(lease: ProjectionRedriveLease): Unit = requireRedriveLease(lease)

    private fun requireReason(reason: String): Unit =
        require(reason.isNotBlank() && reason.toByteArray(Charsets.UTF_8).size <= ProjectionHole.MAX_REASON_BYTES) {
            "projection hole reason is blank or too large"
        }

    private companion object {
        const val MAX_REDRIVE_LETTERS: Int = 1_000
        const val MAX_HOLD_STATUS: Int = 1_000

        const val CURRENT_CHECKPOINT_LEASE: String = """
            SELECT 1
            FROM rain_event.projection_checkpoint
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND log_id = ? AND lease_token = ? AND fence = ? AND lease_until > statement_timestamp()
            """

        const val INSERT_HOLD: String = """
            INSERT INTO rain_event.projection_hold(
              projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position, failure_code, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'held')
            """

        const val LOCK_HOLD: String = """
            SELECT projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position, failure_code,
                   state, letter_count, letter_bytes, fence, created_at, updated_at
            FROM rain_event.projection_hold
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            FOR UPDATE
            """

        const val LOCK_HOLD_NOWAIT: String = """
            SELECT projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position, failure_code,
                   state, letter_count, letter_bytes, fence, created_at, updated_at
            FROM rain_event.projection_hold
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            FOR UPDATE NOWAIT
            """

        const val FETCH_LETTER: String = """
            SELECT checksum
            FROM rain_event.projection_letter
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND position = ?
            """

        const val LAST_LETTER_POSITION: String = """
            SELECT position
            FROM rain_event.projection_letter
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            ORDER BY position DESC
            LIMIT 1
            """

        const val INSERT_LETTER: String = """
            INSERT INTO rain_event.projection_letter(
              projection_name, generation, partition_depth, partition_prefix, sequence_digest, position, namespace, family,
              stream_key, stream_version, type, revision, payload, metadata, recorded_at, checksum
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?)
            """

        const val INCREMENT_HOLD: String = """
            UPDATE rain_event.projection_hold
            SET letter_count = letter_count + 1, letter_bytes = letter_bytes + ?, updated_at = statement_timestamp()
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            RETURNING projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position, failure_code,
                      state, letter_count, letter_bytes, fence, created_at, updated_at
            """

        const val CLAIM_REDRIVE: String = """
            WITH candidate AS (
              SELECT h.ctid
              FROM rain_event.projection_hold h
              JOIN rain_event.projection_checkpoint c
                ON c.projection_name = h.projection_name
               AND c.generation = h.generation
               AND c.partition_depth = h.partition_depth
               AND c.partition_prefix = h.partition_prefix
              WHERE h.projection_name = ? AND h.generation = ? AND h.partition_depth = ? AND h.partition_prefix = ?
                AND c.log_id = ?
                AND (h.state = 'held' OR h.lease_until <= statement_timestamp())
              ORDER BY h.first_position, h.sequence_digest
              FOR UPDATE OF h SKIP LOCKED
              LIMIT 1
            )
            UPDATE rain_event.projection_hold h
            SET state = 'redriving', fence = h.fence + 1, lease_token = ?,
                lease_until = statement_timestamp() + (? * interval '1 millisecond'), updated_at = statement_timestamp()
            FROM candidate
            WHERE h.ctid = candidate.ctid
            RETURNING h.projection_name, h.generation, h.partition_depth, h.partition_prefix, h.sequence_digest, h.first_position,
                      h.failure_code, h.state, h.letter_count, h.letter_bytes, h.fence, h.created_at, h.updated_at
            """

        const val BUSY_REDRIVE: String = """
            SELECT min(h.lease_until) AS lease_until
            FROM rain_event.projection_hold h
            JOIN rain_event.projection_checkpoint c
              ON c.projection_name = h.projection_name
             AND c.generation = h.generation
             AND c.partition_depth = h.partition_depth
             AND c.partition_prefix = h.partition_prefix
            WHERE h.projection_name = ? AND h.generation = ? AND h.partition_depth = ? AND h.partition_prefix = ?
              AND c.log_id = ? AND h.state = 'redriving' AND h.lease_until > statement_timestamp()
            """

        const val CURRENT_REDRIVE_LEASE: String = """
            SELECT 1
            FROM rain_event.projection_hold
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND state = 'redriving' AND lease_token = ? AND fence = ?
              AND lease_until > statement_timestamp()
            """

        const val READ_LETTERS: String = """
            SELECT sequence_digest, position, namespace, family, stream_key, stream_version, type, revision, payload, metadata,
                   recorded_at, checksum, attempts, last_failure_code, last_failed_at
            FROM rain_event.projection_letter
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            ORDER BY position
            LIMIT ?
            """

        const val LOCK_REDRIVE_HOLD: String = """
            SELECT projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position, failure_code,
                   state, letter_count, letter_bytes, fence, created_at, updated_at
            FROM rain_event.projection_hold
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND state = 'redriving' AND lease_token = ? AND fence = ?
              AND lease_until > statement_timestamp()
            FOR UPDATE
            """

        const val LOCK_HEAD_LETTER: String = """
            SELECT sequence_digest, position, namespace, family, stream_key, stream_version, type, revision, payload, metadata, recorded_at
            FROM rain_event.projection_letter
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            ORDER BY position
            LIMIT 1
            FOR UPDATE
            """

        const val DELETE_LETTER: String = """
            DELETE FROM rain_event.projection_letter
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND position = ?
            """

        const val DELETE_EMPTY_HOLD: String = """
            DELETE FROM rain_event.projection_hold
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND lease_token = ? AND fence = ? AND state = 'redriving'
            """

        const val DECREMENT_HOLD: String = """
            UPDATE rain_event.projection_hold
            SET letter_count = letter_count - 1, letter_bytes = letter_bytes - ?, updated_at = statement_timestamp()
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND lease_token = ? AND fence = ? AND state = 'redriving'
            """

        const val RECORD_REDRIVE_FAILURE: String = """
            UPDATE rain_event.projection_letter
            SET attempts = attempts + 1, last_failure_code = ?, last_failed_at = statement_timestamp()
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND position = ?
            """

        const val RELEASE_REDRIVE: String = """
            UPDATE rain_event.projection_hold
            SET state = 'held', lease_token = NULL, lease_until = NULL, updated_at = statement_timestamp()
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND sequence_digest = ? AND lease_token = ? AND fence = ? AND state = 'redriving'
            """

        const val LETTER_RANGE: String = """
            SELECT min(position) AS first_position, max(position) AS last_position
            FROM rain_event.projection_letter
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            """

        const val INSERT_HOLE: String = """
            INSERT INTO rain_event.projection_hole(
              projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position, last_position,
              operator_type, operator_id, reason
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING hole_id, projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position,
                      last_position, operator_type, operator_id, reason, created_at, acknowledged_at
            """

        const val DELETE_HOLD: String = """
            DELETE FROM rain_event.projection_hold
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            """

        const val ACKNOWLEDGE_HOLE: String = """
            UPDATE rain_event.projection_hole
            SET acknowledged_at = statement_timestamp(), acknowledged_operator_type = ?, acknowledged_operator_id = ?, acknowledged_reason = ?
            WHERE hole_id = ? AND acknowledged_at IS NULL
            RETURNING hole_id, projection_name, generation, partition_depth, partition_prefix, sequence_digest, first_position,
                      last_position, operator_type, operator_id, reason, created_at, acknowledged_at
            """

        const val FETCH_HOLE: String = """
            SELECT hole_id, acknowledged_at
            FROM rain_event.projection_hole
            WHERE hole_id = ?
            """

        const val READ_HOLDS: String = """
            SELECT h.projection_name, h.generation, h.partition_depth, h.partition_prefix, h.sequence_digest, h.first_position,
                   h.failure_code, h.state, h.letter_count, h.letter_bytes, h.fence, h.created_at, h.updated_at
            FROM rain_event.projection_hold h
            JOIN rain_event.projection_checkpoint c
              ON c.projection_name = h.projection_name
             AND c.generation = h.generation
             AND c.partition_depth = h.partition_depth
             AND c.partition_prefix = h.partition_prefix
            WHERE h.projection_name = ? AND h.generation = ? AND h.partition_depth = ? AND h.partition_prefix = ? AND c.log_id = ?
            ORDER BY h.created_at, h.sequence_digest
            LIMIT ?
            """

        const val REDRIVE_UNTIL: String = """
            SELECT lease_until
            FROM rain_event.projection_hold
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND sequence_digest = ?
            """
    }
}
