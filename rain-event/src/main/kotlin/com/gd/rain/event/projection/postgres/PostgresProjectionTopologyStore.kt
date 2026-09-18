package com.gd.rain.event.projection.postgres

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventLogSettlement
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionTopology
import com.gd.rain.event.projection.ProjectionTopologyBlockers
import com.gd.rain.event.projection.ProjectionTopologyDeclaration
import com.gd.rain.event.projection.ProjectionTopologyMember
import com.gd.rain.event.projection.ProjectionTopologyMemberState
import com.gd.rain.event.projection.ProjectionTopologyRegistration
import com.gd.rain.event.projection.ProjectionTopologyRetirement
import com.gd.rain.event.projection.ProjectionTopologySplit
import com.gd.rain.event.projection.ProjectionTopologyStore
import com.gd.rain.event.projection.ProjectionTransactionPlacement
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.persistence.tx.TransactionPlacement
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant

/**
 * PostgreSQL implementation of the durable one-way projection split protocol.
 *
 * `register` and `split` require the caller's transaction. This adapter opens no transaction and never retries a
 * command: row locks, child checkpoints, parent retirement and the generation's current topology fingerprint commit
 * or roll back together.
 */
public class PostgresProjectionTopologyStore(
    private val dsl: DSLContext,
    private val placement: ProjectionTransactionPlacement? = null,
) : ProjectionTopologyStore {
    public constructor(
        dsl: DSLContext,
        transactionManager: PlatformTransactionManager,
    ) : this(dsl, ProjectionTransactionPlacement { TransactionPlacement.inspect(dsl, transactionManager) })

    override fun register(declaration: ProjectionTopologyDeclaration): ProjectionTopologyRegistration {
        requireTransaction()
        if (!generationMatches(declaration, lock = true)) return ProjectionTopologyRegistration.Conflict
        val inserted =
            dsl.execute(
                INSERT_TOPOLOGY,
                declaration.projection.text(),
                declaration.generation.value,
                declaration.contract.origin.logId
                    .copy(),
                declaration.contract.revision.value,
                declaration.cover.hasher.id
                    .text(),
                declaration.contract.topology.copy(),
            )
        if (inserted == 1) {
            declaration.cover.members.forEach { member ->
                check(
                    dsl.execute(
                        INSERT_MEMBER,
                        declaration.projection.text(),
                        declaration.generation.value,
                        member.depth,
                        member.prefix,
                    ) == 1,
                ) { "projection topology member insert did not create its declared partition" }
            }
        }
        val topology =
            checkNotNull(topology(declaration.projection, declaration.generation)) {
                "projection topology disappeared during registration"
            }
        return if (matches(topology, declaration)) {
            if (inserted == 1) ProjectionTopologyRegistration.Created(topology) else ProjectionTopologyRegistration.Repeated(topology)
        } else {
            ProjectionTopologyRegistration.Conflict
        }
    }

    override fun topology(
        projection: com.gd.rain.event.projection.ProjectionName,
        generation: ProjectionGeneration,
    ): ProjectionTopology? {
        val header = dsl.fetchOne(FETCH_TOPOLOGY, projection.text(), generation.value) ?: return null
        val members =
            dsl
                .fetch(FETCH_MEMBERS, projection.text(), generation.value)
                .map(::member)
        return topology(header, members)
    }

    override fun split(
        declaration: ProjectionTopologyDeclaration,
        parent: ProjectionPartition,
    ): ProjectionTopologySplit {
        requireTransaction()
        if (!generationMatches(declaration, lock = true)) return ProjectionTopologySplit.ContractDrift
        val registered = register(declaration)
        if (registered is ProjectionTopologyRegistration.Conflict) return ProjectionTopologySplit.ContractDrift
        val current = lockTopology(declaration) ?: return ProjectionTopologySplit.ContractDrift
        if (!matches(current, declaration)) return ProjectionTopologySplit.ContractDrift
        val parentMember = current.members.singleOrNull { it.partition == parent } ?: return ProjectionTopologySplit.ContractDrift
        if (parentMember.state != ProjectionTopologyMemberState.LIVE) return ProjectionTopologySplit.ContractDrift
        val nextCover = declaration.cover.split(parent)
        val children = parent.split()
        val parentCheckpoint =
            lockCheckpoint(ProjectionLane(declaration.projection, declaration.generation, parent))
                ?: return ProjectionTopologySplit.ParentMissing
        if (!checkpointMatches(parentCheckpoint, declaration.contract)) return ProjectionTopologySplit.ContractDrift
        val leaseUntil = parentCheckpoint.get("lease_until", Instant::class.java)
        if (parentCheckpoint.get("lease_active", Boolean::class.java) == true) {
            return ProjectionTopologySplit.Busy(checkNotNull(leaseUntil) { "active projection checkpoint has no lease expiry" })
        }
        blockers(declaration.projection, declaration.generation, parent)?.let { return ProjectionTopologySplit.Blocked(it) }

        val inherited = cursor(parentCheckpoint, declaration.contract.origin)
        val parentFence = checkNotNull(parentCheckpoint.get("fence", Long::class.java))
        children.toList().forEach { child ->
            check(
                dsl.execute(
                    INSERT_CHILD_CHECKPOINT,
                    declaration.projection.text(),
                    declaration.generation.value,
                    child.depth,
                    child.prefix,
                    declaration.contract.origin.logId
                        .copy(),
                    declaration.contract.revision.value,
                    nextCover.fingerprint.copy(),
                    inherited.deliveredPosition,
                    inherited.settlement.boundXid,
                    inherited.settlement.reach,
                ) == 1,
            ) { "projection split child checkpoint already exists" }
        }
        check(
            dsl.execute(
                RETIRE_PARENT_MEMBER,
                declaration.projection.text(),
                declaration.generation.value,
                parent.depth,
                parent.prefix,
            ) == 1,
        ) { "projection split parent member is no longer live" }
        children.toList().forEach { child ->
            check(
                dsl.execute(
                    INSERT_MEMBER,
                    declaration.projection.text(),
                    declaration.generation.value,
                    child.depth,
                    child.prefix,
                ) == 1,
            ) { "projection split child member already exists" }
        }
        val retirement =
            checkNotNull(
                dsl.fetchOne(
                    INSERT_RETIREMENT,
                    declaration.projection.text(),
                    declaration.generation.value,
                    parent.depth,
                    parent.prefix,
                    children.first.depth,
                    children.first.prefix,
                    children.second.depth,
                    children.second.prefix,
                    inherited.deliveredPosition,
                    inherited.settlement.boundXid,
                    inherited.settlement.reach,
                    parentFence,
                ),
            ) { "projection topology retirement insert did not return its record" }.let { retirement(it, declaration.contract.origin) }
        check(
            dsl.execute(
                UPDATE_TOPOLOGY,
                nextCover.fingerprint.copy(),
                declaration.projection.text(),
                declaration.generation.value,
                declaration.contract.topology.copy(),
            ) == 1,
        ) { "projection topology changed while its split was in progress" }
        dsl.fetchOne(ENABLE_TOPOLOGY_UPDATE)
        check(
            dsl.execute(
                UPDATE_GENERATION_TOPOLOGY,
                nextCover.fingerprint.copy(),
                declaration.projection.text(),
                declaration.generation.value,
                declaration.contract.topology.copy(),
            ) == 1,
        ) { "projection generation changed while its split was in progress" }
        check(
            dsl.execute(
                DELETE_PARENT_CHECKPOINT,
                declaration.projection.text(),
                declaration.generation.value,
                parent.depth,
                parent.prefix,
                parentFence,
            ) == 1,
        ) { "projection split parent checkpoint was no longer the locked retired lane" }
        val nextDeclaration =
            ProjectionTopologyDeclaration(
                declaration.projection,
                declaration.generation,
                declaration.contract.copy(topology = nextCover.fingerprint),
                nextCover,
            )
        val topology =
            checkNotNull(topology(nextDeclaration.projection, nextDeclaration.generation)) {
                "projection topology disappeared after split"
            }
        check(matches(topology, nextDeclaration)) { "projection topology does not match its committed split cover" }
        return ProjectionTopologySplit.Split(topology, retirement)
    }

    private fun requireTransaction() {
        checkNotNull(placement) { "projection topology mutation requires a transaction placement" }.inspect().requireAuthority()
    }

    private fun generationMatches(
        declaration: ProjectionTopologyDeclaration,
        lock: Boolean,
    ): Boolean {
        val query = if (lock) LOCK_GENERATION else FETCH_GENERATION
        val row = dsl.fetchOne(query, declaration.projection.text(), declaration.generation.value) ?: return false
        return EventLogId.of(checkNotNull(row.get("log_id", ByteArray::class.java))) == declaration.contract.origin.logId &&
            row.get("contract_revision", Int::class.java) == declaration.contract.revision.value &&
            com.gd.rain.event.projection.ProjectionTopologyFingerprint
                .of(checkNotNull(row.get("topology_fingerprint", ByteArray::class.java))) == declaration.contract.topology
    }

    private fun lockTopology(declaration: ProjectionTopologyDeclaration): ProjectionTopology? {
        val header = dsl.fetchOne(LOCK_TOPOLOGY, declaration.projection.text(), declaration.generation.value) ?: return null
        return topology(header, dsl.fetch(FETCH_MEMBERS, declaration.projection.text(), declaration.generation.value).map(::member))
    }

    private fun matches(
        topology: ProjectionTopology,
        declaration: ProjectionTopologyDeclaration,
    ): Boolean =
        topology.projection == declaration.projection &&
            topology.generation == declaration.generation &&
            topology.contract == declaration.contract &&
            topology.hasher == declaration.cover.hasher.id &&
            topology.members.filter { it.state == ProjectionTopologyMemberState.LIVE }.map(ProjectionTopologyMember::partition) ==
            declaration.cover.members

    private fun checkpointMatches(
        row: Record,
        contract: ProjectionCheckpointContract,
    ): Boolean =
        EventLogId.of(checkNotNull(row.get("log_id", ByteArray::class.java))) == contract.origin.logId &&
            row.get("contract_revision", Int::class.java) == contract.revision.value &&
            com.gd.rain.event.projection.ProjectionTopologyFingerprint
                .of(checkNotNull(row.get("topology_fingerprint", ByteArray::class.java))) == contract.topology

    private fun blockers(
        projection: com.gd.rain.event.projection.ProjectionName,
        generation: ProjectionGeneration,
        parent: ProjectionPartition,
    ): ProjectionTopologyBlockers? {
        val row =
            checkNotNull(
                dsl.fetchOne(
                    BLOCKERS,
                    projection.text(),
                    generation.value,
                    parent.depth,
                    parent.prefix,
                    projection.text(),
                    generation.value,
                    parent.depth,
                    parent.prefix,
                    projection.text(),
                    generation.value,
                    parent.depth,
                    parent.prefix,
                ),
            ) { "projection topology blocker query returned no row" }
        val holds = checkNotNull((row.get("holds") as? Number)?.toInt())
        val holes = checkNotNull((row.get("holes") as? Number)?.toInt())
        val halts = checkNotNull((row.get("halts") as? Number)?.toInt())
        return if (holds + holes + halts == 0) null else ProjectionTopologyBlockers(holds, holes, halts)
    }

    private fun topology(
        header: Record,
        members: List<ProjectionTopologyMember>,
    ): ProjectionTopology {
        val origin = EventLogOrigin(EventLogId.of(checkNotNull(header.get("log_id", ByteArray::class.java))))
        val contract =
            ProjectionCheckpointContract(
                origin,
                com.gd.rain.event.projection.ProjectionContractRevision(
                    checkNotNull(header.get("contract_revision", Int::class.java)),
                ),
                com.gd.rain.event.projection.ProjectionTopologyFingerprint
                    .of(checkNotNull(header.get("topology_fingerprint", ByteArray::class.java))),
            )
        return ProjectionTopology(
            com.gd.rain.event.projection.ProjectionName.of(
                checkNotNull(header.get("projection_name", String::class.java)),
            ),
            ProjectionGeneration(checkNotNull(header.get("generation", Long::class.java))),
            contract,
            SequenceKeyHasherId.of(checkNotNull(header.get("hasher_id", String::class.java))),
            members,
            checkNotNull(header.get("updated_at", Instant::class.java)),
        )
    }

    private fun member(row: Record): ProjectionTopologyMember =
        ProjectionTopologyMember(
            ProjectionPartition(
                checkNotNull(row.get("partition_depth", Int::class.java)),
                checkNotNull(row.get("partition_prefix", Long::class.java)),
            ),
            when (checkNotNull(row.get("state", String::class.java))) {
                "live" -> ProjectionTopologyMemberState.LIVE
                "retired" -> ProjectionTopologyMemberState.RETIRED
                else -> error("projection topology member state is invalid")
            },
        )

    private fun cursor(
        row: Record,
        origin: EventLogOrigin,
    ): EventLogCursor =
        EventLogCursor(
            origin,
            checkNotNull(row.get("cursor_position", Long::class.java)),
            EventLogSettlement(
                row.get("cursor_bound_xid", String::class.java),
                checkNotNull(row.get("cursor_reach", Long::class.java)),
            ),
        )

    private fun retirement(
        row: Record,
        origin: EventLogOrigin,
    ): ProjectionTopologyRetirement =
        ProjectionTopologyRetirement(
            com.gd.rain.event.projection.ProjectionName.of(
                checkNotNull(row.get("projection_name", String::class.java)),
            ),
            ProjectionGeneration(checkNotNull(row.get("generation", Long::class.java))),
            ProjectionPartition(
                checkNotNull(row.get("parent_depth", Int::class.java)),
                checkNotNull(row.get("parent_prefix", Long::class.java)),
            ),
            ProjectionPartition(
                checkNotNull(row.get("first_child_depth", Int::class.java)),
                checkNotNull(row.get("first_child_prefix", Long::class.java)),
            ),
            ProjectionPartition(
                checkNotNull(row.get("second_child_depth", Int::class.java)),
                checkNotNull(row.get("second_child_prefix", Long::class.java)),
            ),
            EventLogCursor(
                origin,
                checkNotNull(row.get("cursor_position", Long::class.java)),
                EventLogSettlement(
                    row.get("cursor_bound_xid", String::class.java),
                    checkNotNull(row.get("cursor_reach", Long::class.java)),
                ),
            ),
            checkNotNull(row.get("parent_fence", Long::class.java)),
            checkNotNull(row.get("retired_at", Instant::class.java)),
        )

    private fun lockCheckpoint(lane: ProjectionLane): Record? =
        dsl.fetchOne(
            LOCK_PARENT_CHECKPOINT,
            lane.projection.text(),
            lane.generation.value,
            lane.partition.depth,
            lane.partition.prefix,
        )

    private companion object {
        const val FETCH_GENERATION: String = """
            SELECT log_id, contract_revision, topology_fingerprint
            FROM rain_event.projection_generation
            WHERE projection_name = ? AND generation = ?
            """
        const val LOCK_GENERATION: String = "$FETCH_GENERATION FOR UPDATE"
        const val INSERT_TOPOLOGY: String = """
            INSERT INTO rain_event.projection_topology(
              projection_name, generation, log_id, contract_revision, hasher_id, topology_fingerprint
            ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (projection_name, generation) DO NOTHING
            """
        const val INSERT_MEMBER: String = """
            INSERT INTO rain_event.projection_topology_member(
              projection_name, generation, partition_depth, partition_prefix, state
            ) VALUES (?, ?, ?, ?, 'live')
            """
        const val FETCH_TOPOLOGY: String = """
            SELECT projection_name, generation, log_id, contract_revision, hasher_id, topology_fingerprint, updated_at
            FROM rain_event.projection_topology
            WHERE projection_name = ? AND generation = ?
            """
        const val LOCK_TOPOLOGY: String = "$FETCH_TOPOLOGY FOR UPDATE"
        const val FETCH_MEMBERS: String = """
            SELECT partition_depth, partition_prefix, state
            FROM rain_event.projection_topology_member
            WHERE projection_name = ? AND generation = ?
            ORDER BY partition_depth, partition_prefix
            """
        const val LOCK_PARENT_CHECKPOINT: String = """
            SELECT log_id, contract_revision, topology_fingerprint, cursor_position, cursor_bound_xid, cursor_reach, fence,
                   lease_until, lease_until > statement_timestamp() AS lease_active
            FROM rain_event.projection_checkpoint
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?
            FOR UPDATE
            """
        const val BLOCKERS: String = """
            SELECT
              (SELECT count(*) FROM rain_event.projection_hold
               WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?) AS holds,
              (SELECT count(*) FROM rain_event.projection_hole
               WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?) AS holes,
              (SELECT count(*) FROM rain_event.projection_halt
               WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ?) AS halts
            """
        const val INSERT_CHILD_CHECKPOINT: String = """
            INSERT INTO rain_event.projection_checkpoint(
              projection_name, generation, partition_depth, partition_prefix, log_id, contract_revision,
              topology_fingerprint, cursor_position, cursor_bound_xid, cursor_reach, fence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (projection_name, generation, partition_depth, partition_prefix) DO NOTHING
            """
        const val RETIRE_PARENT_MEMBER: String = """
            UPDATE rain_event.projection_topology_member
            SET state = 'retired', retired_at = statement_timestamp()
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND state = 'live'
            """
        const val INSERT_RETIREMENT: String = """
            INSERT INTO rain_event.projection_topology_retirement(
              projection_name, generation, parent_depth, parent_prefix, first_child_depth, first_child_prefix,
              second_child_depth, second_child_prefix, cursor_position, cursor_bound_xid, cursor_reach, parent_fence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING projection_name, generation, parent_depth, parent_prefix, first_child_depth, first_child_prefix,
                      second_child_depth, second_child_prefix, cursor_position, cursor_bound_xid, cursor_reach,
                      parent_fence, retired_at
            """
        const val UPDATE_TOPOLOGY: String = """
            UPDATE rain_event.projection_topology
            SET topology_fingerprint = ?, updated_at = statement_timestamp()
            WHERE projection_name = ? AND generation = ? AND topology_fingerprint = ?
            """
        const val ENABLE_TOPOLOGY_UPDATE: String =
            "SELECT set_config('rain_event.projection_topology_split', 'on', true)"
        const val UPDATE_GENERATION_TOPOLOGY: String = """
            UPDATE rain_event.projection_generation
            SET topology_fingerprint = ?
            WHERE projection_name = ? AND generation = ? AND topology_fingerprint = ?
            """
        const val DELETE_PARENT_CHECKPOINT: String = """
            DELETE FROM rain_event.projection_checkpoint
            WHERE projection_name = ? AND generation = ? AND partition_depth = ? AND partition_prefix = ? AND fence = ?
            """
    }
}
