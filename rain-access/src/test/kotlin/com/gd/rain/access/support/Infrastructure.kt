package com.gd.rain.access.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.store.JooqCatalogueStore
import com.gd.rain.access.internal.store.JooqCredentialStore
import com.gd.rain.access.internal.store.JooqGrantStore
import com.gd.rain.access.internal.store.JooqSessionStore
import com.gd.rain.audit.JooqAuditRecorder
import com.gd.rain.core.actor.CurrentActor
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.MutableClock
import com.gd.rain.test.QueryPlan
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import com.gd.rain.test.RedisServer
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.reflect.KClass

/** A fresh database with every rain schema migrated, and the access stores over it. */
class AccessDatabase private constructor(
    val database: RainDatabase,
    dataSource: DataSource,
) {
    val dataSource: DataSource = dataSource
    val transactions = DataSourceTransactionManager(dataSource)
    val dsl: DSLContext = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
    val jdbc = JdbcTemplate(dataSource)
    val clock = MutableClock(START)
    val ids: IdGenerator = IdGenerator(UuidV7Ids::next)
    val credentials = JooqCredentialStore(dsl)
    val sessions = JooqSessionStore(dsl)
    val grants = JooqGrantStore(dsl)
    val catalogue = JooqCatalogueStore(dsl)

    fun auditRecorder(actor: CurrentActor? = null): JooqAuditRecorder =
        JooqAuditRecorder(dsl, transactions, ids, clock, actor, AccessAuditTypes.ALL)

    fun <T> inTransaction(block: () -> T): T {
        val held = ArrayList<T>(1)
        TransactionTemplate(transactions).executeWithoutResult { held += block() }
        return held.single()
    }

    fun count(sql: String): Long = requireNotNull(jdbc.queryForObject(sql, Long::class.java))

    companion object {
        fun fresh(
            prefix: String,
            dataSource: (RainDatabase) -> DataSource = RainDatabase::dataSource,
        ): AccessDatabase {
            val database = RainPostgres.freshDatabase(prefix)
            val fixture = AccessDatabase(database, dataSource(database))
            RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
                .migrate(
                    Flyway
                        .configure()
                        .dataSource(database.dataSource())
                        .locations("classpath:db/none")
                        .failOnMissingLocations(false)
                        .load(),
                )
            return fixture
        }
    }
}

/** Connection factories to rain-test's Redis servers, and to a server that does not exist. */
object RedisFactories {
    fun of(server: RedisServer): LettuceConnectionFactory = of(server.host, server.port)

    fun of(
        host: String,
        port: Int,
    ): LettuceConnectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration(host, port)).apply {
            afterPropertiesSet()
            start()
        }

    /** A factory whose server does not exist. */
    fun unreachable(): LettuceConnectionFactory = of("127.0.0.1", 1)
}

/**
 * Re-evaluates [condition] until it holds. Each pause between two evaluations is a bounded wait on a latch nobody counts
 * down, and the wait fails once [bound] has passed.
 */
fun awaitUntil(
    what: String,
    bound: Duration = Duration.ofSeconds(60),
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + bound.toNanos()
    val pause = CountDownLatch(1)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        pause.await(20, TimeUnit.MILLISECONDS)
    }
}

/** The nodes of a query plan, read from its JSON, for an assertion plan criterion v3 does not make. */
object PlanShape {
    private val json: JsonMapper = JsonMapper.builder().build()

    fun nodes(plan: QueryPlan): List<JsonNode> {
        val found = mutableListOf<JsonNode>()

        fun walk(node: JsonNode) {
            found.add(node)
            node.get("Plans")?.forEach(::walk)
        }
        json.readTree(plan.json).forEach { walk(it.get("Plan")) }
        return found
    }
}

/** The log of one class as a list. */
class Transcript private constructor(
    private val logger: Logger,
    private val appender: ListAppender<ILoggingEvent>,
    private val level: Level?,
    private val additive: Boolean,
) : AutoCloseable {
    val lines: List<ILoggingEvent> get() = appender.list.toList()

    fun attributes(event: ILoggingEvent): Map<String, String?> = event.keyValuePairs.orEmpty().associate { it.key to it.value?.toString() }

    fun written(): String = lines.joinToString("\n") { "${it.level} ${it.formattedMessage} ${attributes(it)}" }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = level
        logger.isAdditive = additive
    }

    companion object {
        fun of(type: KClass<*>): Transcript {
            val logger = LoggerFactory.getLogger(type.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val transcript = Transcript(logger, appender, logger.level, logger.isAdditive)
            logger.isAdditive = false
            logger.level = Level.TRACE
            logger.addAppender(appender)
            return transcript
        }
    }
}
