package com.gd.rain.jobs.support

import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobProfile
import com.gd.rain.test.RainPostgres
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList

/** A minimal application that uses rain-jobs: one profile, one definition, one handler. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
class JobsApplication {
    val handled: MutableList<String> = CopyOnWriteArrayList()

    @Bean
    fun standard(): JobProfile = Fixtures.profile()

    @Bean
    fun notes(): JobDefinition<Note> = Fixtures.definition()

    @Bean
    fun notesHandler(): JobHandler<Note> =
        object : JobHandler<Note> {
            override val definition = Fixtures.definition()

            override fun handle(
                payload: Note,
                attempt: Attempt,
            ) {
                handled += payload.text
            }
        }
}

internal object JobsApplications {
    const val POOL_SIZE: Int = 30

    /** A real start against a fresh database, migrations applied by rain's strategy, polling only when triggered. */
    fun start(
        prefix: String,
        role: String,
    ): ConfigurableApplicationContext {
        val database = RainPostgres.freshDatabase(prefix)
        val properties =
            database.springProperties() +
                listOf(
                    "spring.application.name=jobs-it",
                    "rain.deployment.stage=test",
                    "rain.runtime.roles=$role",
                    "rain.persistence.statement-timeout=30s",
                    "spring.flyway.enabled=true",
                    "spring.datasource.hikari.maximum-pool-size=$POOL_SIZE",
                    "rain.jobs.workers.notes.write=2",
                    "rain.jobs.required-recurring=",
                    "rain.jobs.drain-grace=10s",
                    "rain.jobs.reserved-connections=5",
                    "rain.jobs.scheduler.poll-interval=1h",
                )
        return SpringApplicationBuilder(JobsApplication::class.java)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .properties(*properties.toTypedArray())
            .run()
    }
}
