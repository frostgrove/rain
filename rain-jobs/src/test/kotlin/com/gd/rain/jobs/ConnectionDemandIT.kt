package com.gd.rain.jobs

import com.gd.rain.jobs.internal.worker.DbManagedScheduler
import com.gd.rain.jobs.internal.worker.JobsWorker
import com.gd.rain.jobs.support.JobsApplications
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * The connection demand the check computes is read against the worker a real start builds: every term that counts
 * threads is compared with db-scheduler's own pool sizes, not with the numbers the wiring meant to pass.
 */
@Tag("integration")
class ConnectionDemandIT {
    @Test
    fun `the demand counts the pools the worker really built and fits the pool that was stated`() {
        JobsApplications.start("connection_demand", "worker").use { context ->
            val check = context.getBean("connectionDemandCheck") as ConnectionDemandCheck
            val demand = check.demand()
            val pools =
                context.getBean(JobsWorker::class.java).schedulers().map { scheduler ->
                    val built = (scheduler as DbManagedScheduler).scheduler
                    val field = built.javaClass.getDeclaredField("threadpoolSize")
                    field.isAccessible = true
                    field.getInt(built)
                }

            assertThat(demand.profileThreads + demand.recurringThreads).isEqualTo(pools.sum())
            assertThat(demand.schedulers).isEqualTo(pools.size)
            assertThat((context.getBean(DataSource::class.java) as HikariDataSource).maximumPoolSize).isEqualTo(JobsApplications.POOL_SIZE)
            assertThat(demand.total).isLessThanOrEqualTo(JobsApplications.POOL_SIZE)
            assertThat(check.problems()).isEmpty()
        }
    }
}
