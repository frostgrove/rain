package com.gd.rain.event.projection.postgres

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventLogSettlement
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionCover
import com.gd.rain.event.projection.ProjectionCutover
import com.gd.rain.event.projection.ProjectionEffectAdmission
import com.gd.rain.event.projection.ProjectionEffectBarrier
import com.gd.rain.event.projection.ProjectionEffectGate
import com.gd.rain.event.projection.ProjectionEffectPolicy
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionGenerationPlan
import com.gd.rain.event.projection.ProjectionGenerationReadiness
import com.gd.rain.event.projection.ProjectionGenerationRecord
import com.gd.rain.event.projection.ProjectionGenerationRegistration
import com.gd.rain.event.projection.ProjectionGenerationState
import com.gd.rain.event.projection.ProjectionGenerationStore
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionTopologyFingerprint
import com.gd.rain.event.projection.ProjectionTransactionPlacement
import com.gd.rain.persistence.tx.TransactionPlacement
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant

/** PostgreSQL catalog for immutable rebuild generations, checked-cover readiness, and CAS cutover. */
public class PostgresProjectionGenerationStore(
    private val dsl: DSLContext,
    private val placement: ProjectionTransactionPlacement? = null,
) : ProjectionGenerationStore,
    ProjectionEffectGate {
    public constructor(
        dsl: DSLContext,
        transactionManager: PlatformTransactionManager,
    ) : this(dsl, ProjectionTransactionPlacement { TransactionPlacement.inspect(dsl, transactionManager) })

    override fun admitEffect(
        projection: ProjectionName,
        generation: ProjectionGeneration,
        position: Long,
    ): ProjectionEffectAdmission {
        require(position > 0) { "projection effect position is positive" }
        checkNotNull(placement) { "projection effect gate requires a transaction placement" }.inspect().requireAuthority()
        val row = dsl.fetchOne(EFFECT_GATE, generation.value, projection.text()) ?: return ProjectionEffectAdmission.INACTIVE_GENERATION
        if ((row.get("active_generation") as? Number)?.toLong() != generation.value) return ProjectionEffectAdmission.INACTIVE_GENERATION
        if (row.get("state", String::class.java) != "active") return ProjectionEffectAdmission.INACTIVE_GENERATION
        if (row.get("effect_policy", String::class.java) != "staged_durable") return ProjectionEffectAdmission.SUPPRESSED_BY_BARRIER
        val barrier = checkNotNull((row.get("effect_after_position") as? Number)?.toLong()) { "staged-effect generation has no barrier" }
        return if (position > barrier) ProjectionEffectAdmission.ALLOWED else ProjectionEffectAdmission.SUPPRESSED_BY_BARRIER
    }

    override fun register(plan: ProjectionGenerationPlan): ProjectionGenerationRegistration {
        dsl.execute(
            INSERT_CATALOG,
            plan.projection.text(),
            plan.contract.origin.logId
                .copy(),
        )
        val catalogLog =
            EventLogId.of(
                checkNotNull(dsl.fetchOne(CATALOG_LOG, plan.projection.text())?.get("log_id", ByteArray::class.java)) {
                    "projection catalog disappeared during registration"
                },
            )
        if (catalogLog != plan.contract.origin.logId) return ProjectionGenerationRegistration.Collision
        val inserted =
            dsl.execute(
                INSERT_GENERATION,
                plan.projection.text(),
                plan.generation.value,
                plan.contract.origin.logId
                    .copy(),
                plan.contract.revision.value,
                plan.contract.topology.copy(),
                plan.source.deliveredPosition,
                plan.barrier.deliveredPosition,
                policy(plan.effectPolicy),
                plan.effectBarrier?.afterPosition,
            )
        val existing = checkNotNull(generation(plan.projection, plan.generation)) { "projection generation disappeared after registration" }
        return if (inserted == 1) {
            ProjectionGenerationRegistration.Created(existing)
        } else if (same(existing.plan, plan)) {
            ProjectionGenerationRegistration.Repeated(existing)
        } else {
            ProjectionGenerationRegistration.Collision
        }
    }

    override fun generation(
        projection: ProjectionName,
        generation: ProjectionGeneration,
    ): ProjectionGenerationRecord? = dsl.fetchOne(FETCH_GENERATION, projection.text(), generation.value)?.let(::record)

    override fun active(projection: ProjectionName): ProjectionGenerationRecord? =
        dsl.fetchOne(FETCH_ACTIVE, projection.text())?.let(::record)

    override fun runnable(
        projection: ProjectionName,
        limit: Int,
    ): List<ProjectionGenerationRecord> {
        require(limit in 1..MAX_RUNNABLE_GENERATIONS) { "projection runnable generation limit is outside 1..$MAX_RUNNABLE_GENERATIONS" }
        return dsl.fetch(FETCH_RUNNABLE, projection.text(), limit).map(::record)
    }

    override fun markReady(
        plan: ProjectionGenerationPlan,
        cover: ProjectionCover,
    ): ProjectionGenerationReadiness {
        if (cover.fingerprint != plan.contract.topology) return ProjectionGenerationReadiness.ContractDrift
        val current = generation(plan.projection, plan.generation) ?: return ProjectionGenerationReadiness.ContractDrift
        if (!same(current.plan, plan)) return ProjectionGenerationReadiness.ContractDrift
        blockers(plan.projection, plan.generation)?.let { return ProjectionGenerationReadiness.Blocked(it.holds, it.holes, it.halts) }
        if (current.state == ProjectionGenerationState.READY) return ProjectionGenerationReadiness.Ready(current)
        if (current.state != ProjectionGenerationState.BUILDING) return ProjectionGenerationReadiness.ContractDrift
        val rows = dsl.fetch(CHECKPOINTS, plan.projection.text(), plan.generation.value)
        var missing = 0
        var lagging = 0
        cover.members.forEach { partition ->
            val row =
                rows.singleOrNull {
                    it.get("partition_depth", Int::class.java) == partition.depth &&
                        it.get("partition_prefix", Long::class.java) == partition.prefix
                }
            if (row == null) {
                missing++
            } else if (
                !EventLogId.of(checkNotNull(row.get("log_id", ByteArray::class.java))).equals(plan.contract.origin.logId) ||
                row.get("contract_revision", Int::class.java) != plan.contract.revision.value ||
                !ProjectionTopologyFingerprint
                    .of(checkNotNull(row.get("topology_fingerprint", ByteArray::class.java)))
                    .equals(plan.contract.topology) ||
                checkNotNull(row.get("cursor_position", Long::class.java)) < plan.barrier.deliveredPosition
            ) {
                lagging++
            }
        }
        if (missing != 0 || lagging != 0) return ProjectionGenerationReadiness.Behind(missing, lagging)
        dsl.execute(MARK_READY, plan.projection.text(), plan.generation.value)
        return ProjectionGenerationReadiness.Ready(checkNotNull(generation(plan.projection, plan.generation)))
    }

    override fun cutover(
        projection: ProjectionName,
        expectedActive: ProjectionGeneration?,
        candidate: ProjectionGeneration,
    ): ProjectionCutover =
        dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            val catalog = tx.fetchOne(LOCK_CATALOG, projection.text()) ?: return@transactionResult ProjectionCutover.Conflict
            val active = (catalog.get("active_generation") as? Number)?.toLong()?.let(::ProjectionGeneration)
            if (active != expectedActive) return@transactionResult ProjectionCutover.Conflict
            val row =
                tx.fetchOne(LOCK_GENERATION, projection.text(), candidate.value)
                    ?: return@transactionResult ProjectionCutover.Conflict
            val state = ProjectionGenerationState.valueOf(checkNotNull(row.get("state", String::class.java)).uppercase())
            if (state !in setOf(ProjectionGenerationState.READY, ProjectionGenerationState.RETIRED)) {
                return@transactionResult ProjectionCutover.NotReady
            }
            blockers(tx, projection, candidate)?.let { return@transactionResult ProjectionCutover.Blocked(it.holds, it.holes, it.halts) }
            if (active != null) tx.execute(RETIRE_ACTIVE, projection.text(), active.value)
            tx.execute(ACTIVATE, projection.text(), candidate.value)
            tx.execute(SET_ACTIVE, candidate.value, projection.text(), active?.value)
            ProjectionCutover.Activated(
                checkNotNull(tx.fetchOne(FETCH_GENERATION, projection.text(), candidate.value)).let(::record),
            )
        }

    private fun record(row: Record): ProjectionGenerationRecord {
        val origin = EventLogOrigin(EventLogId.of(checkNotNull(row.get("log_id", ByteArray::class.java))))
        val contract =
            ProjectionCheckpointContract(
                origin,
                com.gd.rain.event.projection
                    .ProjectionContractRevision(checkNotNull(row.get("contract_revision", Int::class.java))),
                ProjectionTopologyFingerprint.of(checkNotNull(row.get("topology_fingerprint", ByteArray::class.java))),
            )
        val source =
            EventLogCursor(origin, checkNotNull(row.get("source_position", Long::class.java)), EventLogSettlement())
        val barrier =
            EventLogCursor(origin, checkNotNull(row.get("barrier_position", Long::class.java)), EventLogSettlement())
        val effectPolicy =
            if (row.get("effect_policy", String::class.java) == "staged_durable") {
                ProjectionEffectPolicy.STAGED_DURABLE
            } else {
                ProjectionEffectPolicy.DISABLED
            }
        val effectBarrier =
            if (effectPolicy == ProjectionEffectPolicy.STAGED_DURABLE) {
                ProjectionEffectBarrier(
                    origin,
                    checkNotNull((row.get("effect_after_position") as? Number)?.toLong()) {
                        "staged-effect projection generation has no barrier"
                    },
                )
            } else {
                null
            }
        val plan =
            ProjectionGenerationPlan(
                ProjectionName.of(checkNotNull(row.get("projection_name", String::class.java))),
                ProjectionGeneration(checkNotNull(row.get("generation", Long::class.java))),
                contract,
                source,
                barrier,
                effectPolicy,
                effectBarrier,
            )
        return ProjectionGenerationRecord(
            plan,
            ProjectionGenerationState.valueOf(checkNotNull(row.get("state", String::class.java)).uppercase()),
            checkNotNull(row.get("created_at", Instant::class.java)),
            row.get("cutover_at", Instant::class.java),
            row.get("retired_at", Instant::class.java),
        )
    }

    private fun same(
        left: ProjectionGenerationPlan,
        right: ProjectionGenerationPlan,
    ): Boolean =
        left.projection == right.projection && left.generation == right.generation && left.contract == right.contract &&
            left.source.deliveredPosition == right.source.deliveredPosition &&
            left.barrier.deliveredPosition == right.barrier.deliveredPosition &&
            left.effectPolicy == right.effectPolicy && left.effectBarrier == right.effectBarrier

    private fun policy(value: ProjectionEffectPolicy): String =
        if (value == ProjectionEffectPolicy.STAGED_DURABLE) "staged_durable" else "disabled"

    private fun blockers(
        projection: ProjectionName,
        generation: ProjectionGeneration,
    ): Blockers? = blockers(dsl, projection, generation)

    private fun blockers(
        context: DSLContext,
        projection: ProjectionName,
        generation: ProjectionGeneration,
    ): Blockers? {
        val row =
            checkNotNull(
                context.fetchOne(
                    BLOCKERS,
                    projection.text(),
                    generation.value,
                    projection.text(),
                    generation.value,
                    projection.text(),
                    generation.value,
                ),
            ) { "projection blocker query returned no row" }
        val blockers =
            Blockers(
                checkNotNull((row.get("holds") as? Number)?.toInt()),
                checkNotNull((row.get("holes") as? Number)?.toInt()),
                checkNotNull((row.get("halts") as? Number)?.toInt()),
            )
        return blockers.takeIf { it.holds + it.holes + it.halts > 0 }
    }

    private data class Blockers(
        val holds: Int,
        val holes: Int,
        val halts: Int,
    )

    private companion object {
        const val INSERT_CATALOG: String = """
            INSERT INTO rain_event.projection_catalog(projection_name, log_id)
            VALUES (?, ?) ON CONFLICT (projection_name) DO NOTHING
            """
        const val CATALOG_LOG: String = "SELECT log_id FROM rain_event.projection_catalog WHERE projection_name = ?"
        const val INSERT_GENERATION: String = """
            INSERT INTO rain_event.projection_generation(
              projection_name, generation, log_id, contract_revision, topology_fingerprint, source_position,
              barrier_position, effect_policy, effect_after_position, state
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'building') ON CONFLICT (projection_name, generation) DO NOTHING
            """
        const val FETCH_GENERATION: String = """
            SELECT projection_name, generation, log_id, contract_revision, topology_fingerprint, source_position,
                   barrier_position, effect_policy, effect_after_position, state, created_at, cutover_at, retired_at
            FROM rain_event.projection_generation WHERE projection_name = ? AND generation = ?
            """
        const val FETCH_ACTIVE: String = """
            SELECT generation.projection_name, generation.generation, generation.log_id, generation.contract_revision,
                   generation.topology_fingerprint, generation.source_position, generation.barrier_position,
                   generation.effect_policy, generation.effect_after_position, generation.state, generation.created_at,
                   generation.cutover_at, generation.retired_at
            FROM rain_event.projection_catalog catalog
            JOIN rain_event.projection_generation generation
              ON generation.projection_name = catalog.projection_name AND generation.generation = catalog.active_generation
            WHERE catalog.projection_name = ?
            """
        const val FETCH_RUNNABLE: String = """
            SELECT projection_name, generation, log_id, contract_revision, topology_fingerprint, source_position,
                   barrier_position, effect_policy, effect_after_position, state, created_at, cutover_at, retired_at
            FROM rain_event.projection_generation
            WHERE projection_name = ? AND state IN ('building', 'active')
            ORDER BY generation
            LIMIT ?
            """
        const val CHECKPOINTS: String = """
            SELECT partition_depth, partition_prefix, log_id, contract_revision, topology_fingerprint, cursor_position
            FROM rain_event.projection_checkpoint WHERE projection_name = ? AND generation = ?
            """
        const val BLOCKERS: String = """
            SELECT
              (SELECT count(*) FROM rain_event.projection_hold WHERE projection_name = ? AND generation = ?) AS holds,
              (SELECT count(*) FROM rain_event.projection_hole
                 WHERE projection_name = ? AND generation = ? AND acknowledged_at IS NULL) AS holes,
              (SELECT count(*) FROM rain_event.projection_halt WHERE projection_name = ? AND generation = ?) AS halts
            """
        const val MARK_READY: String = """
            UPDATE rain_event.projection_generation SET state = 'ready'
            WHERE projection_name = ? AND generation = ? AND state = 'building'
            """
        const val LOCK_CATALOG: String = "SELECT active_generation FROM rain_event.projection_catalog WHERE projection_name = ? FOR UPDATE"
        const val LOCK_GENERATION: String = """
            SELECT state FROM rain_event.projection_generation
            WHERE projection_name = ? AND generation = ? FOR UPDATE
            """
        const val RETIRE_ACTIVE: String = """
            UPDATE rain_event.projection_generation SET state = 'retired', retired_at = statement_timestamp()
            WHERE projection_name = ? AND generation = ? AND state = 'active'
            """
        const val ACTIVATE: String = """
            UPDATE rain_event.projection_generation
            SET state = 'active', cutover_at = statement_timestamp(), retired_at = NULL
            WHERE projection_name = ? AND generation = ? AND state IN ('ready', 'retired')
            """
        const val SET_ACTIVE: String = """
            UPDATE rain_event.projection_catalog
            SET active_generation = ?, row_version = row_version + 1, updated_at = statement_timestamp()
            WHERE projection_name = ? AND active_generation IS NOT DISTINCT FROM ?
            """
        const val EFFECT_GATE: String = """
            SELECT catalog.active_generation, generation.state, generation.effect_policy, generation.effect_after_position
            FROM rain_event.projection_catalog catalog
            JOIN rain_event.projection_generation generation
              ON generation.projection_name = catalog.projection_name AND generation.generation = ?
            WHERE catalog.projection_name = ?
            FOR KEY SHARE OF catalog
            """
        const val MAX_RUNNABLE_GENERATIONS: Int = 128
    }
}
