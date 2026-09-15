package com.gd.rain.test

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.ConcurrentHashMap

/** What a Redis server a test starts does with its keys under memory pressure, and whether it says so. */
public enum class RedisPolicy(
    arguments: List<String>,
) {
    /** `maxmemory-policy noeviction`: every key is kept for as long as it was written for. */
    RETAINING(listOf("--maxmemory-policy", "noeviction")),

    /** `maxmemory 64mb` and `maxmemory-policy allkeys-lru`: any key may be evicted once the memory is used. */
    EVICTING(listOf("--maxmemory", "64mb", "--maxmemory-policy", "allkeys-lru")),

    /**
     * `maxmemory-policy noeviction` with `CONFIG` renamed away: what a managed Redis looks like — it answers every command
     * a store issues, and refuses to say how it is configured.
     */
    SILENT(listOf("--maxmemory-policy", "noeviction", "--rename-command", "CONFIG", "")),
    ;

    /** The arguments `redis-server` is started with. */
    public val arguments: List<String> = arguments.toList()
}

/** One Redis server in a container: where it listens, and the Spring Boot properties that point an application at it. */
public class RedisServer internal constructor(
    private val container: GenericContainer<*>,
    public val policy: RedisPolicy,
    /** Whether the server is one of the JVM's shared ones ([RainRedis.shared]), which only the JVM's end stops. */
    public val shared: Boolean,
) : AutoCloseable {
    public val host: String get() = container.host

    public val port: Int get() = container.getMappedPort(RainRedis.PORT)

    /** `spring.data.redis.host` and `spring.data.redis.port` for an application context. */
    public fun springProperties(): List<String> = listOf("spring.data.redis.host=$host", "spring.data.redis.port=$port")

    /** Stops a server a test started for itself; a shared server is refused, because other tests of the JVM use it. */
    override fun close() {
        check(!shared) { "the shared ${policy.name.lowercase()} Redis server lives as long as the test JVM" }
        container.stop()
    }
}

/**
 * Redis servers for tests, in containers of [IMAGE].
 *
 * [shared] answers one server per [RedisPolicy] for the whole test JVM, started on first use; a test that writes keys
 * to it names them under a prefix of its own. [start] starts a server of the test's own, for a test that stops the
 * server or needs one nobody else writes to.
 */
public object RainRedis {
    public const val IMAGE: String = "redis:8-alpine"
    public const val PORT: Int = 6379

    private val servers = ConcurrentHashMap<RedisPolicy, RedisServer>()

    /** The JVM's one server of [policy]. */
    public fun shared(policy: RedisPolicy): RedisServer = servers.computeIfAbsent(policy) { RedisServer(container(it), it, shared = true) }

    /** A server of the caller's own, started now; the caller closes it. */
    public fun start(policy: RedisPolicy): RedisServer = RedisServer(container(policy), policy, shared = false)

    private fun container(policy: RedisPolicy): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(IMAGE))
            .withExposedPorts(PORT)
            .withCommand("redis-server", *policy.arguments.toTypedArray())
            .also { it.start() }
}
