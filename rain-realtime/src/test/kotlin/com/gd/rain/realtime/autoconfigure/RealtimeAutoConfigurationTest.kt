package com.gd.rain.realtime.autoconfigure

import com.gd.rain.boot.autoconfigure.RainRuntimeAutoConfiguration
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.realtime.HikariListenerConnections
import com.gd.rain.realtime.ListenerStartRefused
import com.gd.rain.realtime.RealtimeErrorCodes
import com.gd.rain.realtime.RealtimeListener
import com.gd.rain.realtime.RealtimeListenerHealthCheck
import com.gd.rain.realtime.RealtimeProperties
import com.gd.rain.realtime.RealtimePublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import javax.sql.DataSource

/** Wiring only: the data source points at a port nothing listens on. */
class RealtimeAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    JooqAutoConfiguration::class.java,
                    RainRuntimeAutoConfiguration::class.java,
                    RainRealtimeAutoConfiguration::class.java,
                ),
            ).withBean(DataSource::class.java, { PGSimpleDataSource().apply { setURL(NOWHERE) } })
            .withPropertyValues("rain.deployment.stage=test")

    private val section =
        arrayOf(
            "rain.realtime.pool-name=sample-realtime",
            "rain.realtime.subscriber-buffer=8",
            "rain.realtime.max-subscriptions=32",
            "rain.realtime.connect-timeout=250ms",
        )

    @Test
    fun `a worker builds the publisher and no listener`() {
        runner.withPropertyValues("rain.runtime.roles=worker", "spring.datasource.url=$NOWHERE", *section).run { context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(RealtimePublisher::class.java)
                .doesNotHaveBean(RealtimeListener::class.java)
                .doesNotHaveBean(RealtimeListenerHealthCheck::class.java)
            assertThat(context.getBeansOfType(ErrorCodeCatalog::class.java).values).contains(RealtimeErrorCodes)
        }
    }

    @Test
    fun `without the section there is neither a publisher nor a listener, whatever the roles`() {
        runner.withPropertyValues("rain.runtime.roles=api,worker", "spring.datasource.url=$NOWHERE").run { context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(RealtimePublisher::class.java)
                .doesNotHaveBean(RealtimeListener::class.java)
        }
    }

    @Test
    fun `an api process starts the listener, and refuses to start when its first session cannot connect`() {
        runner.withPropertyValues("rain.runtime.roles=api", "spring.datasource.url=$NOWHERE", *section).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).matches(
                { failure -> generateSequence(failure, Throwable::cause).any { it is ListenerStartRefused } },
                "the start was refused by the listener",
            )
        }
    }

    @Test
    fun `the listener needs spring datasource url and says so`() {
        runner.withPropertyValues("rain.runtime.roles=api", *section).run { context ->
            assertThat(context.startupFailure).matches(
                { failure ->
                    generateSequence(failure, Throwable::cause).any { cause ->
                        cause is ConfigurationProblemsException && cause.problems.single().path == "spring.datasource.url"
                    }
                },
                "a configuration problem naming spring.datasource.url",
            )
        }
    }

    @Test
    fun `an application's own publisher wins`() {
        runner
            .withPropertyValues("rain.runtime.roles=worker", *section)
            .withBean(
                "ownPublisher",
                RealtimePublisher::class.java,
                {
                    RealtimePublisher(
                        org.jooq.impl.DSL
                            .using(org.jooq.SQLDialect.POSTGRES),
                    )
                },
            ).run { context ->
                assertThat(context).hasSingleBean(RealtimePublisher::class.java).hasBean("ownPublisher")
            }
    }

    @Test
    fun `the dedicated pool takes the datasource coordinates, the configured name and exactly one connection`() {
        val properties =
            RealtimeProperties(
                poolName = "sample-realtime",
                subscriberBuffer = 8,
                maxSubscriptions = 32,
                connectTimeout = Duration.ofMillis(750),
            )
        val source =
            DataSourceProperties().apply {
                url = NOWHERE
                username = "app"
                password = "secret"
            }

        HikariListenerConnections.of(properties, source).use { connections ->
            val pool = connections.pool
            assertThat(pool.poolName).isEqualTo("sample-realtime")
            assertThat(pool.jdbcUrl).isEqualTo(NOWHERE)
            assertThat(pool.username).isEqualTo("app")
            assertThat(pool.maximumPoolSize).isEqualTo(1)
            assertThat(pool.minimumIdle).isZero()
            assertThat(pool.maxLifetime).isZero()
            assertThat(pool.idleTimeout).isZero()
            assertThat(pool.connectionTimeout).isEqualTo(750)
        }
    }

    private companion object {
        const val NOWHERE = "jdbc:postgresql://127.0.0.1:1/none"
    }
}
