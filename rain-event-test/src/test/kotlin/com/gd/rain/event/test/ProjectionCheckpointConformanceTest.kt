package com.gd.rain.event.test

import com.gd.rain.event.EventLogId
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.projection.ProjectionCheckpointStore
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectionCheckpointConformanceTest {
    private val clock: MutableClock = MutableClock(Instant.parse("2026-09-17T00:00:00Z"))
    private val origin: EventLogOrigin = EventLogOrigin(EventLogId.of(ByteArray(EventLogId.BYTES) { 3 }))

    @Test
    fun `in-memory checkpoint reference passes every certified section`() {
        val report =
            ProjectionCheckpointConformance(
                Target("memory", origin, InMemoryProjectionCheckpointStore()),
                "memory",
                clock,
            ).verify()

        report.requireCertified()
        assertThat(report.sections.map(EventConformanceSection::status))
            .containsOnly(EventConformanceStatus.PASSED)
    }

    @Test
    fun `deliberately unfenced checkpoint fails only the stale advance section`() {
        val report =
            ProjectionCheckpointConformance(
                Target("unfenced", origin, UnfencedProjectionCheckpointStore()),
                "unfenced",
                clock,
            ).verify()

        assertThat(report.sections.filter { it.status == EventConformanceStatus.FAILED }.map(EventConformanceSection::id))
            .containsExactly("projection.checkpoint.fenced-advance")
    }

    private class Target(
        override val id: String,
        override val origin: EventLogOrigin,
        private val store: ProjectionCheckpointStore,
    ) : ProjectionCheckpointConformanceTarget {
        override fun checkpointStore(): ProjectionCheckpointStore = store
    }
}
