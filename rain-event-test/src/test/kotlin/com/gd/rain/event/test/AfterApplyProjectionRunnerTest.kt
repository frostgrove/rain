package com.gd.rain.event.test

import com.gd.rain.event.AppendResult
import com.gd.rain.event.EncodedChanges
import com.gd.rain.event.EncodedFact
import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.FactType
import com.gd.rain.event.OperationKey
import com.gd.rain.event.StreamRef
import com.gd.rain.event.projection.AfterApplyPass
import com.gd.rain.event.projection.AfterApplyProjectionRunner
import com.gd.rain.event.projection.ProjectionAdvancement
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionCover
import com.gd.rain.event.projection.ProjectionDefinition
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
import com.gd.rain.event.projection.SequenceKeyHasher
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AfterApplyProjectionRunnerTest {
    private val clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
    private val events = InMemoryEventStore(clock = clock)
    private val checkpoints = InMemoryProjectionCheckpointStore()
    private val namespace = EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 8 })

    @Test
    fun `partitioned after-apply runners deliver every owned event once per pass and advance independent lanes`() {
        append("even", "one")
        append("odd", "two")
        val delivered = mutableListOf<Long>()
        val definition = definition(1) { batch -> delivered += batch.events.map { it.position } }
        val runner = AfterApplyProjectionRunner(definition, events, checkpoints, clock, Duration.ofMinutes(1))
        val cover = definition.spec.cover
        val even = ProjectionLane(definition.spec.name, ProjectionGeneration(1), cover.members.single { it.prefix == 0L })
        val odd = ProjectionLane(definition.spec.name, ProjectionGeneration(1), cover.members.single { it.prefix == 1L })

        val evenPass = runner.run(even) as AfterApplyPass.Advanced
        val oddPass = runner.run(odd) as AfterApplyPass.Advanced

        assertThat(evenPass.delivered).isEqualTo(2)
        assertThat(evenPass.applied).isEqualTo(1)
        assertThat(oddPass.delivered).isEqualTo(2)
        assertThat(oddPass.applied).isEqualTo(1)
        assertThat(delivered).containsExactlyInAnyOrder(1, 2)
        assertThat(checkpoints.checkpoint(even)?.cursor?.deliveredPosition).isEqualTo(2)
        assertThat(checkpoints.checkpoint(odd)?.cursor?.deliveredPosition).isEqualTo(2)
    }

    @Test
    fun `after-apply handler failure does not advance and a new pass redelivers the same event`() {
        append("even", "one")
        val attempts = mutableListOf<Long>()
        var first = true
        val definition =
            definition(1) { batch ->
                attempts += batch.events.single().position
                if (first) {
                    first = false
                    error("destination unavailable")
                }
            }
        val runner = AfterApplyProjectionRunner(definition, events, checkpoints, clock, Duration.ofMinutes(1))
        val lane =
            ProjectionLane(
                definition.spec.name,
                ProjectionGeneration(1),
                definition.spec.cover.members
                    .single { it.prefix == 0L },
            )

        assertThatThrownBy { runner.run(lane) }.isInstanceOf(IllegalStateException::class.java).hasMessage("destination unavailable")
        assertThat(checkpoints.checkpoint(lane)?.cursor?.deliveredPosition).isEqualTo(0)
        assertThat(runner.run(lane)).isInstanceOf(AfterApplyPass.Advanced::class.java)
        assertThat(attempts).containsExactly(1, 1)
    }

    @Test
    fun `checkpoint contract drift is a closed result rather than a silent cursor reuse`() {
        append("even", "one")
        val initial = definition(1) {}
        val lane =
            ProjectionLane(
                initial.spec.name,
                ProjectionGeneration(1),
                initial.spec.cover.members
                    .single { it.prefix == 0L },
            )
        AfterApplyProjectionRunner(initial, events, checkpoints, clock, Duration.ofMinutes(1)).run(lane)

        val changed = definition(2) {}

        assertThat(AfterApplyProjectionRunner(changed, events, checkpoints, clock, Duration.ofMinutes(1)).run(lane))
            .isEqualTo(AfterApplyPass.ContractDrift)
    }

    private fun definition(
        revision: Int,
        handler: (com.gd.rain.event.projection.ProjectionBatch) -> Unit,
    ): ProjectionDefinition {
        val cover = ProjectionCover.whole(ByKeyParity).split(ProjectionPartition.WHOLE)
        return ProjectionDefinition(
            ProjectionSpec(
                name = ProjectionName.of("counter-view"),
                revision = ProjectionContractRevision(revision),
                ownedFamilies = setOf("counter"),
                routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
                cover = cover,
                advancement = ProjectionAdvancement.AFTER_APPLY,
                destination = ProjectionDestinationId.of("counter-index"),
                permanentFailurePolicy = ProjectionPermanentFailurePolicy.HALT,
                effectPolicy = ProjectionEffectPolicy.DISABLED,
                budget = ProjectionRunBudget(pageSize = 10),
            ),
            handler::invoke,
        )
    }

    private fun append(
        key: String,
        operation: String,
    ) {
        events.inCallerTransaction { transaction ->
            val result =
                events.append(
                    transaction,
                    0,
                    EncodedChanges(
                        StreamRef(namespace, "counter", key),
                        listOf(EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("1"))),
                    ),
                    EventMetadata(OperationKey.of(operation)),
                )
            assertThat(result).isInstanceOf(AppendResult.Committed::class.java)
        }
    }

    private object ByKeyParity : SequenceKeyHasher {
        override val id: SequenceKeyHasherId = SequenceKeyHasherId.of("counter.key.parity.v1")

        override fun hash(event: com.gd.rain.event.StoredEvent): Long = if (event.stream.key == "odd") 1 else 0

        override fun sequence(event: com.gd.rain.event.StoredEvent): com.gd.rain.event.projection.ProjectionSequenceId =
            com.gd.rain.event.projection.ProjectionSequenceId
                .forStream(id, event.stream)
    }
}
