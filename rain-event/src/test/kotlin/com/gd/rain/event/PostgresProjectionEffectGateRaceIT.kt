package com.gd.rain.event

import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.event.projection.ProjectionAdvancement
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionCover
import com.gd.rain.event.projection.ProjectionCutover
import com.gd.rain.event.projection.ProjectionDestinationId
import com.gd.rain.event.projection.ProjectionEffectAdmission
import com.gd.rain.event.projection.ProjectionEffectPolicy
import com.gd.rain.event.projection.ProjectionFact
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionGenerationPlan
import com.gd.rain.event.projection.ProjectionGenerationReadiness
import com.gd.rain.event.projection.ProjectionGenerationRegistration
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionPermanentFailurePolicy
import com.gd.rain.event.projection.ProjectionRoute
import com.gd.rain.event.projection.ProjectionRunBudget
import com.gd.rain.event.projection.ProjectionSpec
import com.gd.rain.event.projection.SequenceKeyHasher
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.event.projection.postgres.PostgresProjectionCheckpointStore
import com.gd.rain.event.projection.postgres.PostgresProjectionGenerationStore
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Tag("integration")
class PostgresProjectionEffectGateRaceIT {
    @Test
    fun `cutover waits for an admitted active-generation effect to commit and then fences the retired generation`() {
        val database = RainPostgres.freshDatabase("projection_effect_gate_race")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        dsl.execute("CREATE TABLE projection_effect_gate_race_test (position bigint PRIMARY KEY)")
        val events = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 12 }), "counter", "effect-race")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                check(
                    events.append(
                        transaction,
                        0,
                        encoded(stream),
                        EventMetadata(OperationKey.of("effect-race-source")),
                    ) is AppendResult.Committed,
                ) {
                    "effect gate source event was not committed"
                }
            }
        }
        val source = events.initialCursor()
        val barrier = events.readCommitted(source, 10).next
        val contract = ProjectionCheckpointContract(events.origin, SPEC.revision, SPEC.cover.fingerprint)
        val first =
            ProjectionGenerationPlan.create(
                SPEC.name,
                ProjectionGeneration(1),
                contract,
                source,
                barrier,
                ProjectionEffectPolicy.STAGED_DURABLE,
            )
        val second =
            ProjectionGenerationPlan.create(
                SPEC.name,
                ProjectionGeneration(2),
                contract,
                source,
                barrier,
                ProjectionEffectPolicy.STAGED_DURABLE,
            )
        val generations = PostgresProjectionGenerationStore(dsl)
        val gate = PostgresProjectionGenerationStore(dsl, transactions)
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin)
        ready(generations, checkpoints, first, source, barrier)
        assertThat(generations.cutover(SPEC.name, null, first.generation)).isInstanceOf(ProjectionCutover.Activated::class.java)
        ready(generations, checkpoints, second, source, barrier)

        val admitted = CountDownLatch(1)
        val releaseEffect = CountDownLatch(1)
        val cutoverStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val effect =
            executor.submit<ProjectionEffectAdmission> {
                checkNotNull(
                    TransactionTemplate(transactions).execute {
                        val admission = gate.admitEffect(SPEC.name, first.generation, barrier.deliveredPosition + 1)
                        dsl.execute(
                            "INSERT INTO projection_effect_gate_race_test(position) VALUES (?)",
                            barrier.deliveredPosition + 1,
                        )
                        admitted.countDown()
                        check(releaseEffect.await(5, TimeUnit.SECONDS)) { "test did not release the admitted effect" }
                        admission
                    },
                ) { "effect transaction returned null" }
            }
        try {
            check(admitted.await(5, TimeUnit.SECONDS)) { "effect gate did not admit its staged write" }
            val cutover =
                executor.submit<ProjectionCutover> {
                    cutoverStarted.countDown()
                    generations.cutover(SPEC.name, first.generation, second.generation)
                }
            check(cutoverStarted.await(5, TimeUnit.SECONDS)) { "cutover task did not start" }
            assertThatThrownBy { cutover.get(250, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseEffect.countDown()
            assertThat(effect.get(5, TimeUnit.SECONDS)).isEqualTo(ProjectionEffectAdmission.ALLOWED)
            assertThat(cutover.get(5, TimeUnit.SECONDS)).isInstanceOf(ProjectionCutover.Activated::class.java)
        } finally {
            releaseEffect.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "effect-gate test executor did not stop" }
        }

        assertThat(dsl.fetch("SELECT position FROM projection_effect_gate_race_test").getValues(0, Long::class.java))
            .containsExactly(barrier.deliveredPosition + 1)
        assertThat(generations.active(SPEC.name)?.plan?.generation).isEqualTo(second.generation)
        val retiredAdmission =
            checkNotNull(
                TransactionTemplate(transactions).execute {
                    gate.admitEffect(SPEC.name, first.generation, barrier.deliveredPosition + 2)
                },
            ) { "retired-generation effect transaction returned null" }
        assertThat(retiredAdmission).isEqualTo(ProjectionEffectAdmission.INACTIVE_GENERATION)
    }

    private fun ready(
        generations: PostgresProjectionGenerationStore,
        checkpoints: PostgresProjectionCheckpointStore,
        plan: ProjectionGenerationPlan,
        source: EventLogCursor,
        barrier: EventLogCursor,
    ) {
        assertThat(generations.register(plan)).isInstanceOf(ProjectionGenerationRegistration.Created::class.java)
        val lane = ProjectionLane(plan.projection, plan.generation, ProjectionPartition.WHOLE)
        val claim =
            checkpoints.claim(lane, plan.contract, source, Instant.parse("2026-09-17T00:00:00Z"), Duration.ofMinutes(1))
                as ProjectionClaim.Acquired
        assertThat(checkpoints.advance(claim.lease, barrier, Instant.parse("2026-09-17T00:00:00Z")))
            .isInstanceOf(com.gd.rain.event.projection.ProjectionAdvance.Advanced::class.java)
        assertThat(generations.markReady(plan, SPEC.cover)).isInstanceOf(ProjectionGenerationReadiness.Ready::class.java)
    }

    private fun encoded(stream: StreamRef): EncodedChanges =
        EncodedChanges(stream, listOf(EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("one"))))

    private companion object {
        val SPEC: ProjectionSpec =
            ProjectionSpec(
                name = ProjectionName.of("counter-effect-race"),
                revision = ProjectionContractRevision(1),
                ownedFamilies = setOf("counter"),
                routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
                cover = ProjectionCover.whole(ByStream),
                advancement = ProjectionAdvancement.SAME_UNIT,
                destination = ProjectionDestinationId.of("counter-view-db"),
                permanentFailurePolicy = ProjectionPermanentFailurePolicy.HALT,
                effectPolicy = ProjectionEffectPolicy.STAGED_DURABLE,
                budget = ProjectionRunBudget(10),
            )

        object ByStream : SequenceKeyHasher {
            override val id: SequenceKeyHasherId = SequenceKeyHasherId.of("stream.v1")

            override fun hash(event: StoredEvent): Long =
                event.stream.key
                    .hashCode()
                    .toLong()

            override fun sequence(event: StoredEvent): com.gd.rain.event.projection.ProjectionSequenceId =
                com.gd.rain.event.projection.ProjectionSequenceId
                    .forStream(id, event.stream)
        }
    }
}
