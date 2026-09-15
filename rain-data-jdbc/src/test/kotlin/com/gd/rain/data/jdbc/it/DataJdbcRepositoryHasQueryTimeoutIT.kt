package com.gd.rain.data.jdbc.it

import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.sql.SQLException
import java.util.UUID

interface SleepingRepository : Repository<Note, UUID> {
    @Query("SELECT 1 FROM pg_sleep(:seconds)")
    fun sleep(
        @Param("seconds") seconds: Int,
    ): Int
}

/** Gap 43: a Spring Data JDBC repository query is bounded by `rain.persistence.statement-timeout` like every other statement. */
@Tag("integration")
class DataJdbcRepositoryHasQueryTimeoutIT {
    @Test
    fun `a repository query that outlives the statement timeout is cancelled by the server`() {
        val database = RainPostgres.freshDatabase("datajdbc_timeout")
        val properties =
            database.springProperties() +
                listOf(
                    "spring.application.name=sample",
                    "rain.runtime.roles=api",
                    "rain.deployment.stage=test",
                    "rain.persistence.statement-timeout=1s",
                )

        SpringApplicationBuilder(DataJdbcApplication::class.java)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .properties(*properties.toTypedArray())
            .run()
            .use { context ->
                val failure = catchThrowable { context.getBean(SleepingRepository::class.java).sleep(5) }

                val states = generateSequence(failure, Throwable::cause).filterIsInstance<SQLException>().map { it.sqlState }.toList()
                assertThat(states).describedAs("the SQL states in %s", failure).contains(QUERY_CANCELED)
            }
    }

    private companion object {
        const val QUERY_CANCELED = "57014"
    }
}
