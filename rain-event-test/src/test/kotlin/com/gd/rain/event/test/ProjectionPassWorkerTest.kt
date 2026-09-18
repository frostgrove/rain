package com.gd.rain.event.test

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.projection.ProjectionCheckpoint
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionCover
import com.gd.rain.event.projection.ProjectionCutover
import com.gd.rain.event.projection.ProjectionEffectPolicy
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionGenerationPlan
import com.gd.rain.event.projection.ProjectionGenerationReadiness
import com.gd.rain.event.projection.ProjectionGenerationRecord
import com.gd.rain.event.projection.ProjectionGenerationRegistration
import com.gd.rain.event.projection.ProjectionGenerationState
import com.gd.rain.event.projection.ProjectionGenerationStore
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionPass
import com.gd.rain.event.projection.ProjectionPassBinding
import com.gd.rain.event.projection.ProjectionPassHintCodec
import com.gd.rain.event.projection.ProjectionPassHintConsumer
import com.gd.rain.event.projection.ProjectionPassHintDelivery
import com.gd.rain.event.projection.ProjectionPassLaneSource
import com.gd.rain.event.projection.ProjectionPassRequest
import com.gd.rain.event.projection.ProjectionPassRunners
import com.gd.rain.event.projection.ProjectionPassScheduling
import com.gd.rain.event.projection.ProjectionPassSweep
import com.gd.rain.event.projection.ProjectionPassWorker
import com.gd.rain.event.projection.SequenceKeyHasher
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.AttemptMeta
import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.EnqueueOutcome
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.WorkQueue
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ProjectionPassWorkerTest {
    @Test
    fun `stale queued checkpoint hint schedules a fresh pass without invoking the runner`() {
        val fixture = Fixture(ProjectionGenerationState.ACTIVE, maxPages = 2)
        val stale = ProjectionPassRequest.forLane(fixture.lane, fixture.checkpoint)
        val reclaimed =
            fixture.checkpoints.claim(
                fixture.lane,
                fixture.contract,
                fixture.checkpoint.cursor,
                fixture.clock.instant().plusSeconds(1),
                Duration.ofMinutes(1),
            ) as ProjectionClaim.Acquired
        fixture.checkpoints.release(reclaimed.lease)

        fixture.worker.handle(stale, ImmediateAttempt)

        assertThat(fixture.invocations).isZero()
        assertThat(fixture.queue.entries).hasSize(1)
        val entry = fixture.queue.entries.single()
        assertThat(entry.payload.expectedCheckpoint).isNotEqualTo(stale.expectedCheckpoint)
        assertThat(entry.options.dedupe).isEqualTo(Dedupe.Collapse("projection-pass:counter:1:p0:0"))
    }

    @Test
    fun `active generation consumes only its declared pages then places one successor`() {
        val fixture = Fixture(ProjectionGenerationState.ACTIVE, maxPages = 2)

        fixture.worker.handle(ProjectionPassRequest.forLane(fixture.lane, fixture.checkpoint), ImmediateAttempt)

        assertThat(fixture.invocations).isEqualTo(2)
        assertThat(fixture.queue.entries).hasSize(1)
        val entry = fixture.queue.entries.single()
        assertThat(entry.options.after).isEqualTo(Duration.ZERO)
        assertThat(entry.options.dedupe).isInstanceOf(Dedupe.Collapse::class.java)
    }

    @Test
    fun `building generation reads one page and paces its successor from the durable checkpoint timestamp`() {
        val fixture = Fixture(ProjectionGenerationState.BUILDING, maxPages = 3)

        fixture.worker.handle(ProjectionPassRequest.forLane(fixture.lane, fixture.checkpoint), ImmediateAttempt)

        assertThat(fixture.invocations).isOne()
        assertThat(fixture.queue.entries).hasSize(1)
        val entry = fixture.queue.entries.single()
        assertThat(entry.options.after).isEqualTo(Duration.ofSeconds(30))
        assertThat(entry.options.subjectKey).isEqualTo(SubjectKey("projection-pass:counter:1:p0:0"))
    }

    @Test
    fun `realtime hint is a best-effort kick and malformed payload is ignored`() {
        val fixture = Fixture(ProjectionGenerationState.ACTIVE, maxPages = 1)

        assertThat(ProjectionPassHintCodec.decode(ProjectionPassHintCodec.encode(fixture.lane))?.lane).isEqualTo(fixture.lane)
        assertThat(ProjectionPassHintCodec.decode("not-a-projection-hint")).isNull()
        assertThat(ProjectionPassHintCodec.decode("x".repeat(8_001))).isNull()
        assertThat(ProjectionPassHintConsumer(fixture.worker).consume("not-a-projection-hint"))
            .isEqualTo(ProjectionPassHintDelivery.Ignored)
        assertThat(ProjectionPassHintConsumer(fixture.worker).consume(ProjectionPassHintCodec.encode(fixture.lane)))
            .isInstanceOf(ProjectionPassHintDelivery.Kicked::class.java)
        assertThat(fixture.queue.entries).hasSize(1)
    }

    @Test
    fun `bounded recurring sweep walks lanes then wraps after its keyset cursor`() {
        val fixture = Fixture(ProjectionGenerationState.ACTIVE, maxPages = 1)
        val lanes =
            listOf(
                fixture.lane,
                fixture.lane.copy(partition = ProjectionPartition(1, 0)),
                fixture.lane.copy(partition = ProjectionPartition(1, 1)),
            )
        val source =
            ProjectionPassLaneSource { after, limit ->
                lanes
                    .drop(if (after == null) 0 else lanes.indexOf(after) + 1)
                    .take(limit)
            }
        val sweep = ProjectionPassSweep("projection.pass-sweep", Duration.ofMinutes(1), source, fixture.worker, 2)

        sweep.run()
        assertThat(fixture.queue.entries).hasSize(2)
        sweep.run()
        assertThat(fixture.queue.entries).hasSize(3)
        sweep.run()
        assertThat(fixture.queue.entries).hasSize(5)
    }

    private class Fixture(
        state: ProjectionGenerationState,
        maxPages: Int,
    ) {
        val clock: MutableClock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
        private val origin: EventLogOrigin = EventLogOrigin(EventLogId.of(ByteArray(EventLogId.BYTES) { 9 }))
        private val cover: ProjectionCover = ProjectionCover.whole(ByPosition)
        val lane: ProjectionLane = ProjectionLane(ProjectionName.of("counter"), ProjectionGeneration(1), ProjectionPartition.WHOLE)
        val contract: ProjectionCheckpointContract = ProjectionCheckpointContract(origin, ProjectionContractRevision(1), cover.fingerprint)
        val checkpoints: InMemoryProjectionCheckpointStore = InMemoryProjectionCheckpointStore()
        val checkpoint: ProjectionCheckpoint
        val queue: RecordingQueue = RecordingQueue()
        var invocations: Int = 0
        val worker: ProjectionPassWorker

        init {
            val initial = EventLogCursor.start(origin)
            val claim = checkpoints.claim(lane, contract, initial, clock.instant(), Duration.ofMinutes(1)) as ProjectionClaim.Acquired
            val advanced =
                (
                    checkpoints.advance(claim.lease, EventLogCursor.after(initial, 1), clock.instant())
                        as com.gd.rain.event.projection.ProjectionAdvance.Advanced
                )
            checkpoints.release(claim.lease)
            checkpoint = advanced.checkpoint
            val plan =
                ProjectionGenerationPlan.create(
                    lane.projection,
                    lane.generation,
                    contract,
                    initial,
                    checkpoint.cursor,
                    ProjectionEffectPolicy.DISABLED,
                )
            val generation =
                ProjectionGenerationRecord(
                    plan,
                    state,
                    clock.instant(),
                    if (state == ProjectionGenerationState.ACTIVE) clock.instant() else null,
                )
            worker =
                ProjectionPassWorker(
                    JobDefinition.of("projection.pass", "projection"),
                    checkpoints,
                    FixedGenerations(generation),
                    ProjectionPassRunners(
                        setOf(
                            ProjectionPassBinding(
                                lane.projection,
                                { invoked ->
                                    assertThat(invoked).isEqualTo(lane)
                                    invocations++
                                    ProjectionPass.Progress(checkpoint, hasMore = true)
                                },
                                maxPages,
                            ),
                        ),
                    ),
                    queue,
                    clock,
                    ProjectionPassScheduling(JobPriority(20), Duration.ofSeconds(5), Duration.ofSeconds(30)),
                )
        }
    }

    private class FixedGenerations(
        private val record: ProjectionGenerationRecord,
    ) : ProjectionGenerationStore {
        override fun register(plan: ProjectionGenerationPlan): ProjectionGenerationRegistration = error("not used")

        override fun generation(
            projection: ProjectionName,
            generation: ProjectionGeneration,
        ): ProjectionGenerationRecord? = record.takeIf { it.plan.projection == projection && it.plan.generation == generation }

        override fun active(projection: ProjectionName): ProjectionGenerationRecord? =
            record.takeIf { it.plan.projection == projection && it.state == ProjectionGenerationState.ACTIVE }

        override fun runnable(
            projection: ProjectionName,
            limit: Int,
        ): List<ProjectionGenerationRecord> =
            record
                .takeIf {
                    it.plan.projection == projection &&
                        it.state in setOf(ProjectionGenerationState.BUILDING, ProjectionGenerationState.ACTIVE)
                }?.let(::listOf)
                .orEmpty()

        override fun markReady(
            plan: ProjectionGenerationPlan,
            cover: ProjectionCover,
        ): ProjectionGenerationReadiness = error("not used")

        override fun cutover(
            projection: ProjectionName,
            expectedActive: ProjectionGeneration?,
            candidate: ProjectionGeneration,
        ): ProjectionCutover = error("not used")
    }

    private class RecordingQueue : WorkQueue {
        val entries: MutableList<Entry> = mutableListOf()

        override fun <P : Any> enqueue(
            definition: JobDefinition<P>,
            payload: P,
            options: EnqueueOptions,
        ): EnqueueOutcome {
            require(definition.name == "projection.pass")
            entries += Entry(payload as ProjectionPassRequest, options)
            return EnqueueOutcome.Scheduled(UUID(0, entries.size.toLong()))
        }

        data class Entry(
            val payload: ProjectionPassRequest,
            val options: EnqueueOptions,
        )
    }

    private object ImmediateAttempt : Attempt {
        override val meta: AttemptMeta =
            AttemptMeta(
                UUID(0, 1),
                "projection.pass",
                "projection",
                1,
                0,
                1,
                0,
                Instant.parse("2026-09-17T00:01:00Z"),
                null,
            )

        override fun <T> step(
            budget: Duration,
            body: () -> T,
        ): T = body()

        override fun <T> fenced(
            guards: List<com.gd.rain.core.lock.Guard>,
            effect: () -> T,
        ): T = effect()
    }

    private object ByPosition : SequenceKeyHasher {
        override val id: SequenceKeyHasherId = SequenceKeyHasherId.of("counter.position.v1")

        override fun hash(event: StoredEvent): Long = event.position

        override fun sequence(event: StoredEvent): com.gd.rain.event.projection.ProjectionSequenceId =
            com.gd.rain.event.projection.ProjectionSequenceId
                .forStream(id, event.stream)
    }
}
