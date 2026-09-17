package com.gd.rain.event.projection

import com.gd.rain.event.AggregateSpec
import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventCatalogue
import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.EventNamespace
import com.gd.rain.event.FactSpec
import com.gd.rain.event.FactType
import com.gd.rain.event.OperationKey
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StreamRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.KClass

class ProjectionContractTest {
    @Test
    fun `checked cover has exactly one owner and scale out is a split rather than a remap`() {
        val whole = ProjectionCover.whole(ByKeyParity)
        val split = whole.split(ProjectionPartition.WHOLE)

        assertThat(whole.partitionFor(event("even", 1))).isEqualTo(ProjectionPartition.WHOLE)
        assertThat(split.members).containsExactly(ProjectionPartition(1, 0), ProjectionPartition(1, 1))
        assertThat(split.partitionFor(event("even", 1))).isEqualTo(ProjectionPartition(1, 0))
        assertThat(split.partitionFor(event("odd", 2))).isEqualTo(ProjectionPartition(1, 1))
        assertThat(split.fingerprint).isNotEqualTo(whole.fingerprint)
    }

    @Test
    fun `cover rejects holes and overlaps before a worker can claim a checkpoint`() {
        assertThatThrownBy {
            ProjectionCover.of(ByKeyParity, listOf(ProjectionPartition(1, 0)))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("projection cover has a hole or overlap")

        assertThatThrownBy {
            ProjectionCover.of(ByKeyParity, listOf(ProjectionPartition.WHOLE, ProjectionPartition(1, 0)))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("projection cover partitions overlap")
    }

    @Test
    fun `owned facts require an explicit route and invalid delivery semantics are closed`() {
        val specification =
            ProjectionSpec(
                name = ProjectionName.of("counter-view"),
                revision = ProjectionContractRevision(1),
                ownedFamilies = setOf("counter"),
                routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
                cover = ProjectionCover.whole(ByKeyParity),
                advancement = ProjectionAdvancement.SAME_UNIT,
                destination = ProjectionDestinationId.of("counter-view-db"),
                permanentFailurePolicy = ProjectionPermanentFailurePolicy.PARK_SEQUENCE,
                effectPolicy = ProjectionEffectPolicy.STAGED_DURABLE,
                budget = ProjectionRunBudget(pageSize = 10),
            )

        assertThat(specification.route(event("even", 1))).isEqualTo(ProjectionRoute.Handle)
        assertThat(specification.route(event("other", 2, family = "other"))).isNull()
        assertThatThrownBy { specification.route(event("missing", 3, type = "counter.decremented")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("projection has no route for an owned fact")
        assertThatThrownBy {
            ProjectionSpec(
                name = ProjectionName.of("counter-external"),
                revision = ProjectionContractRevision(1),
                ownedFamilies = setOf("counter"),
                routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
                cover = ProjectionCover.whole(ByKeyParity),
                advancement = ProjectionAdvancement.AFTER_APPLY,
                destination = ProjectionDestinationId.of("external-index"),
                permanentFailurePolicy = ProjectionPermanentFailurePolicy.PARK_SEQUENCE,
                effectPolicy = ProjectionEffectPolicy.DISABLED,
                budget = ProjectionRunBudget(pageSize = 10),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("park sequence requires same-unit projection delivery")
    }

    @Test
    fun `startup catalogue rejects a projection that does not route a newly declared owned fact`() {
        val specification =
            ProjectionSpec(
                name = ProjectionName.of("counter-view"),
                revision = ProjectionContractRevision(1),
                ownedFamilies = setOf("counter"),
                routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
                cover = ProjectionCover.whole(ByKeyParity),
                advancement = ProjectionAdvancement.AFTER_APPLY,
                destination = ProjectionDestinationId.of("counter-index"),
                permanentFailurePolicy = ProjectionPermanentFailurePolicy.HALT,
                effectPolicy = ProjectionEffectPolicy.DISABLED,
                budget = ProjectionRunBudget(pageSize = 10),
            )

        assertThatThrownBy {
            ProjectionCatalogue(
                EventCatalogue(setOf(Counter)),
                setOf(ProjectionDefinition(specification, ProjectionHandler {})),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("projection does not route every declared fact of its owned families")
    }

    @Test
    fun `generation plan freezes the barrier that suppresses historical effects`() {
        val origin = EventLogOrigin(EventLogId.of(ByteArray(EventLogId.BYTES) { 2 }))
        val contract =
            ProjectionCheckpointContract(
                origin,
                ProjectionContractRevision(1),
                ProjectionTopologyFingerprint.of(ByteArray(ProjectionTopologyFingerprint.BYTES) { 3 }),
            )
        val source = EventLogCursor.start(origin)
        val barrier = EventLogCursor.after(source, 9)
        val plan =
            ProjectionGenerationPlan.create(
                ProjectionName.of("counter-view"),
                ProjectionGeneration(2),
                contract,
                source,
                barrier,
                ProjectionEffectPolicy.STAGED_DURABLE,
            )

        assertThat(plan.effectBarrier).isEqualTo(ProjectionEffectBarrier(origin, 9))
        assertThat(ProjectionGenerationTransitions.allows(ProjectionGenerationState.BUILDING, ProjectionGenerationState.READY)).isTrue()
        assertThat(ProjectionGenerationTransitions.allows(ProjectionGenerationState.RETIRED, ProjectionGenerationState.ACTIVE)).isTrue()
        assertThat(ProjectionGenerationTransitions.allows(ProjectionGenerationState.FAILED, ProjectionGenerationState.BUILDING)).isFalse()
        assertThatThrownBy {
            ProjectionGenerationPlan(
                ProjectionName.of("counter-view"),
                ProjectionGeneration(2),
                contract,
                source,
                barrier,
                ProjectionEffectPolicy.STAGED_DURABLE,
                ProjectionEffectBarrier(origin, 8),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("projection effect barrier must equal the immutable rebuild barrier")
    }

    private fun event(
        key: String,
        position: Long,
        family: String = "counter",
        type: String = "counter.incremented",
    ): StoredEvent =
        StoredEvent(
            StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 1 }), family, key),
            1,
            position,
            FactType(type, 1),
            EventBytes.utf8("1"),
            EventMetadata(OperationKey.of("projection-$position")).canonicalBytes(),
            Instant.parse("2026-09-17T00:00:00Z"),
        )

    private object ByKeyParity : SequenceKeyHasher {
        override val id: SequenceKeyHasherId = SequenceKeyHasherId.of("counter.key.parity.v1")

        override fun hash(event: StoredEvent): Long = if (event.stream.key == "odd") 1 else 0

        override fun sequence(event: StoredEvent): ProjectionSequenceId = ProjectionSequenceId.forStream(id, event.stream)
    }

    private object Incremented : FactSpec<String> {
        override val type: String = "counter.incremented"
        override val kotlinType: KClass<String> = String::class
        override val readableRevisions: Set<Int> = setOf(1)
        override val writeRevision: Int = 1

        override fun write(value: String): EventBytes = EventBytes.utf8(value)

        override fun read(
            revision: Int,
            payload: EventBytes,
        ): String = String(payload.copy(), Charsets.UTF_8)
    }

    private object Decremented : FactSpec<String> {
        override val type: String = "counter.decremented"
        override val kotlinType: KClass<String> = String::class
        override val readableRevisions: Set<Int> = setOf(1)
        override val writeRevision: Int = 1

        override fun write(value: String): EventBytes = EventBytes.utf8(value)

        override fun read(
            revision: Int,
            payload: EventBytes,
        ): String = String(payload.copy(), Charsets.UTF_8)
    }

    private object Counter : AggregateSpec<String, String> {
        override val family: String = "counter"
        override val facts: Set<FactSpec<out Any>> = setOf(Incremented, Decremented)

        override fun streamKey(id: String): String = id

        override fun initial(id: String): String = ""

        override fun fold(
            state: String,
            fact: Any,
        ): String = state + fact
    }
}
