package com.gd.rain.jobs

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.jobs.autoconfigure.RainJobsAutoConfiguration
import com.gd.rain.jobs.internal.JobCatalog
import com.gd.rain.jobs.internal.execution.AttemptStatementTimeout
import com.gd.rain.jobs.internal.worker.JobsWorker
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.Note
import com.gd.rain.persistence.autoconfigure.RainPersistenceAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/** Wiring only: the data source points nowhere and nothing here connects. */
class JobsAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class Declarations {
        @Bean
        fun standard(): JobProfile = Fixtures.profile()

        @Bean
        fun notes(): JobDefinition<Note> = Fixtures.definition()
    }

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    JooqAutoConfiguration::class.java,
                    RainRuntimeAutoConfiguration::class.java,
                    RainPersistenceAutoConfiguration::class.java,
                    RainJobsAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(Declarations::class.java)
            .withBean(DataSource::class.java, { PGSimpleDataSource().apply { setURL("jdbc:postgresql://127.0.0.1:1/none") } })
            .withPropertyValues(
                "rain.deployment.stage=test",
                "rain.persistence.statement-timeout=5s",
                "rain.jobs.workers.notes.write=2",
                "rain.jobs.required-recurring=",
                "rain.jobs.drain-grace=10s",
                "rain.jobs.reserved-connections=4",
            )

    private fun refusalOf(failure: Throwable): ConfigurationProblemsException =
        generateSequence(failure) { it.cause }.filterIsInstance<ConfigurationProblemsException>().first()

    @Test
    fun `an api process enqueues and administers, and runs no worker machinery`() {
        runner.withPropertyValues("rain.runtime.roles=api").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(WorkQueue::class.java)
            assertThat(context).hasSingleBean(JobAdministration::class.java)
            assertThat(context).hasSingleBean(JobCatalog::class.java)
            assertThat(context).doesNotHaveBean(JobsWorker::class.java)
            assertThat(context).doesNotHaveBean(JobsHealth::class.java)
            assertThat(context).doesNotHaveBean(AttemptStatementTimeout::class.java)
            assertThat(
                context
                    .getBean(JobAdministration::class.java)
                    .definitions()
                    .single()
                    .workers,
            ).isEqualTo(2)
        }
    }

    @Test
    fun `a worker whose definition has no handler is refused before anything starts, with the problem named`() {
        runner.withPropertyValues("rain.runtime.roles=worker", "spring.datasource.hikari.maximum-pool-size=50").run { context ->
            assertThat(context).hasFailed()
            assertThat(
                refusalOf(checkNotNull(context.startupFailure)).problems.map { it.path },
            ).containsExactly("jobs:definition:notes.write")
        }
    }

    @Test
    fun `an application's own work queue replaces rain's`() {
        val recording =
            object : WorkQueue {
                override fun <P : Any> enqueue(
                    definition: JobDefinition<P>,
                    payload: P,
                    options: EnqueueOptions,
                ): EnqueueOutcome = EnqueueOutcome.Scheduled(java.util.UUID(0, 1))
            }

        runner.withPropertyValues("rain.runtime.roles=api").withBean(WorkQueue::class.java, { recording }).run { context ->
            assertThat(context.getBean(WorkQueue::class.java)).isSameAs(recording)
        }
    }

    @Test
    fun `a ceiling for a definition nobody declared refuses every role`() {
        runner.withPropertyValues("rain.runtime.roles=api", "rain.jobs.workers.notes.gone=1").run { context ->
            assertThat(context).hasFailed()
            assertThat(
                refusalOf(checkNotNull(context.startupFailure)).problems.map { it.path },
            ).containsExactly("rain.jobs.workers.notes.gone")
        }
    }
}
