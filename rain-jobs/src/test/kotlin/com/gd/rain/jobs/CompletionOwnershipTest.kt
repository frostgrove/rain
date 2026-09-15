package com.gd.rain.jobs

import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.internal.ledger.InvocationSnapshot
import com.gd.rain.jobs.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Gap 2: a refused claim or a refused lease-guarded write maps to exactly one execution outcome, by the row's current state. */
class CompletionOwnershipTest {
    private val now = Fixtures.START
    private val reaper = Duration.ofSeconds(15)
    private val id = UUID(0, 7)

    private fun refused(snapshot: InvocationSnapshot?): Completion = Completion.afterRefusal(snapshot, 3, now, reaper)

    private fun snapshot(
        state: JobState,
        generation: Int = 3,
        lease: java.time.Instant? = null,
    ) = InvocationSnapshot(id, state, generation, now.plusSeconds(40), lease)

    @Test
    fun `an invocation that is gone, of another generation, or terminal leaves nothing for the execution`() {
        assertThat(refused(null)).isEqualTo(Completion.Remove)
        assertThat(refused(snapshot(JobState.QUEUED, generation = 4))).isEqualTo(Completion.Remove)
        JobState.entries.filter { it.terminal }.forEach { state ->
            assertThat(refused(snapshot(state))).describedAs(state.wire).isEqualTo(Completion.Remove)
        }
    }

    @Test
    fun `a queued invocation is delivered by this execution when it is eligible`() {
        assertThat(refused(snapshot(JobState.QUEUED))).isEqualTo(Completion.Reschedule(now.plusSeconds(40)))
    }

    @Test
    fun `a running invocation is looked at again when its lease would expire, or one reaper interval on when it already has`() {
        assertThat(refused(snapshot(JobState.RUNNING, lease = now.plusSeconds(9)))).isEqualTo(Completion.Reschedule(now.plusSeconds(9)))
        assertThat(refused(snapshot(JobState.RUNNING, lease = now))).isEqualTo(Completion.Reschedule(now.plus(reaper)))
        assertThat(refused(snapshot(JobState.RUNNING, lease = now.minusSeconds(1)))).isEqualTo(Completion.Reschedule(now.plus(reaper)))
    }
}
