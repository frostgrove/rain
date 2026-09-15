package com.gd.rain.access.revocation

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.UnavailableAttemptLimiter
import com.gd.rain.access.internal.UnavailableRevocationList
import com.gd.rain.access.internal.attempt.Attempt
import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.MemoryAttemptLimiter
import com.gd.rain.access.internal.revocation.EvictionReading
import com.gd.rain.access.internal.revocation.NoRevocationList
import com.gd.rain.access.internal.revocation.RedisEvictionPolicy
import com.gd.rain.access.internal.revocation.RedisPingHealthCheck
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.START
import com.gd.rain.access.support.accessWebRunner
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.connection.RedisClusterConnection
import org.springframework.data.redis.connection.RedisClusterNode
import org.springframework.data.redis.connection.RedisClusterServerCommands
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisNode
import java.util.Properties
import java.util.UUID

/** In a cluster every master is asked, because the key a session lands on lives on one of them; the worst answer wins. */
class ClusterEvictionPolicyTest {
    private val factory = mockk<RedisConnectionFactory>()
    private val connection = mockk<RedisClusterConnection>(relaxed = true)
    private val server = mockk<RedisClusterServerCommands>()

    init {
        every { factory.connection } returns connection
        every { connection.serverCommands() } returns server
    }

    private fun node(
        port: Int,
        type: RedisNode.NodeType,
        policy: String?,
    ): RedisClusterNode =
        RedisClusterNode.newRedisClusterNode().listeningAt("10.0.0.1", port).promotedAs(type).build().also { node ->
            every { server.getConfig(node, RedisEvictionPolicy.PARAMETER) } returns
                Properties().apply { policy?.let { setProperty(RedisEvictionPolicy.PARAMETER, it) } }
        }

    @Test
    fun `a cluster whose every master keeps its keys is retaining, and its replicas are not asked`() {
        val replica = node(7002, RedisNode.NodeType.REPLICA, "allkeys-lru")
        every { connection.clusterGetNodes() } returns
            listOf(node(7000, RedisNode.NodeType.MASTER, "noeviction"), replica, node(7001, RedisNode.NodeType.MASTER, "noeviction"))

        assertThat(RedisEvictionPolicy.read(factory)).isEqualTo(EvictionReading.Retaining)
        verify(exactly = 0) { server.getConfig(replica, any()) }
        verify { connection.close() }
    }

    @Test
    fun `one evicting master makes the cluster evicting, even beside a master that will not say`() {
        every { connection.clusterGetNodes() } returns
            listOf(
                node(7000, RedisNode.NodeType.MASTER, "noeviction"),
                node(7001, RedisNode.NodeType.MASTER, null),
                node(7002, RedisNode.NodeType.MASTER, "volatile-lru"),
            )

        assertThat(RedisEvictionPolicy.read(factory)).isEqualTo(EvictionReading.Evicting("volatile-lru"))
    }

    @Test
    fun `a master that will not say leaves an otherwise retaining cluster unanswered`() {
        every { connection.clusterGetNodes() } returns
            listOf(node(7000, RedisNode.NodeType.MASTER, "noeviction"), node(7001, RedisNode.NodeType.MASTER, ""))
        every { connection.ping() } returns "PONG"

        assertThat(RedisEvictionPolicy.read(factory)).isInstanceOf(EvictionReading.Unanswered::class.java)
    }

    @Test
    fun `a cluster that names no master is unanswered, and one that answers nothing at all is unreachable`() {
        every { connection.clusterGetNodes() } returns listOf(node(7000, RedisNode.NodeType.REPLICA, "noeviction"))
        every { connection.ping() } returns "PONG"
        assertThat(RedisEvictionPolicy.read(factory)).isEqualTo(EvictionReading.Unanswered("the cluster named no master to ask"))

        every { connection.clusterGetNodes() } throws
            RedisConnectionFailureException("cluster down", IllegalStateException("reset by peer"))
        every { connection.ping() } throws RedisConnectionFailureException("cluster down", IllegalStateException("reset by peer"))
        assertThat(RedisEvictionPolicy.read(factory)).isEqualTo(EvictionReading.Unreachable("IllegalStateException: reset by peer"))
    }
}

/** The health of a Redis server rain-access depends on is its answer to PING, over a connection that is always given back. */
class RedisPingHealthCheckTest {
    private val factory = mockk<RedisConnectionFactory>()
    private val connection = mockk<RedisConnection>(relaxed = true)
    private val check = RedisPingHealthCheck("access.revocation", "access.revocation", factory)

    init {
        every { factory.connection } returns connection
    }

