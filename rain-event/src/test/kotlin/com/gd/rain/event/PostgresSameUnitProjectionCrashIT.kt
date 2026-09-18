package com.gd.rain.event

import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.event.projection.ProjectionAdvancement
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionCover
import com.gd.rain.event.projection.ProjectionDestinationId
import com.gd.rain.event.projection.ProjectionEffectPolicy
import com.gd.rain.event.projection.ProjectionFact
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionPermanentFailurePolicy
import com.gd.rain.event.projection.ProjectionRoute
import com.gd.rain.event.projection.ProjectionRunBudget
import com.gd.rain.event.projection.ProjectionSpec
import com.gd.rain.event.projection.SameUnitProjectionDefinition
import com.gd.rain.event.projection.SameUnitProjectionRunner
import com.gd.rain.event.projection.SequenceKeyHasher
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.event.projection.postgres.PostgresProjectionCheckpointStore
import com.gd.rain.event.projection.postgres.PostgresSameUnitProjectionDestination
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

@Tag("integration")
class PostgresSameUnitProjectionCrashIT {
    @ParameterizedTest(name = "destination failure after position {0} rolls back the whole same-unit pass")
    @ValueSource(longs = [1, 2, 3])
    fun `same unit delivery rolls back every handler write and its checkpoint before an external retry`(failedPosition: Long) {
        val database = RainPostgres.freshDatabase("same_unit_crash_$failedPosition")
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
        dsl.execute("CREATE TABLE projection_same_unit_crash_test (position bigint PRIMARY KEY)")
        val events = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 6 }), "counter", "crash")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                (1L..3L).forEach { version ->
                    check(
                        events.append(
                            transaction,
                            version - 1,
                            encoded(stream, version),
                            EventMetadata(OperationKey.of("crash-$version")),
                        ) is AppendResult.Committed,
                    ) {
                        "crash-window source event was not committed"
                    }
                }
            }
        }
        val lane = ProjectionLane(SPEC.name, ProjectionGeneration(1), ProjectionPartition.WHOLE)
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin, transactions)
        val destination = PostgresSameUnitProjectionDestination(dsl, transactions)
        var handlerInvocations = 0
        val failing =
            SameUnitProjectionRunner(
                SameUnitProjectionDefinition(SPEC) { batch, _ ->
                    handlerInvocations++
                    batch.events.forEach { event ->
                        dsl.execute("INSERT INTO projection_same_unit_crash_test(position) VALUES (?)", event.position)
                        if (event.position == failedPosition) error("declared destination failure after $failedPosition")
                    }
                },
                events,
                checkpoints,
                destination,
                Clock.systemUTC(),
                Duration.ofMinutes(1),
            )

        assertThatThrownBy {
            TransactionTemplate(transactions).execute { failing.run(lane) }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("declared destination failure after $failedPosition")
        assertThat(handlerInvocations).isEqualTo(1)
        assertThat(dsl.fetchCount(DSL.table("projection_same_unit_crash_test"))).isZero()
        assertThat(checkpoints.checkpoint(lane)).isNull()

        val succeeding =
            SameUnitProjectionRunner(
                SameUnitProjectionDefinition(SPEC) { batch, _ ->
                    handlerInvocations++
                    batch.events.forEach { event ->
                        dsl.execute("INSERT INTO projection_same_unit_crash_test(position) VALUES (?)", event.position)
                    }
                },
                events,
                checkpoints,
                destination,
                Clock.systemUTC(),
                Duration.ofMinutes(1),
            )
        val pass = TransactionTemplate(transactions).execute { succeeding.run(lane) }

        assertThat(pass).isInstanceOf(com.gd.rain.event.projection.SameUnitPass.Advanced::class.java)
        assertThat(handlerInvocations).isEqualTo(2)
        assertThat(dsl.fetch("SELECT position FROM projection_same_unit_crash_test ORDER BY position").getValues(0, Long::class.java))
            .containsExactly(1L, 2L, 3L)
        assertThat(checkpoints.checkpoint(lane)?.cursor?.deliveredPosition).isEqualTo(3)
    }

    private fun encoded(
        stream: StreamRef,
        value: Long,
    ): EncodedChanges =
        EncodedChanges(
            stream,
            listOf(EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8(value.toString()))),
        )

    private companion object {
        val SPEC: ProjectionSpec =
            ProjectionSpec(
                name = ProjectionName.of("counter-same-unit-crash"),
                revision = ProjectionContractRevision(1),
                ownedFamilies = setOf("counter"),
                routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
                cover = ProjectionCover.whole(ByStream),
                advancement = ProjectionAdvancement.SAME_UNIT,
                destination = ProjectionDestinationId.of("counter-view-db"),
                permanentFailurePolicy = ProjectionPermanentFailurePolicy.HALT,
                effectPolicy = ProjectionEffectPolicy.DISABLED,
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
