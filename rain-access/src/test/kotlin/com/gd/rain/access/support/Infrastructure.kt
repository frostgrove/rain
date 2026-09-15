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
import org.assertj.core.api.Assertions.assertThat
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
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

/** The three Redis servers the revocation tests need, one container each for the whole test JVM. */
object RedisServers {
    const val IMAGE: String = "redis:8-alpine"
    const val PORT: Int = 6379

    val retaining: GenericContainer<*> by lazy { start("--maxmemory-policy", "noeviction") }
    val evicting: GenericContainer<*> by lazy { start("--maxmemory", "64mb", "--maxmemory-policy", "allkeys-lru") }

    /** What a managed Redis looks like: it answers, and will not discuss its configuration. */
    val silent: GenericContainer<*> by lazy { start("--maxmemory-policy", "noeviction", "--rename-command", "CONFIG", "") }

    fun factory(container: GenericContainer<*>): LettuceConnectionFactory = factory(container.host, container.getMappedPort(PORT))

    fun factory(
        host: String,
        port: Int,
    ): LettuceConnectionFactory =
        LettuceConnectionFactory(RedisStandaloneConfiguration(host, port)).apply {
            afterPropertiesSet()
            start()
        }

    /** A factory whose server does not exist. */
    fun unreachable(): LettuceConnectionFactory = factory("127.0.0.1", 1)

    private fun start(vararg arguments: String): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .withCommand("redis-server", *arguments)
            .also { it.start() }
}

/**
 * The plan PostgreSQL chooses once the shortcuts an almost empty test table invites are priced out: no sequential scan, no
 * bitmap scan and no sort wherever any other path exists. PostgreSQL marks a node it could not avoid as disabled, so a plan
 * with no disabled node reaches its rows the way it would in a table of any size.
 */
object ScalePlans {
    fun explain(
        dataSource: DataSource,
        sql: String,
    ): QueryPlan =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf("enable_seqscan", "enable_bitmapscan", "enable_sort").forEach { statement.execute("SET $it = off") }
                statement.executeQuery("EXPLAIN (FORMAT JSON) $sql").use { rows ->
                    check(rows.next()) { "EXPLAIN returned no plan" }
                    QueryPlan(rows.getString(1))
                }
            }
        }
}

/** The shape of a query plan, read from its JSON: which index scans carry a filter, and whether a sort sits under the limit. */
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

    /**
     * A keyset page proven bounded: [index] is used under a `Limit`, nothing is scanned sequentially, no `Sort` sits beneath
     * the `Limit`, no node is one the planner could only use disabled, and the scans of [index] carry no `Filter` — every
     * condition is an index condition, and a [conditioned] page (anything but an unconditioned first page) has one.
     */
    fun assertKeysetPage(
        plan: QueryPlan,
        index: String,
        conditioned: Boolean = true,
    ) {
        val nodes = nodes(plan)
        assertThat(plan.usesIndex(index)).describedAs("uses $index:\n${plan.json}").isTrue()
        assertThat(plan.hasLimit()).describedAs("has a Limit:\n${plan.json}").isTrue()
        assertThat(nodes.none { it.get("Node Type")?.asString() == "Seq Scan" }).describedAs("no sequential scan:\n${plan.json}").isTrue()
        assertThat(nodes.none { it.get("Node Type")?.asString() == "Sort" }).describedAs("no sort:\n${plan.json}").isTrue()
        val scans = nodes.filter { it.get("Index Name")?.asString() == index }
        assertThat(scans).describedAs("scans of $index").isNotEmpty()
        assertThat(nodes.none { it.get("Disabled")?.asBoolean() == true }).describedAs("no disabled node:\n${plan.json}").isTrue()
        scans.forEach { scan ->
            assertThat(scan.has("Filter")).describedAs("a filter on $index:\n${plan.json}").isFalse()
            if (conditioned) assertThat(scan.has("Index Cond")).describedAs("index conditions on $index:\n${plan.json}").isTrue()
        }
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
