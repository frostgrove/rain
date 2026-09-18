package com.gd.rain.event.test

import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.projection.ProjectionCheckpointStore
import com.gd.rain.event.projection.SameUnitProjectionHoldStore
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectionHoldConformanceTest {
    private val clock: MutableClock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
    private val origin: EventLogOrigin = EventLogOrigin(EventLogId.of(ByteArray(EventLogId.BYTES) { 4 }))

    @Test
    fun `in-memory hold reference passes every certified section`() {
        val checkpoints = InMemoryProjectionCheckpointStore()
        val report =
            ProjectionHoldConformance(
                Target("memory-hold", origin, checkpoints, InMemoryProjectionHoldStore(checkpoints, clock)),
                "memory-hold",
                clock,
            ).verify()

        report.requireCertified()
        assertThat(report.sections.map(EventConformanceSection::status)).containsOnly(EventConformanceStatus.PASSED)
    }

    @Test
    fun `deliberately unfenced hold fails only the stale redrive acknowledgement section`() {
        val checkpoints = InMemoryProjectionCheckpointStore()
        val report =
            ProjectionHoldConformance(
                Target(
                    "unfenced-hold",
                    origin,
                    checkpoints,
                    UnfencedProjectionHoldStore(InMemoryProjectionHoldStore(checkpoints, clock)),
                ),
                "unfenced-hold",
                clock,
            ).verify()

        assertThat(report.sections.filter { it.status == EventConformanceStatus.FAILED }.map(EventConformanceSection::id))
            .containsExactly("projection.hold.redrive-fence")
    }

    private class Target(
        override val id: String,
        override val origin: EventLogOrigin,
        private val checkpoints: ProjectionCheckpointStore,
        private val holds: SameUnitProjectionHoldStore,
    ) : ProjectionHoldConformanceTarget {
        override fun checkpointStore(): ProjectionCheckpointStore = checkpoints

        override fun holdStore(): SameUnitProjectionHoldStore = holds

        override fun <T : Any> inUnit(block: () -> T): T = block()
    }
}
