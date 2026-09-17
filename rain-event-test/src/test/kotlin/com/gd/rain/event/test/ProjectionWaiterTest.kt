package com.gd.rain.event.test

import com.gd.rain.event.EventLogCursor
import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.projection.ProjectionCheckpointContract
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
import com.gd.rain.event.projection.ProjectionMark
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionTopologyFingerprint
import com.gd.rain.event.projection.ProjectionWaitOutcome
import com.gd.rain.event.projection.ProjectionWaiter
import com.gd.rain.event.projection.SequenceKeyHasher
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ProjectionWaiterTest {
    @Test
    fun `waiter reaches only after the durable full-cover checkpoint passes the requested mark`() {
        val clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
        val origin = EventLogOrigin(EventLogId.of(ByteArray(EventLogId.BYTES) { 1 }))
        val source = EventLogCursor.start(origin)
        val barrier = EventLogCursor.after(source, 2)
        val cover = ProjectionCover.whole(ByStream)
        val plan =
            ProjectionGenerationPlan.create(
                ProjectionName.of("counter-view"),
                ProjectionGeneration(1),
                ProjectionCheckpointContract(origin, ProjectionContractRevision(1), cover.fingerprint),
                source,
                barrier,
                ProjectionEffectPolicy.DISABLED,
            )
        val checkpoints = InMemoryProjectionCheckpointStore()
        val lane = ProjectionLane(plan.projection, plan.generation, cover.members.single())
        val claim =
            checkpoints.claim(
                lane,
                plan.contract,
                source,
                clock.instant(),
                Duration.ofMinutes(1),
            ) as com.gd.rain.event.projection.ProjectionClaim.Acquired
        checkpoints.advance(claim.lease, barrier, clock.instant())
        val executor = ScheduledThreadPoolExecutor(1)
        try {
            val waiter =
                ProjectionWaiter(ActiveGeneration(plan), checkpoints, clock, executor, Duration.ofMillis(10), Duration.ofSeconds(1))
            val result = waiter.await(plan.projection, cover, ProjectionMark(origin, 2), clock.instant().plusSeconds(1))

            assertThat(result.completion.toCompletableFuture().get(1, TimeUnit.SECONDS))
                .isEqualTo(ProjectionWaitOutcome.Reached(ProjectionGeneration(1)))
        } finally {
            executor.shutdownNow()
        }
    }

    private object ByStream : SequenceKeyHasher {
        override val id: SequenceKeyHasherId = SequenceKeyHasherId.of("stream.v1")

        override fun hash(event: com.gd.rain.event.StoredEvent): Long = event.position

        override fun sequence(event: com.gd.rain.event.StoredEvent): com.gd.rain.event.projection.ProjectionSequenceId =
            com.gd.rain.event.projection.ProjectionSequenceId
                .forStream(id, event.stream)
    }

    private class ActiveGeneration(
        private val plan: ProjectionGenerationPlan,
    ) : ProjectionGenerationStore {
        private val record = ProjectionGenerationRecord(plan, ProjectionGenerationState.ACTIVE, Instant.EPOCH, Instant.EPOCH)

        override fun register(plan: ProjectionGenerationPlan): ProjectionGenerationRegistration = error("not used")

        override fun generation(
            projection: ProjectionName,
            generation: ProjectionGeneration,
        ): ProjectionGenerationRecord? = if (projection == plan.projection && generation == plan.generation) record else null

        override fun active(projection: ProjectionName): ProjectionGenerationRecord? = if (projection == plan.projection) record else null

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
}
