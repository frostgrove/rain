package com.gd.rain.access.internal.revocation

import com.gd.rain.observability.health.HealthCheck
import org.springframework.data.redis.connection.RedisConnectionFactory
import java.time.Duration

/** A Redis server rain-access depends on answers `PING` within the check's budget; its importance is the application's. */
public class RedisPingHealthCheck(
    override val name: String,
    override val code: String,
    private val factory: RedisConnectionFactory,
) : HealthCheck {
    override val timeout: Duration? = null

    override fun probe() {
        factory.connection.use { connection -> check(connection.ping() != null) { "the Redis server answered PING with nothing" } }
    }
}
