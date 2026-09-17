package com.gd.rain.event

import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.event.projection.ProjectionAdvancement
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionCover
import com.gd.rain.event.projection.ProjectionDestinationId
import com.gd.rain.event.projection.ProjectionEffectPolicy
import com.gd.rain.event.projection.ProjectionFailureCode
import com.gd.rain.event.projection.ProjectionFact
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionGenerationPlan
import com.gd.rain.event.projection.ProjectionHoldEnqueue
import com.gd.rain.event.projection.ProjectionHoldLimits
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLetter
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionPermanentFailurePolicy
import com.gd.rain.event.projection.ProjectionRoute
import com.gd.rain.event.projection.ProjectionRunBudget
import com.gd.rain.event.projection.ProjectionSpec
import com.gd.rain.event.projection.ProjectionTopologyDeclaration
import com.gd.rain.event.projection.ProjectionTopologyMemberState
import com.gd.rain.event.projection.ProjectionTopologyRegistration
import com.gd.rain.event.projection.ProjectionTopologySplit
import com.gd.rain.event.projection.SameUnitProjectionDefinition
import com.gd.rain.event.projection.SameUnitProjectionRunner
import com.gd.rain.event.projection.SequenceKeyHasher
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.event.projection.postgres.PostgresProjectionCheckpointStore
import com.gd.rain.event.projection.postgres.PostgresProjectionGenerationStore
import com.gd.rain.event.projection.postgres.PostgresProjectionHoldStore
import com.gd.rain.event.projection.postgres.PostgresProjectionTopologyStore
import com.gd.rain.event.projection.postgres.PostgresSameUnitProjectionDestination
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Tag("integration")
class PostgresProjectionTopologyIT {
    @Test
    fun `one-way split inherits the exact parent cursor and durably refuses an old parent runner`() {
        val database = RainPostgres.freshDatabase("projection_topology_split")
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
        val events = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 12 }), "counter", "split")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                events.append(transaction, 0, encoded(stream, "one"), EventMetadata(OperationKey.of("split-one")))
                events.append(transaction, 1, encoded(stream, "two"), EventMetadata(OperationKey.of("split-two")))
            }
        }
        val whole = ProjectionCover.whole(ByKeyParity)
        val name = ProjectionName.of("counter-topology")
        val generation = ProjectionGeneration(1)
        val lane = ProjectionLane(name, generation, ProjectionPartition.WHOLE)
        val contract = ProjectionCheckpointContract(events.origin, ProjectionContractRevision(1), whole.fingerprint)
        val page = events.readCommitted(events.initialCursor(), 10)
        val plan =
            ProjectionGenerationPlan.create(
                name,
                generation,
                contract,
                events.initialCursor(),
                page.next,
                ProjectionEffectPolicy.DISABLED,
            )
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin, transactions)
        val generations = PostgresProjectionGenerationStore(dsl, transactions)
        val topologies = PostgresProjectionTopologyStore(dsl, transactions)
        val declaration = ProjectionTopologyDeclaration(name, generation, contract, whole)
        TransactionTemplate(transactions).execute {
            assertThat(generations.register(plan)).isInstanceOf(com.gd.rain.event.projection.ProjectionGenerationRegistration.Created::class.java)
            val claim =
                checkpoints.claim(lane, contract, events.initialCursor(), Instant.now(), Duration.ofMinutes(1)) as ProjectionClaim.Acquired
            assertThat(checkpoints.advance(claim.lease, page.next, Instant.now()))
                .isInstanceOf(com.gd.rain.event.projection.ProjectionAdvance.Advanced::class.java)
            assertThat(topologies.register(declaration)).isInstanceOf(ProjectionTopologyRegistration.Created::class.java)
        }

        val split = TransactionTemplate(transactions).execute { topologies.split(declaration, ProjectionPartition.WHOLE) }

        assertThat(split).isInstanceOf(ProjectionTopologySplit.Split::class.java)
        val completed = split as ProjectionTopologySplit.Split
        val children = whole.split(ProjectionPartition.WHOLE).members
        assertThat(completed.retirement.inheritedCursor).isEqualTo(page.next)
        assertThat(completed.topology.contract.topology).isEqualTo(whole.split(ProjectionPartition.WHOLE).fingerprint)
        assertThat(completed.topology.members.filter { it.state == ProjectionTopologyMemberState.LIVE }.map { it.partition })
            .containsExactlyElementsOf(children)
        assertThat(checkpoints.checkpoint(lane)).isNull()
        children.forEach { child ->
            assertThat(checkpoints.checkpoint(ProjectionLane(name, generation, child))?.cursor).isEqualTo(page.next)
        }
        assertThat(generations.generation(name, generation)?.plan?.contract?.topology)
            .isEqualTo(whole.split(ProjectionPartition.WHOLE).fingerprint)
        assertThat(checkpoints.claim(lane, contract, events.initialCursor(), Instant.now(), Duration.ofMinutes(1)))
            .isEqualTo(ProjectionClaim.Retired)

        var invocations = 0
        val oldRunner =
            SameUnitProjectionRunner(
                SameUnitProjectionDefinition(
                    ProjectionSpec(
                        name,
                        ProjectionContractRevision(1),
                        setOf("counter"),
                        mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
                        whole,
                        ProjectionAdvancement.SAME_UNIT,
                        ProjectionDestinationId.of("counter-topology-db"),
                        ProjectionPermanentFailurePolicy.HALT,
                        ProjectionEffectPolicy.DISABLED,
                        ProjectionRunBudget(10),
                    ),
                ) { _, _ -> invocations++ },
                events,
                checkpoints,
                PostgresSameUnitProjectionDestination(dsl, transactions),
                Clock.systemUTC(),
                Duration.ofMinutes(1),
            )

        assertThat(TransactionTemplate(transactions).execute { oldRunner.run(lane) })
            .isEqualTo(com.gd.rain.event.projection.SameUnitPass.Retired)
        assertThat(invocations).isZero()
    }

    @Test
    fun `split defers a live checkpoint lease and refuses to relocate a parked parent sequence`() {
        val database = RainPostgres.freshDatabase("projection_topology_guards")
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
        val events = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 13 }), "counter", "guard")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                events.append(transaction, 0, encoded(stream, "one"), EventMetadata(OperationKey.of("guard-one")))
            }
        }
        val whole = ProjectionCover.whole(ByKeyParity)
        val name = ProjectionName.of("counter-topology-guard")
        val generation = ProjectionGeneration(1)
        val lane = ProjectionLane(name, generation, ProjectionPartition.WHOLE)
        val contract = ProjectionCheckpointContract(events.origin, ProjectionContractRevision(1), whole.fingerprint)
        val page = events.readCommitted(events.initialCursor(), 10)
        val plan =
            ProjectionGenerationPlan.create(
                name,
                generation,
                contract,
                events.initialCursor(),
                page.next,
                ProjectionEffectPolicy.DISABLED,
            )
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin, transactions)
        val generations = PostgresProjectionGenerationStore(dsl, transactions)
        val topologies = PostgresProjectionTopologyStore(dsl, transactions)
        val holds = PostgresProjectionHoldStore(dsl, events.origin, transactions)
        val declaration = ProjectionTopologyDeclaration(name, generation, contract, whole)
        TransactionTemplate(transactions).execute {
            generations.register(plan)
            topologies.register(declaration)
        }
        val active = checkpoints.claim(lane, contract, events.initialCursor(), Instant.now(), Duration.ofMinutes(1)) as ProjectionClaim.Acquired

        val busy = TransactionTemplate(transactions).execute { topologies.split(declaration, ProjectionPartition.WHOLE) }

        assertThat(busy).isInstanceOf(ProjectionTopologySplit.Busy::class.java)
        assertThat(checkpoints.checkpoint(lane)).isNotNull()
        checkpoints.release(active.lease)
        TransactionTemplate(transactions).execute {
            val claim =
                checkpoints.claim(lane, contract, events.initialCursor(), Instant.now(), Duration.ofMinutes(1)) as ProjectionClaim.Acquired
            assertThat(
                holds.enqueue(
                    claim.lease,
                    ProjectionLetter(ByKeyParity.sequence(page.events.single()), page.events.single()),
                    ProjectionFailureCode.of("destination.invalid"),
                    ProjectionHoldLimits(maxLetters = 10, maxBytes = 64 * 1024),
                ),
            ).isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
            checkpoints.advance(claim.lease, page.next, Instant.now())
        }

        val blocked = TransactionTemplate(transactions).execute { topologies.split(declaration, ProjectionPartition.WHOLE) }

        assertThat(blocked).isEqualTo(ProjectionTopologySplit.Blocked(com.gd.rain.event.projection.ProjectionTopologyBlockers(1, 0, 0)))
        assertThat(checkpoints.checkpoint(lane)?.cursor).isEqualTo(page.next)
        assertThat(topologies.topology(name, generation)?.members?.single()?.state).isEqualTo(ProjectionTopologyMemberState.LIVE)
    }

    private object ByKeyParity : SequenceKeyHasher {
        override val id: SequenceKeyHasherId = SequenceKeyHasherId.of("counter.key.parity.v1")

        override fun hash(event: StoredEvent): Long = event.position and 1

        override fun sequence(event: StoredEvent): com.gd.rain.event.projection.ProjectionSequenceId =
            com.gd.rain.event.projection.ProjectionSequenceId.forStream(id, event.stream)
    }

    private fun encoded(
        stream: StreamRef,
        value: String,
    ): EncodedChanges = EncodedChanges(stream, listOf(EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8(value))))
}
