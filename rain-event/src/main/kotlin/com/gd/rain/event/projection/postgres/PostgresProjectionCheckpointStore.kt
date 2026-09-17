package com.gd.rain.event.projection.postgres

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventLogSettlement
import com.gd.rain.event.projection.ProjectionAdvance
import com.gd.rain.event.projection.ProjectionCheckpoint
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionCheckpointStoreSupport
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionHalt
import com.gd.rain.event.projection.ProjectionHaltResult
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLease
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionTopologyFingerprint
import com.gd.rain.event.projection.ProjectionTransactionPlacement
import com.gd.rain.event.projection.SameUnitProjectionCheckpointStore
import com.gd.rain.event.projection.SameUnitProjectionHaltStore
import com.gd.rain.persistence.tx.TransactionPlacement
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL durable checkpoint and fenced lease adapter for an explicit event-log origin.
 *
 * Every state mutation is a conditional single statement and uses database statement time for the
 * lease. It is suitable for AFTER_APPLY destinations: the handler remains at-least-once because it
 * executes between [claim] and [advance], outside this checkpoint transaction.
 */
public class PostgresProjectionCheckpointStore(
    private val dsl: DSLContext,
    private val origin: EventLogOrigin,
    private val placement: ProjectionTransactionPlacement? = null,
) : ProjectionCheckpointStoreSupport(),
    SameUnitProjectionHaltStore {
    public constructor(
        dsl: DSLContext,
        origin: EventLogOrigin,
        transactionManager: PlatformTransactionManager,
    ) : this(dsl, origin, ProjectionTransactionPlacement { TransactionPlacement.inspect(dsl, transactionManager) })

    override fun inspectPlacement(): TransactionPlacement =
        checkNotNull(placement) { "projection checkpoint store was not configured for same-unit verification" }.inspect()

    override fun claim(
        lane: ProjectionLane,
        contract: ProjectionCheckpointContract,
        initialCursor: EventLogCursor,
        now: Instant,
        leaseFor: Duration,
    ): ProjectionClaim {
        require(contract.origin == origin) { "projection checkpoint contract belongs to another log" }
        require(initialCursor.origin == origin) { "projection initial cursor belongs to another log" }
        require(!leaseFor.isNegative && !leaseFor.isZero) { "projection lease duration is positive" }
        when (topologyAdmission(lane, contract)) {
            TopologyAdmission.ALLOWED -> Unit
            TopologyAdmission.RETIRED -> return ProjectionClaim.Retired
            TopologyAdmission.CONTRACT_DRIFT -> return ProjectionClaim.ContractDrift
        }
        halted(lane)?.let { return ProjectionClaim.Halted(it) }
        val leaseMillis = leaseFor.toMillis().also { require(it > 0) { "projection lease duration is below one millisecond" } }
        val createdLease = newLease(lane, 1)
        val created =
            dsl.fetchOne(
                INSERT_CLAIM,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                origin.logId.copy(),
                contract.revision.value,
                contract.topology.copy(),
                initialCursor.deliveredPosition,
                initialCursor.settlement.boundXid,
                initialCursor.settlement.reach,
                createdLease.token,
                leaseMillis,
            )
        if (created != null) return ProjectionClaim.Acquired(checkpoint(created), createdLease)

        val candidate = newLease(lane, 1)
        val reclaimed =
            dsl.fetchOne(
                RECLAIM_EXPIRED,
                candidate.token,
                leaseMillis,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                origin.logId.copy(),
                contract.revision.value,
                contract.topology.copy(),
            )
        if (reclaimed != null) {
            val checkpoint = checkpoint(reclaimed)
            return ProjectionClaim.Acquired(checkpoint, withFence(candidate, checkpoint.fence))
        }

        halted(lane)?.let { return ProjectionClaim.Halted(it) }
        val existing = checkNotNull(fetch(lane)) { "projection checkpoint disappeared during claim" }
        return if (!matchesOrigin(existing) || checkpoint(existing).contract != contract) {
            ProjectionClaim.ContractDrift
        } else {
            val leaseUntil = existing.get("lease_until", Instant::class.java)
            if (leaseUntil == null) {
                ProjectionClaim.Busy(now)
            } else {
                ProjectionClaim.Busy(leaseUntil)
            }
        }
    }

    override fun advance(
        lease: ProjectionLease,
        next: EventLogCursor,
        now: Instant,
    ): ProjectionAdvance {
        requireLease(lease)
        require(next.origin == origin) { "projection cursor belongs to another log" }
        val checkpoint = checkpoint(lease.lane) ?: return ProjectionAdvance.LostLease
        require(next.deliveredPosition >= checkpoint.cursor.deliveredPosition) { "projection checkpoint cursor cannot move backward" }
        val advanced =
            dsl.fetchOne(
                ADVANCE,
                next.deliveredPosition,
                next.settlement.boundXid,
                next.settlement.reach,
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                lease.token,
                lease.fence,
            ) ?: return ProjectionAdvance.LostLease
        return ProjectionAdvance.Advanced(checkpoint(advanced))
    }

    override fun release(lease: ProjectionLease) {
        if (!owns(lease)) return
        dsl.execute(
            RELEASE,
            lease.lane.projection.text(),
            lease.lane.generation.value,
            lease.lane.partition.depth,
            lease.lane.partition.prefix,
            lease.token,
            lease.fence,
        )
    }

    override fun checkpoint(lane: ProjectionLane): ProjectionCheckpoint? = fetch(lane)?.let(::checkpoint)

    override fun halt(
        lease: ProjectionLease,
        position: Long,
        failure: com.gd.rain.event.projection.ProjectionFailureCode,
    ): ProjectionHaltResult {
        requireLease(lease)
        require(position > 0) { "projection halt position is positive" }
        val inserted =
            dsl.fetchOne(
                INSERT_HALT,
                position,
                failure.text(),
                lease.lane.projection.text(),
                lease.lane.generation.value,
                lease.lane.partition.depth,
                lease.lane.partition.prefix,
                origin.logId.copy(),
                lease.token,
                lease.fence,
                position,
            )
        if (inserted != null) return ProjectionHaltResult.Halted(halt(inserted))
        return halted(lease.lane)?.let(ProjectionHaltResult::Halted) ?: ProjectionHaltResult.LostLease
    }

    private fun fetch(lane: ProjectionLane): Record? =
        dsl.fetchOne(
            FETCH,
            lane.projection.text(),
            lane.generation.value,
            lane.partition.depth,
            lane.partition.prefix,
        )

    private fun halted(lane: ProjectionLane): ProjectionHalt? =
        dsl
            .fetchOne(
                FETCH_HALT,
                lane.projection.text(),
                lane.generation.value,
                lane.partition.depth,
                lane.partition.prefix,
                origin.logId.copy(),
            )?.let(::halt)

    private fun halt(row: Record): ProjectionHalt =
        ProjectionHalt(
            ProjectionLane(
                ProjectionName.of(checkNotNull(row.get("projection_name", String::class.java))),
                ProjectionGeneration(checkNotNull(row.get("generation", Long::class.java))),
                ProjectionPartition(
                    checkNotNull(row.get("partition_depth", Int::class.java)),
                    checkNotNull(row.get("partition_prefix", Long::class.java)),
                ),
            ),
            checkNotNull(row.get("position", Long::class.java)),
            com.gd.rain.event.projection.ProjectionFailureCode
                .of(checkNotNull(row.get("failure_code", String::class.java))),
            checkNotNull(row.get("created_at", Instant::class.java)),
        )

    private fun checkpoint(row: Record): ProjectionCheckpoint {
        require(matchesOrigin(row)) { "projection checkpoint belongs to another event log" }
        val lane =
            ProjectionLane(
                ProjectionName.of(checkNotNull(row.get("projection_name", String::class.java))),
                ProjectionGeneration(checkNotNull(row.get("generation", Long::class.java))),
                ProjectionPartition(
                    checkNotNull(row.get("partition_depth", Int::class.java)),
                    checkNotNull(row.get("partition_prefix", Long::class.java)),
                ),
            )
        val contract =
            ProjectionCheckpointContract(
                origin,
                ProjectionContractRevision(checkNotNull(row.get("contract_revision", Int::class.java))),
                ProjectionTopologyFingerprint.of(checkNotNull(row.get("topology_fingerprint", ByteArray::class.java))),
            )
        return ProjectionCheckpoint(
            lane,
            contract,
            EventLogCursor(
                origin,
                checkNotNull(row.get("cursor_position", Long::class.java)),
                EventLogSettlement(
                    row.get("cursor_bound_xid", String::class.java),
                    checkNotNull(row.get("cursor_reach", Long::class.java)),
                ),
            ),
            checkNotNull(row.get("fence", Long::class.java)),
            checkNotNull(row.get("updated_at", Instant::class.java)),
        )
    }

    private fun matchesOrigin(row: Record): Boolean =
        EventLogId.of(checkNotNull(row.get("log_id", ByteArray::class.java)) { "projection checkpoint has no log id" }) == origin.logId

    private fun topologyAdmission(
        lane: ProjectionLane,
        contract: ProjectionCheckpointContract,
    ): TopologyAdmission {
        val row =
            dsl.fetchOne(
                TOPOLOGY_MEMBER,
                lane.partition.depth,
                lane.partition.prefix,
                lane.projection.text(),
                lane.generation.value,
            ) ?: return TopologyAdmission.ALLOWED
        if (row.get("state", String::class.java) == "retired") return TopologyAdmission.RETIRED
        if (row.get("state", String::class.java) != "live") return TopologyAdmission.CONTRACT_DRIFT
        return if (
            EventLogId.of(checkNotNull(row.get("log_id", ByteArray::class.java))) == contract.origin.logId &&
            row.get("contract_revision", Int::class.java) == contract.revision.value &&
            ProjectionTopologyFingerprint.of(checkNotNull(row.get("topology_fingerprint", ByteArray::class.java))) == contract.topology
        ) {
            TopologyAdmission.ALLOWED
        } else {
            TopologyAdmission.CONTRACT_DRIFT
        }
    }

    private companion object {
        enum class TopologyAdmission {
            ALLOWED,
            RETIRED,
            CONTRACT_DRIFT,
        }

        const val INSERT_CLAIM: String = """
            INSERT INTO rain_event.projection_checkpoint(
              projection_name, generation, partition_depth, partition_prefix, log_id, contract_revision,
              topology_fingerprint, cursor_position, cursor_bound_xid, cursor_reach, fence, lease_token, lease_until
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, statement_timestamp() + (? * interval '1 millisecond'))
            ON CONFLICT (projection_name, generation, partition_depth, partition_prefix) DO NOTHING
            RETURNING projection_name, generation, partition_depth, partition_prefix, log_id, contract_revision, topology_fingerprint,
                      cursor_position, cursor_bound_xid, cursor_reach, fence, updated_at
            """

        const val RECLAIM_EXPIRED: String = """
            UPDATE rain_event.projection_checkpoint
            SET fence = fence + 1,
                lease_token = ?,
                lease_until = statement_timestamp() + (? * interval '1 millisecond'),
                updated_at = statement_timestamp()
            WHERE projection_name = ?
              AND generation = ?
              AND partition_depth = ?
              AND partition_prefix = ?
              AND log_id = ?
              AND contract_revision = ?
              AND topology_fingerprint = ?
              AND (lease_until IS NULL OR lease_until <= statement_timestamp())
            RETURNING projection_name, generation, partition_depth, partition_prefix, log_id, contract_revision, topology_fingerprint,
                      cursor_position, cursor_bound_xid, cursor_reach, fence, updated_at
            """

        const val FETCH: String = """
            SELECT projection_name, generation, partition_depth, partition_prefix, log_id, contract_revision,
                   topology_fingerprint, cursor_position, cursor_bound_xid, cursor_reach, fence, lease_until, updated_at
            FROM rain_event.projection_checkpoint
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
            """

        const val TOPOLOGY_MEMBER: String = """
            SELECT topology.log_id, topology.contract_revision, topology.topology_fingerprint, member.state
            FROM rain_event.projection_topology topology
            LEFT JOIN rain_event.projection_topology_member member
              ON member.projection_name = topology.projection_name
             AND member.generation = topology.generation
             AND member.partition_depth = ?
             AND member.partition_prefix = ?
            WHERE topology.projection_name = ? AND topology.generation = ?
            """

        const val ADVANCE: String = """
            UPDATE rain_event.projection_checkpoint
            SET cursor_position = ?,
                cursor_bound_xid = ?,
                cursor_reach = ?,
                lease_token = NULL,
                lease_until = NULL,
                updated_at = statement_timestamp()
            WHERE projection_name = ?
              AND generation = ?
              AND partition_depth = ?
              AND partition_prefix = ?
              AND lease_token = ?
              AND fence = ?
              AND lease_until > statement_timestamp()
            RETURNING projection_name, generation, partition_depth, partition_prefix, log_id, contract_revision, topology_fingerprint,
                      cursor_position, cursor_bound_xid, cursor_reach, fence, updated_at
            """

        const val RELEASE: String = """
            UPDATE rain_event.projection_checkpoint
            SET lease_token = NULL, lease_until = NULL, updated_at = statement_timestamp()
            WHERE projection_name = ?
              AND generation = ?
              AND partition_depth = ?
              AND partition_prefix = ?
              AND lease_token = ?
              AND fence = ?
            """

        const val FETCH_HALT: String = """
            SELECT h.projection_name, h.generation, h.partition_depth, h.partition_prefix, h.position, h.failure_code, h.created_at
            FROM rain_event.projection_halt h
            JOIN rain_event.projection_checkpoint c
              ON c.projection_name = h.projection_name
             AND c.generation = h.generation
             AND c.partition_depth = h.partition_depth
             AND c.partition_prefix = h.partition_prefix
            WHERE h.projection_name = ? AND h.generation = ? AND h.partition_depth = ? AND h.partition_prefix = ? AND c.log_id = ?
            """

        const val INSERT_HALT: String = """
            INSERT INTO rain_event.projection_halt(
              projection_name, generation, partition_depth, partition_prefix, position, failure_code
            )
            SELECT projection_name, generation, partition_depth, partition_prefix, ?, ?
            FROM rain_event.projection_checkpoint
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
              AND log_id = ? AND lease_token = ? AND fence = ? AND lease_until > statement_timestamp()
              AND cursor_position < ?
            ON CONFLICT (projection_name, generation, partition_depth, partition_prefix) DO NOTHING
            RETURNING projection_name, generation, partition_depth, partition_prefix, position, failure_code, created_at
            """
    }
}
