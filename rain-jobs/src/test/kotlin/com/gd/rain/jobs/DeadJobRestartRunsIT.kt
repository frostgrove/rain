package com.gd.rain.jobs

import com.gd.rain.jobs.admin.RedriveOutcome
import com.gd.rain.jobs.support.AdminFixture
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.jobs.support.WorkerFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** Gap 3: a restarted dead job does not merely look queued — a real scheduler delivers it and it runs. */
@Tag("integration")
class DeadJobRestartRunsIT {
    @Test
    fun `a dead invocation redriven by an operator is delivered by a real scheduler and succeeds`() {
        val calls = AtomicInteger()
        val profile = Fixtures.profile(retries = 0)
        val definition = Fixtures.definition()
        val fixture =
            WorkerFixture(
                "dead_job_restart",
                listOf(profile),
                listOf(definition),
                mapOf(definition.name to 1),
                handlers = { _ ->
                    listOf(
                        object : JobHandler<Note> {
                            override val definition = definition

                            override fun handle(
                                payload: Note,
                                attempt: Attempt,
                            ) {
                                if (calls.incrementAndGet() == 1) error("the converter was down")
                            }
                        },
                    )
                },
            )
        val administration = AdminFixture(fixture.queue).administration
        val id =
            fixture.queue.queue
                .enqueue(definition, Note("n"), Fixtures.options())
                .invocation

        fixture.worker.start()
        try {
            Awaits.until("the first attempt to leave the invocation dead") { stateOf(fixture, id) == "dead" }

            assertThat(administration.redrive(id)).isEqualTo(RedriveOutcome.Redriven(id, 1))
            fixture.scheduler(profile.id).triggerPoll()

            Awaits.until("the redriven invocation to succeed") { stateOf(fixture, id) == "succeeded" }
        } finally {
            fixture.worker.stop()
        }
        assertThat(calls.get()).isEqualTo(2)
        assertThat(fixture.queue.database.count("SELECT attempts FROM rain_jobs.job_invocation WHERE id = '$id'")).isEqualTo(2)
    }

    private fun stateOf(
        fixture: WorkerFixture,
        id: java.util.UUID,
    ): String? =
        fixture.queue.database.jdbc
            .queryForObject("SELECT state FROM rain_jobs.job_invocation WHERE id = ?", String::class.java, id)
}
