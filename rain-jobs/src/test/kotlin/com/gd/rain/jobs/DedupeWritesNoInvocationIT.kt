package com.gd.rain.jobs

import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.jobs.support.QueueFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/** Gap 5: the reservation comes before the invocation, so an absorbed order writes no row — only a count on its holder. */
@Tag("integration")
class DedupeWritesNoInvocationIT {
    @Test
    fun `absorbed orders write no invocation and no execution, and are counted on the holder`() {
        val fixture = QueueFixture("dedupe_no_row")
        val holder = fixture.queue.enqueue(Fixtures.definition(), Note("a"), Fixtures.options(Dedupe.Collapse("k")))

        fixture.clock.advance(Duration.ofSeconds(3))
        repeat(3) { fixture.queue.enqueue(Fixtures.definition(), Note("b"), Fixtures.options(Dedupe.Collapse("k"))) }

        assertThat(fixture.invocations()).isEqualTo(1)
        assertThat(fixture.executions()).isEqualTo(1)
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_invocation WHERE state = 'cancelled'")).isZero()
        val counted =
            fixture.database.jdbc.queryForMap(
                "SELECT absorbed_count, last_absorbed_at = ? AS absorbed_now FROM rain_jobs.job_invocation WHERE id = ?",
                java.sql.Timestamp.from(Fixtures.START.plusSeconds(3)),
                holder.invocation,
            )
        assertThat(counted["absorbed_count"]).isEqualTo(3)
        assertThat(counted["absorbed_now"]).isEqualTo(true)
        assertThat(fixture.database.count("SELECT count(*) FROM rain_jobs.job_intent")).describedAs("one reservation").isEqualTo(1)
    }
}
