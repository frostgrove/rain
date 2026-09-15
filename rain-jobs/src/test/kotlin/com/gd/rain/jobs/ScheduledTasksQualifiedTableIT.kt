package com.gd.rain.jobs

import com.gd.rain.jobs.support.JobsDatabase
import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * db-scheduler reads and writes `rain_jobs.scheduled_tasks` when the builder is given the schema-qualified
 * name, on a connection whose `search_path` does not contain `rain_jobs`.
 */
@Tag("integration")
class ScheduledTasksQualifiedTableIT {
    @Test
    fun `a client schedules into and a scheduler polls, runs and removes from the qualified table`() {
        val fixture = JobsDatabase.fresh("qualified_table")
        assertThat(fixture.jdbc.queryForObject("SHOW search_path", String::class.java)).doesNotContain("rain_jobs")
        val ran = CountDownLatch(1)
        val task = Tasks.oneTime("probe").execute { _, _ -> ran.countDown() }

        SchedulerClient.Builder
            .create(fixture.dataSource, listOf(task))
            .tableName(TABLE)
            .enablePriority()
            .build()
            .schedule(task.instance("one"), Instant.now(), SchedulerClient.ScheduleOptions.WHEN_EXISTS_DO_NOTHING)
        assertThat(fixture.count("SELECT count(*) FROM rain_jobs.scheduled_tasks WHERE task_instance = 'one'")).isEqualTo(1)

        val scheduler =
            Scheduler
                .create(fixture.dataSource, task)
                .tableName(TABLE)
                .enablePriority()
                .threads(1)
                .pollingInterval(Duration.ofHours(1))
                .build()
        try {
            scheduler.start()
            scheduler.triggerCheckForDueExecutions()
            assertThat(ran.await(BOUND, TimeUnit.SECONDS)).describedAs("the scheduler ran the execution").isTrue()
        } finally {
            scheduler.stop()
        }

        assertThat(fixture.count("SELECT count(*) FROM rain_jobs.scheduled_tasks")).describedAs("the completion removed it").isZero()
        assertThat(fixture.count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'scheduled_tasks'"))
            .describedAs("only the module schema holds the table")
            .isEqualTo(1)
    }

    private companion object {
        const val TABLE = "rain_jobs.scheduled_tasks"
        const val BOUND = 30L
    }
}
