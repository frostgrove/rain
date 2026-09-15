package com.gd.rain.jobs

import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.jobs.support.QueueFixture
import com.gd.rain.jobs.support.WorkerFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch

/** The schedulers a worker really builds, per declared profile, and a real poll claiming and running work. */
@Tag("integration")
class SchedulerTopologyIT {
    private val interactive = Fixtures.profile("interactive")
    private val batch = Fixtures.profile("batch")
    private val summarize = Fixtures.definition("tickets.summarize", "interactive")
    private val index = Fixtures.definition("tickets.index", "interactive")
    private val export = Fixtures.definition("tickets.export", "batch")
    private val swept = CountDownLatch(1)
    private val sweep =
        object : RecurringWork {
            override val name = "tickets.sweep"
            override val interval: Duration = Duration.ofMinutes(5)

            override fun run() = swept.countDown()
        }
    private val audit =
        object : RecurringWork {
            override val name = "tickets.audit"
            override val interval: Duration = Duration.ofMinutes(5)

            override fun run() = Unit
        }

    private fun handler(
        definition: JobDefinition<Note>,
        body: (Note) -> Unit = {},
    ): JobHandler<Note> =
        object : JobHandler<Note> {
            override val definition = definition

            override fun handle(
                payload: Note,
                attempt: Attempt,
            ) = body(payload)
        }

    private fun fixture(
        prefix: String,
        handlers: (QueueFixture) -> List<JobHandler<*>> = { listOf(handler(summarize), handler(index), handler(export)) },
    ) = WorkerFixture(
        prefix,
        listOf(interactive, batch),
        listOf(summarize, index, export),
        mapOf("tickets.summarize" to 3, "tickets.index" to 1, "tickets.export" to 2),
        handlers,
        listOf(sweep, audit),
    )

    @Test
    fun `each declared profile gets one scheduler with the sum of its ceilings, and recurring work one thread per task`() {
        val fixture = fixture("topology_threads")
        fixture.worker.start()
        try {
            assertThat(
                fixture.worker
                    .report()
                    .schedulers
                    .map { it.name },
            ).containsExactly("batch", "interactive", SchedulerHealth.RECURRING)
            assertThat(fixture.threadsOf("interactive")).isEqualTo(4)
            assertThat(fixture.threadsOf("batch")).isEqualTo(2)
            assertThat(fixture.threadsOf(SchedulerHealth.RECURRING)).isEqualTo(2)
            assertThat(
                fixture
                    .scheduler("interactive")
                    .spec.tasks
                    .map { it.name },
            ).containsExactlyInAnyOrder("tickets.summarize", "tickets.index")
            assertThat(fixture.worker.report().schedulers).allMatch { it.started && !it.shuttingDown }
            fixture.worker.schedulers().forEach { it.triggerPoll() }
            Awaits.until("every scheduler to report a poll") {
                fixture.worker
                    .report()
                    .schedulers
                    .all { it.lastPoll == Fixtures.START }
            }
        } finally {
            fixture.worker.stop()
        }
    }

    @Test
    fun `recurring work owns its execution row from start-up, and a due one runs once polled`() {
        val fixture = fixture("topology_recurring")
        fixture.worker.start()
        try {
            assertThat(
                fixture.queue.database.jdbc.queryForList(
                    "SELECT task_name FROM rain_jobs.scheduled_tasks WHERE task_instance = 'recurring' ORDER BY task_name",
                    String::class.java,
                ),
            ).containsExactly("tickets.audit", "tickets.sweep")

            fixture.scheduler(SchedulerHealth.RECURRING).triggerPoll()

            Awaits.latch(swept, "the recurring sweep to run")
        } finally {
            fixture.worker.stop()
        }
    }

    @Test
    fun `a real scheduler claims enqueued work, runs its handler and removes the execution`() {
        val handled = CountDownLatch(1)
        val fixture =
            fixture("topology_pickup") { _ ->
                listOf(handler(summarize) { handled.countDown() }, handler(index), handler(export))
            }
        fixture.worker.start()
        try {
            val id =
                fixture.queue.queue
                    .enqueue(summarize, Note("n"), Fixtures.options())
                    .invocation

            fixture.scheduler("interactive").triggerPoll()

            Awaits.latch(handled, "the handler to run")
            Awaits.until("the invocation to succeed and its execution to be removed") {
                fixture.queue.database.count(
                    "SELECT count(*) FROM rain_jobs.job_invocation WHERE state = 'succeeded' AND id = '$id'",
                ) == 1L &&
                    fixture.queue.executions() == 2L
            }
            assertThat(
                fixture.queue.database.jdbc
                    .queryForObject("SELECT picked_by FROM rain_jobs.job_invocation WHERE id = ?", String::class.java, id),
            ).isEqualTo("it-host#interactive")
        } finally {
            fixture.worker.stop()
        }
        assertThat(fixture.worker.isRunning).isFalse()
        assertThat(fixture.worker.lastStop?.unfinished).isEmpty()
        assertThat(fixture.worker.report().schedulers).allMatch { it.shuttingDown }
    }
}