    @Test
    fun `a server that answers PING passes, one that answers nothing fails, and the connection is closed either way`() {
        every { connection.ping() } returns "PONG"
        check.probe()

        every { connection.ping() } returns null
        assertThatThrownBy { check.probe() }
            .isInstanceOf(
                IllegalStateException::class.java,
            ).hasMessageContaining("answered PING with nothing")

        verify(exactly = 2) { connection.close() }
        assertThat(listOf(check.name, check.code)).containsOnly("access.revocation")
        assertThat(check.timeout).describedAs("the registry's own budget applies").isNull()
    }
}

/** A store stated as redis needs a Redis client; without one the start is refused, and nothing stands in that could answer. */
class RedisClientAbsentRefusesRedisStoresTest {
    private val withoutRedis = FilteredClassLoader(RedisConnectionFactory::class.java)

    @Test
    fun `stores stated as redis with no Redis client on the classpath refuse the start, naming each store`() {
        accessWebRunner()
            .withClassLoader(withoutRedis)
            .withPropertyValues(
                "rain.access.revocation.store=redis",
                "rain.access.revocation.redis.key-prefix=app:revoked:",
                "rain.access.revocation.redis.replay.interval=1m",
                "rain.access.attempts.store=redis",
                "rain.access.attempts.redis.key-prefix=app:attempts:",
            ).run { context ->
                assertThat(context).hasFailed()
                val failures = generateSequence(context.startupFailure, Throwable::cause).toList()
                val refusal =
                    requireNotNull(failures.filterIsInstance<ConfigurationProblemsException>().firstOrNull()) {
                        "the start was not refused by configuration validation: ${failures.joinToString(
                            " <- ",
                        ) { "${it.javaClass.name}: ${it.message}" }}"
                    }
                assertThat(refusal.problems.map { it.path to it.code }).contains(
                    "rain.access.revocation.store" to ProblemCode.CONTRADICTS,
                    "rain.access.attempts.store" to ProblemCode.CONTRADICTS,
                )
                assertThat(refusal.problems.single { it.path == "rain.access.revocation.store" }.message).contains("spring-boot-data-redis")
            }
    }

    @Test
    fun `one store stated as redis with no Redis client refuses the start naming that store, and the other store is still wired`() {
        accessWebRunner()
            .withClassLoader(withoutRedis)
            .withPropertyValues(
                "rain.access.revocation.store=redis",
                "rain.access.revocation.redis.key-prefix=app:revoked:",
                "rain.access.revocation.redis.replay.interval=1m",
            ).run { context ->
                val failures = generateSequence(context.startupFailure, Throwable::cause).toList()
                val refusal =
                    requireNotNull(failures.filterIsInstance<ConfigurationProblemsException>().firstOrNull()) {
                        "the start was not refused by configuration validation: ${failures.joinToString(
                            " <- ",
                        ) { "${it.javaClass.name}: ${it.message}" }}"
                    }
                assertThat(
                    refusal.problems.map { it.path },
                ).contains("rain.access.revocation.store").doesNotContain("rain.access.attempts.store")
            }
    }

    @Test
    fun `with no Redis client and no store on Redis the start goes ahead on the stores it was given`() {
        accessWebRunner().withClassLoader(withoutRedis).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(RevocationList::class.java)).isSameAs(NoRevocationList)
            assertThat(context.getBean(AttemptLimiter::class.java)).isInstanceOf(MemoryAttemptLimiter::class.java)
        }
    }
}

/** The stand-ins for Redis stores with no client refuse every call, and neither stands in for the other's store. */
class UnavailableRedisStandInsNeverAnswerTest {
    @Test
    fun `every call to a stand-in refuses rather than answering, and each stands in for its own store only`() {
        assertThat(UnavailableRevocationList).isNotInstanceOf(AttemptLimiter::class.java)
        assertThat(UnavailableAttemptLimiter).isNotInstanceOf(RevocationList::class.java)

        val subject = SubjectRef(AGENT, UUID.randomUUID())
        val attempt = Attempt(AGENT, "ada@example.test", "203.0.113.7")

        listOf<() -> Any>(
            { UnavailableRevocationList.verdict(UUID.randomUUID(), subject, START) },
            { UnavailableRevocationList.announceSessions(listOf(RevokedSession(UUID.randomUUID(), START))) },
            { UnavailableRevocationList.announceCutoff(SubjectCutoff(subject, START, null)) },
            { UnavailableAttemptLimiter.admit(attempt) },
            { UnavailableAttemptLimiter.recordFailure(attempt) },
            { UnavailableAttemptLimiter.recordSuccess(attempt) },
        ).forEach { call ->
            assertThatThrownBy { call() }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("no Redis client")
        }
    }
}
