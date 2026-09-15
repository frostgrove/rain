package com.gd.rain.test

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * One PostgreSQL server per test JVM, and a database of its own for every test that asks.
 *
 * A fresh database per test keeps tests independent without restarting the server; names are
 * sequential within the JVM, so a failing run names the database it left behind.
 */
public object RainPostgres {
    public const val IMAGE: String = "postgres:18"

    private val sequence = AtomicInteger()

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(IMAGE)).apply { start() }
    }

    public fun freshDatabase(prefix: String): RainDatabase {
        require(Regex("^[a-z][a-z0-9_]{0,40}$").matches(prefix)) { "database prefix \"$prefix\" is lower-case snake case" }
        val name = "${prefix}_${sequence.incrementAndGet()}"
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE \"$name\"") }
        }
        val url = "jdbc:postgresql://${container.host}:${container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/$name"
        return RainDatabase(url, container.username, container.password)
    }
}

public data class RainDatabase(
    public val url: String,
    public val username: String,
    public val password: String,
) {
    public fun dataSource(): DataSource =
        PGSimpleDataSource().also {
            it.setURL(url)
            it.user = username
            it.password = password
        }

    /** The Spring Boot properties that point an application at this database. */
    public fun springProperties(): List<String> =
        listOf("spring.datasource.url=$url", "spring.datasource.username=$username", "spring.datasource.password=$password")
}
