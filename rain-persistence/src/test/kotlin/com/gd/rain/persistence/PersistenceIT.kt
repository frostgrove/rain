package com.gd.rain.persistence

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.Sharing
import com.gd.rain.core.lock.keyOf
import com.gd.rain.persistence.fault.DataAccessFaultTranslator
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.persistence.lock.JooqAdvisoryLockStore
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.persistence.sql.SqlStates
import com.gd.rain.persistence.tx.TransactionRetry
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
class PersistenceIT {
    @Test
    fun `module schemas migrate before the application, each with its own history, and a second run applies nothing`() {
        val database = RainPostgres.freshDatabase("schemas")
        val strategy = RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
        val application =
            Flyway
                .configure()
                .dataSource(database.dataSource())
                .locations("classpath:db/migration")
                .load()

        strategy.migrate(application)
        val first = strategy.report!!
        strategy.migrate(application)
        val second = strategy.report!!

        assertThat(first.modules.map { it.schema to it.result.migrationsExecuted }).containsExactly("rain_alpha" to 1, "rain_beta" to 1)
        assertThat(first.application.migrationsExecuted).isEqualTo(1)
        assertThat(second.modules.map { it.result.migrationsExecuted } + second.application.migrationsExecuted).containsOnly(0)

        val jdbc = JdbcTemplate(database.dataSource())
        val histories =
            jdbc.queryForList(
                "SELECT table_schema FROM information_schema.tables WHERE table_name = 'flyway_schema_history' ORDER BY table_schema",
                String::class.java,
            )
        assertThat(histories).containsExactly("public", "rain_alpha", "rain_beta")
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Migrating {
        val out = ByteArrayOutputStream()

        @Bean
        fun commandOutput(): CommandOutput = CommandOutput(PrintStream(out), PrintStream(ByteArrayOutputStream()))
    }

    @Test
    fun `the migrate command enables Flyway for itself, reports every schema and exits zero`() {
        val database = RainPostgres.freshDatabase("migrate")
        val properties =
            database.springProperties() +
                listOf(
                    "spring.application.name=sample",
                    "rain.runtime.command=migrate",
                    "rain.deployment.stage=prod",
                    "rain.persistence.statement-timeout=30s",
                    "spring.flyway.enabled=false",
                )

        val context =
            SpringApplicationBuilder(
                Migrating::class.java,
            ).web(WebApplicationType.NONE).logStartupInfo(false).properties(*properties.toTypedArray()).run()

        assertThat(
            context
                .getBean(Migrating::class.java)
                .out
                .toString()
                .lines()
                .filter(String::isNotBlank),
        ).containsExactly(
            "migrate: rain_alpha applied 1, now at 1",
            "migrate: rain_beta applied 1, now at 1",
            "migrate: application applied 1, now at 1",
        )
        assertThat(SpringApplication.exit(context)).isZero()
    }

    @Test
    fun `an exclusive lock blocks another holder until it is released, and the waiter fails with a lock timeout`() {
        val database = RainPostgres.freshDatabase("locks")
        val locks = locks(database.dataSource(), timeout = Duration.ofMillis(200))
        val key = keyOf("ticket", "42")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newVirtualThreadPerTaskExecutor()

        val holder =
            pool.submit {
                locks.guarded(listOf(Exclusively(key))) {
                    held.countDown()
                    check(release.await(BOUND, TimeUnit.SECONDS)) { "the test never released the lock" }
                }
            }
        check(held.await(BOUND, TimeUnit.SECONDS)) { "the holder never took the lock" }

        assertThatThrownBy { locks.guarded(listOf(Exclusively(key))) { } }
            .matches({ SqlStates.of(it) == SqlStates.LOCK_NOT_AVAILABLE }, "carries SQLState 55P03")

        release.countDown()
        holder.get(BOUND, TimeUnit.SECONDS)
        assertThat(locks.guarded(listOf(Exclusively(key))) { "free" }).isEqualTo("free")
        pool.shutdown()
    }

    @Test
    fun `shared holders admit each other`() {
        val database = RainPostgres.freshDatabase("shared")
        val locks = locks(database.dataSource(), timeout = Duration.ofMillis(200))
        val key = keyOf("catalogue")
        val first = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newVirtualThreadPerTaskExecutor()

        val holder =
            pool.submit {
                locks.guarded(listOf(Sharing(key))) {
                    first.countDown()
                    check(release.await(BOUND, TimeUnit.SECONDS))
                }
            }
        check(first.await(BOUND, TimeUnit.SECONDS))

        assertThat(locks.guarded(listOf(Sharing(key))) { "shared" }).isEqualTo("shared")

        release.countDown()
        holder.get(BOUND, TimeUnit.SECONDS)
        pool.shutdown()
    }

    @Test
    fun `real database failures translate to their declared faults`() {
        val database = RainPostgres.freshDatabase("faults")
        val jdbc = JdbcTemplate(database.dataSource())
        jdbc.execute("CREATE TABLE parent (id int PRIMARY KEY)")
        jdbc.execute(
            "CREATE TABLE child (id int PRIMARY KEY, parent_id int NOT NULL REFERENCES parent(id), note text CHECK (length(note) < 5))",
        )
        jdbc.update("INSERT INTO parent VALUES (1)")
        jdbc.update("INSERT INTO child VALUES (1, 1, 'ok')")

        fun codeOf(block: () -> Unit): Pair<FaultKind, String> {
            val failure = runCatching(block).exceptionOrNull() ?: error("the statement did not fail")
            val fault = DataAccessFaultTranslator.translate(failure) ?: error("not translated: $failure")
            return fault.kind to fault.code.value
        }

        assertThat(codeOf { jdbc.update("INSERT INTO parent VALUES (1)") }).isEqualTo(FaultKind.CONFLICT to "unique")
        assertThat(codeOf { jdbc.update("INSERT INTO child VALUES (2, 99, 'ok')") }).isEqualTo(FaultKind.CONFLICT to "foreign_key")
        assertThat(codeOf { jdbc.update("INSERT INTO child (id, note) VALUES (3, 'ok')") }).isEqualTo(FaultKind.VALIDATION to "required")
        assertThat(codeOf { jdbc.update("INSERT INTO child VALUES (4, 1, 'too long')") }).isEqualTo(FaultKind.VALIDATION to "check")
        assertThat(codeOf { jdbc.queryForObject("SELECT 'x'::int", Int::class.java) }).isEqualTo(FaultKind.BAD_REQUEST to "invalid_format")

        val single = SingleConnectionDataSource(database.url, database.username, database.password, true)
        val pinned = JdbcTemplate(single)
        pinned.execute("SET statement_timeout = '50ms'")
        assertThat(codeOf { pinned.queryForList("SELECT pg_sleep(2)") }).isEqualTo(FaultKind.RETRYABLE to "statement_timeout")
        single.destroy()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Timed

    @Test
    fun `JdbcTemplate and jOOQ statements both carry the one statement timeout`() {
        val database = RainPostgres.freshDatabase("timeout")
        val properties =
            database.springProperties() +
                listOf(
                    "spring.application.name=sample",
                    "rain.runtime.roles=api",
                    "rain.deployment.stage=test",
                    "rain.persistence.statement-timeout=1s",
                    "spring.flyway.enabled=false",
                )

        SpringApplicationBuilder(
            Timed::class.java,
        ).web(WebApplicationType.NONE).logStartupInfo(false).properties(*properties.toTypedArray()).run().use { context ->
            val jdbc = context.getBean(JdbcTemplate::class.java)
            val dsl = context.getBean(DSLContext::class.java)

            assertThatThrownBy { jdbc.queryForList("SELECT pg_sleep(3)") }
                .matches({ SqlStates.of(it) == SqlStates.QUERY_CANCELED }, "carries SQLState 57014")
            assertThatThrownBy { dsl.fetch("SELECT pg_sleep(3)") }
                .matches({ SqlStates.of(it) == SqlStates.QUERY_CANCELED }, "carries SQLState 57014")
        }
    }

    private fun locks(
        dataSource: javax.sql.DataSource,
        timeout: Duration,
    ): AdvisoryLocks {
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        return AdvisoryLocks(
            DataSourceTransactionManager(dataSource),
            JooqAdvisoryLockStore(dsl),
            TransactionRetry(1, Duration.ofMillis(1), Duration.ofMillis(1)),
            timeout,
        )
    }

    private companion object {
        const val BOUND = 30L
    }
}
