package com.gd.rain.access.it

import com.gd.rain.access.EvictionPolicyAttestation
import com.gd.rain.access.internal.revocation.EvictionPolicyCheck
import com.gd.rain.access.support.RedisFactories
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.test.RainRedis
import com.gd.rain.test.RedisPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

private const val SERVER_PATH = "rain.access.revocation.redis"
private const val ATTESTATION_PATH = "rain.access.revocation.redis.eviction-policy-attested"

/** Gap 14: the server holding revocations keeps every key, or the start is refused; a server that will not say needs an attestation. */
@Tag("integration")
class RevocationServerPolicyIT {
    private fun problems(
        factory: LettuceConnectionFactory,
        attested: EvictionPolicyAttestation? = null,
    ): List<ConfigurationProblem> =
        try {
            EvictionPolicyCheck("the revocation list", SERVER_PATH, ATTESTATION_PATH, attested) { factory }.problems()
        } finally {
            factory.destroy()
        }

    @Test
    fun `a server configured noeviction passes`() {
        assertThat(problems(RedisFactories.of(RainRedis.shared(RedisPolicy.RETAINING)))).isEmpty()
    }

    @Test
    fun `a server that evicts under memory pressure is refused naming its policy, and an attestation does not overrule its answer`() {
        listOf(null, EvictionPolicyAttestation.NOEVICTION).forEach { attested ->
            val found = problems(RedisFactories.of(RainRedis.shared(RedisPolicy.EVICTING)), attested)

            assertThat(found).singleElement().matches(
                { it.path == SERVER_PATH && it.code == ProblemCode.INVALID && it.message.contains("allkeys-lru") },
                "refused naming allkeys-lru",
            )
        }
    }

    @Test
    fun `a server with CONFIG disabled is refused until the deployment attests noeviction, and then reported not evaluated`() {
        val unattested = problems(RedisFactories.of(RainRedis.shared(RedisPolicy.SILENT)))
        val attested = problems(RedisFactories.of(RainRedis.shared(RedisPolicy.SILENT)), EvictionPolicyAttestation.NOEVICTION)

        assertThat(unattested).singleElement().matches({ it.path == ATTESTATION_PATH && it.code == ProblemCode.REQUIRED }, "required")
        assertThat(
            attested,
        ).singleElement().matches({ it.path == ATTESTATION_PATH && it.code == ProblemCode.NOT_EVALUATED }, "not evaluated")
        assertThat(attested.single().code.fatal).isFalse()
    }

    @Test
    fun `no server at all is refused, attested or not`() {
        listOf(null, EvictionPolicyAttestation.NOEVICTION).forEach { attested ->
            assertThat(problems(RedisFactories.unreachable(), attested)).singleElement().matches(
                { it.path == SERVER_PATH && it.code == ProblemCode.INVALID && it.message.contains("not reachable") },
                "unreachable",
            )
        }
    }
}
