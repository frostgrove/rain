package com.gd.rain.access.it

import com.gd.rain.access.support.AccessApplication
import com.gd.rain.access.support.accessProperties
import com.gd.rain.access.support.awaitUntil
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.test.RainApplication
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.jdbc.core.JdbcTemplate

/**
 * rain-access runs its recurring work on rain-jobs, so an access application has rain-jobs whether or not it declares a
 * job of its own. `rain.jobs.workers` used to be required as a whole: an application with no definition could state no
 * ceiling (`workers: {}` in YAML states nothing) and was refused, and the access test application declared a job it never
 * ran to start at all. An application that declares none now states none, and its worker runs rain-access's work.
 */
@Tag("integration")
class AccessWithoutJobDefinitionsIT {
    @Test
    fun `a worker declaring no job definition states rain-jobs with no workers and runs session retention on rain-jobs`() {
        val database = RainPostgres.freshDatabase("access_without_jobs")
        val stated =
            accessProperties("rain.runtime.roles=worker", "rain.health.checks.jobs=informational")
                .filterNot { it.startsWith("rain.jobs.") }
                .plus(database.springProperties())
                .plus(listOf("spring.config.import=classpath:access/without-jobs.yml", "spring.datasource.hikari.maximum-pool-size=20"))

        RainApplication.start(listOf(AccessApplication::class.java), WebApplicationType.NONE, stated).use { worker ->
            val jdbc = JdbcTemplate(database.dataSource())

            assertThat(worker.bean(JobAdministration::class).definitions()).isEmpty()
            awaitUntil("session retention to finish a run on rain-jobs' recurring scheduler") {
                jdbc.queryForObject(
                    "SELECT count(*) FROM rain_jobs.scheduled_tasks WHERE task_name = 'access.session-retention' AND last_success IS NOT NULL",
                    Long::class.java,
                ) == 1L
            }
        }
    }
}
