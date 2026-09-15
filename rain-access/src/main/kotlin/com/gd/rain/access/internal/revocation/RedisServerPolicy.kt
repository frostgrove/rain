package com.gd.rain.access.internal.revocation

import com.gd.rain.access.EvictionPolicyAttestation
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.data.redis.connection.RedisClusterConnection
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory

/** What a Redis deployment answered about what it evicts. */
public sealed interface EvictionReading {
    /** Every node asked answered `noeviction`. */
    public data object Retaining : EvictionReading

    /** At least one node evicts keys under memory pressure. */
    public data class Evicting(
        public val policy: String,
    ) : EvictionReading

    /** The server answers, and will not say (a managed Redis usually forbids `CONFIG`). */
    public data class Unanswered(
        public val reason: String,
    ) : EvictionReading

    /** No server answered at all. */
    public data class Unreachable(
        public val reason: String,
    ) : EvictionReading
}

/**
 * `CONFIG GET maxmemory-policy`, asked of the server — of every master in a cluster, where the worst answer wins,
 * because the key a session lands on lives on one node. A question that failed is asked a second way, with `PING`, so a
 * server that forbids `CONFIG` is told apart from no server at all.
 */
public object RedisEvictionPolicy {
    public const val PARAMETER: String = "maxmemory-policy"
    public const val RETAINING: String = "noeviction"

    public fun read(factory: RedisConnectionFactory): EvictionReading {
        val connection =
            try {
                factory.connection
            } catch (failed: RuntimeException) {
                return EvictionReading.Unreachable(describe(failed))
            }
        return connection.use { opened ->
            val answer =
                try {
                    if (opened is RedisClusterConnection) cluster(opened) else single(opened)
                } catch (failed: RuntimeException) {
                    EvictionReading.Unanswered(describe(failed))
                }
            if (answer !is EvictionReading.Unanswered) return@use answer
            try {
                opened.ping()
                answer
            } catch (failed: RuntimeException) {
                EvictionReading.Unreachable(describe(failed))
            }
        }
    }

    private fun single(connection: RedisConnection): EvictionReading =
        verdict(connection.serverCommands().getConfig(PARAMETER)?.getProperty(PARAMETER))

    private fun cluster(connection: RedisClusterConnection): EvictionReading {
        val masters = connection.clusterGetNodes().filter { it.isMaster }
        if (masters.isEmpty()) return EvictionReading.Unanswered("the cluster named no master to ask")
        val readings = masters.map { node -> verdict(connection.serverCommands().getConfig(node, PARAMETER)?.getProperty(PARAMETER)) }
        return readings.firstOrNull { it is EvictionReading.Evicting }
            ?: readings.firstOrNull { it is EvictionReading.Unanswered }
            ?: EvictionReading.Retaining
    }

    private fun verdict(policy: String?): EvictionReading =
        when {
            policy.isNullOrEmpty() -> EvictionReading.Unanswered("the server answered no $PARAMETER")
            policy == RETAINING -> EvictionReading.Retaining
            else -> EvictionReading.Evicting(policy)
        }

    private fun describe(failure: Throwable): String =
        generateSequence(failure, Throwable::cause).last().let { root -> "${root.javaClass.simpleName}: ${root.message.orEmpty()}" }
}

/**
 * The Redis server holding [purpose] keeps what it is told: an evicted revocation reads back as a session nobody closed,
 * and an evicted attempt counter is a free reset. A server that will not say is `not_evaluated` only when the deployment
 * attested `noeviction` at [attestationPath]; otherwise the start is refused.
 */
public class EvictionPolicyCheck(
    private val purpose: String,
    private val serverPath: String,
    private val attestationPath: String,
    private val attested: EvictionPolicyAttestation?,
    private val factory: () -> RedisConnectionFactory,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        when (val reading = RedisEvictionPolicy.read(factory())) {
            EvictionReading.Retaining -> {
                emptyList()
            }

            is EvictionReading.Evicting -> {
                listOf(
                    ConfigurationProblem(
                        serverPath,
                        ProblemCode.INVALID,
                        "the Redis server holding $purpose evicts keys (${RedisEvictionPolicy.PARAMETER} is ${reading.policy}); an " +
                            "evicted key silently undoes what it recorded; configure ${RedisEvictionPolicy.RETAINING}",
                    ),
                )
            }

            is EvictionReading.Unanswered -> {
                if (attested == EvictionPolicyAttestation.NOEVICTION) {
                    listOf(
                        ConfigurationProblem(
                            attestationPath,
                            ProblemCode.NOT_EVALUATED,
                            "the Redis server holding $purpose would not say what it evicts (${reading.reason}); the deployment " +
                                "attested ${RedisEvictionPolicy.RETAINING}",
                        ),
                    )
                } else {
                    listOf(
                        ConfigurationProblem(
                            attestationPath,
                            ProblemCode.REQUIRED,
                            "the Redis server holding $purpose would not say what it evicts (${reading.reason}); verify " +
                                "${RedisEvictionPolicy.PARAMETER} is ${RedisEvictionPolicy.RETAINING} out of band and state " +
                                RedisEvictionPolicy.RETAINING,
                        ),
                    )
                }
            }

            is EvictionReading.Unreachable -> {
                listOf(
                    ConfigurationProblem(
                        serverPath,
                        ProblemCode.INVALID,
                        "the Redis server holding $purpose is not reachable (${reading.reason})",
                    ),
                )
            }
        }
}
