package com.gd.rain.jobs

import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.jobs.internal.execution.AttemptStatementTimeout
import com.gd.rain.jobs.internal.worker.JobsWorker
import com.gd.rain.jobs.support.Awaits
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.JobsApplication
import com.gd.rain.jobs.support.JobsApplications
import com.gd.rain.jobs.support.Note
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.ConfigurableTransactionManager
import org.springframework.transaction.PlatformTransactionManager

/** Real application starts: the client in every role, the worker machinery only where the worker role is active. */
@Tag("integration")
class RoleGatingIT {
    @Test
    fun `an api process enqueues work and builds no scheduler, renewer or recurring task`() {
        JobsApplications.start("roles_api", "api").use { context ->
            assertThat(context.getBeanNamesForType(JobsWorker::class.java)).isEmpty()
            assertThat(context.getBeanNamesForType(JobsHealth::class.java)).isEmpty()
            assertThat(context.getBeanNamesForType(AttemptStatementTimeout::class.java)).isEmpty()
            assertThat(context.getBean(JobAdministration::class.java).definitions().map { it.name }).containsExactly("notes.write")

            context.getBean(WorkQueue::class.java).enqueue(Fixtures.definition(), Note("from api"), Fixtures.options())

            val jdbc = context.getBean(JdbcTemplate::class.java)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM rain_jobs.job_invocation WHERE state = 'queued'", Long::class.java),
            ).isEqualTo(1)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM rain_jobs.scheduled_tasks WHERE task_instance = 'recurring'", Long::class.java),
            ).describedAs("no recurring task was registered").isZero()
            assertThat(Thread.getAllStackTraces().keys.map { it.name }).noneMatch { it.startsWith("db-scheduler") }
        }
    }

    @Test
    fun `a worker process starts its schedulers and rain's recurring work, runs enqueued work, and stops within its grace`() {
        val context = JobsApplications.start("roles_worker", "worker")
        val worker = context.getBean(JobsWorker::class.java)
        try {
            assertThat(worker.isRunning).isTrue()
            assertThat(
                context
                    .getBean(JobsHealth::class.java)
                    .report()
                    .schedulers
                    .map { it.name to it.threads },
            ).containsExactly("standard" to 2, SchedulerHealth.RECURRING to 2)
            val transactions = context.getBean(PlatformTransactionManager::class.java) as ConfigurableTransactionManager
            assertThat(transactions.transactionExecutionListeners)
                .describedAs("every transaction an attempt opens is bounded")
                .anyMatch { it is AttemptStatementTimeout }
            val jdbc = context.getBean(JdbcTemplate::class.java)
            assertThat(
                jdbc.queryForList(
                    "SELECT task_name FROM rain_jobs.scheduled_tasks WHERE task_instance = 'recurring' ORDER BY 1",
                    String::class.java,
                ),
            ).containsExactly("rain.jobs.reaper", "rain.jobs.retention")

            context.getBean(WorkQueue::class.java).enqueue(Fixtures.definition(), Note("from worker"), Fixtures.options())
            worker.schedulers().forEach { it.triggerPoll() }

            val application = context.getBean(JobsApplication::class.java)
            Awaits.until("the handler to run") { application.handled.contains("from worker") }
        } finally {
            context.close()
        }
        assertThat(worker.isRunning).isFalse()
        assertThat(worker.lastStop?.unfinished).isEmpty()
    }
}
