package com.gd.rain.realtime

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import java.sql.Connection

/** Where the listening connection comes from, and how a session gives it back. */
internal interface ListenerConnections : AutoCloseable {
    fun open(): Connection

    /** A session that ended because the listener is stopping returns its connection. */
    fun release(connection: Connection)

    /** A session that failed: its connection never serves another session, whatever state it is in. */
    fun evict(connection: Connection)
}

/**
 * The dedicated one-connection Hikari pool, beside the application's pool rather than in it.
 *
 * The listener holds its connection for the life of the process, so borrowing it from the
 * application's pool would take a connection out of that budget for good. It is not a `DataSource`
 * bean either: Boot's `DataSourceAutoConfiguration` backs off when one exists.
 *
 * The pool opens nothing until the listener asks. It is never trimmed or recycled under the listener
 * (`maxLifetime`, `idleTimeout` and `keepaliveTime` are 0), keeps no idle connection of its own
 * (`minimumIdle = 0`, so only the listener's backoff decides when a reconnect is attempted), and waits
 * at most `connect-timeout` for a connection.
 */
internal class HikariListenerConnections(
    internal val pool: HikariDataSource,
) : ListenerConnections {
    override fun open(): Connection = pool.connection

    override fun release(connection: Connection) {
        connection.close()
    }

    /**
     * Evicted while the proxy is still open: Hikari closes the physical connection at once only then,
     * and a connection closed first would already be back in the pool with its `LISTEN`s registered.
     */
    override fun evict(connection: Connection) {
        pool.evictConnection(connection)
    }

    override fun close() {
        pool.close()
    }

    companion object {
        /**
         * Only `url`, `username`, `password` and `driver-class-name` are taken from `spring.datasource`;
         * `spring.datasource.hikari.*` configures the application's pool, not this one.
         */
        fun of(
            properties: RealtimeProperties,
            source: DataSourceProperties,
        ): HikariListenerConnections {
            val url =
                source.url ?: throw ConfigurationProblemsException(
                    listOf(
                        ConfigurationProblem(
                            "spring.datasource.url",
                            ProblemCode.REQUIRED,
                            "no value is provided; the realtime listener opens its own connection from it",
                        ),
                    ),
                )
            val pool =
                HikariDataSource().apply {
                    poolName = properties.poolName
                    jdbcUrl = url
                    username = source.username
                    password = source.password
                    source.driverClassName?.let { driverClassName = it }
                    maximumPoolSize = 1
                    minimumIdle = 0
                    maxLifetime = 0
                    idleTimeout = 0
                    keepaliveTime = 0
                    isAutoCommit = true
                    connectionTimeout = properties.connectTimeout.toMillis()
                    initializationFailTimeout = -1
                }
            return HikariListenerConnections(pool)
        }
    }
}
