package com.gd.rain.access.revocation

import com.gd.rain.access.autoconfigure.AccessAttemptsRedisConnection
import com.gd.rain.access.autoconfigure.AccessRedisConnection
import com.gd.rain.access.autoconfigure.RainAccessAutoConfiguration
import com.gd.rain.access.support.accessWebRunner
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.AutowireCandidateQualifier
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

private const val REVOCATION_PATH = "rain.access.revocation.redis"
private const val ATTEMPTS_PATH = "rain.access.attempts.redis"

/** A factory that is never connected: the choice is made from the beans alone. */
private fun factory(): LettuceConnectionFactory = LettuceConnectionFactory("127.0.0.1", 1)

private fun qualified(qualifier: String): BeanDefinitionCustomizer =
    BeanDefinitionCustomizer { (it as AbstractBeanDefinition).addQualifier(AutowireCandidateQualifier(Qualifier::class.java, qualifier)) }

private fun AssertableWebApplicationContext.refusal(): List<ConfigurationProblem> =
    requireNotNull(generateSequence(startupFailure, Throwable::cause).filterIsInstance<ConfigurationProblemsException>().firstOrNull()) {
        "the start was not refused by a configuration problem: $startupFailure"
    }.problems

/**
 * Two `RedisConnectionFactory` beans with neither qualified for a store used to fail the start with Spring's
 * `NoUniqueBeanDefinitionException` from the store's connection bean. Which factory a store uses is now decided by the
 * qualifiers and the primary factory, and anything else refuses the start with a problem per store naming the qualifiers.
 */
class RedisConnectionChoiceTest {
    /** Both stores on Redis, in a seeder: no role that asks a server anything at start. */
    private fun runner() =
        accessWebRunner().withPropertyValues(
            "rain.runtime.roles=seeder",
            "rain.access.revocation.store=redis",
            "rain.access.revocation.redis.key-prefix=it:revoked:",
            "rain.access.revocation.redis.replay.interval=1m",
            "rain.access.attempts.store=redis",
            "rain.access.attempts.redis.key-prefix=it:attempts:",
        )

    @Test
    fun `two factories neither qualified nor primary refuse the start for both stores, naming both qualifiers`() {
        runner()
            .withBean("first", LettuceConnectionFactory::class.java, ::factory)
            .withBean("second", LettuceConnectionFactory::class.java, ::factory)
            .run { context ->
                val problems = context.refusal()

                assertThat(problems.map { it.path to it.code })
                    .containsExactlyInAnyOrder(REVOCATION_PATH to ProblemCode.CONTRADICTS, ATTEMPTS_PATH to ProblemCode.CONTRADICTS)
                assertThat(problems).allMatch(
                    {
                        it.message.contains(RainAccessAutoConfiguration.REVOCATION_QUALIFIER) &&
                            it.message.contains(RainAccessAutoConfiguration.ATTEMPTS_QUALIFIER) &&
                            it.message.contains("first, second")
                    },
                    "names the qualifiers and the factories",
                )
            }
    }

    @Test
    fun `a factory named for each store serves that store`() {
        runner()
            .withBean(RainAccessAutoConfiguration.REVOCATION_QUALIFIER, LettuceConnectionFactory::class.java, ::factory)
            .withBean(RainAccessAutoConfiguration.ATTEMPTS_QUALIFIER, LettuceConnectionFactory::class.java, ::factory)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(AccessRedisConnection::class.java).factory())
                    .isSameAs(context.getBean(RainAccessAutoConfiguration.REVOCATION_QUALIFIER))
                assertThat(context.getBean(AccessAttemptsRedisConnection::class.java).factory())
                    .isSameAs(context.getBean(RainAccessAutoConfiguration.ATTEMPTS_QUALIFIER))
            }
    }

    @Test
    fun `without a qualified factory a store uses the application's primary one, and a qualified one takes precedence`() {
        runner()
            .withBean("application", LettuceConnectionFactory::class.java, ::factory, BeanDefinitionCustomizer { it.isPrimary = true })
            .withBean(
                "reporting",
                LettuceConnectionFactory::class.java,
                ::factory,
                qualified(RainAccessAutoConfiguration.REVOCATION_QUALIFIER),
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(AccessRedisConnection::class.java).factory()).isSameAs(context.getBean("reporting"))
                assertThat(context.getBean(AccessAttemptsRedisConnection::class.java).factory()).isSameAs(context.getBean("application"))
            }
    }

    @Test
    fun `two factories qualified for one store refuse it, and no factory at all is required`() {
        runner()
            .withBean("one", LettuceConnectionFactory::class.java, ::factory, qualified(RainAccessAutoConfiguration.REVOCATION_QUALIFIER))
            .withBean("two", LettuceConnectionFactory::class.java, ::factory, qualified(RainAccessAutoConfiguration.REVOCATION_QUALIFIER))
            .withBean(RainAccessAutoConfiguration.ATTEMPTS_QUALIFIER, LettuceConnectionFactory::class.java, ::factory)
            .run { context ->
                assertThat(context.refusal()).singleElement().matches(
                    {
                        it.path == REVOCATION_PATH && it.code == ProblemCode.CONTRADICTS &&
                            it.message.startsWith("2 RedisConnectionFactory beans")
                    },
                    "two qualified rainRevocation",
                )
            }
        runner().run { context ->
            assertThat(context.refusal().map { it.path to it.code })
                .containsExactlyInAnyOrder(REVOCATION_PATH to ProblemCode.REQUIRED, ATTEMPTS_PATH to ProblemCode.REQUIRED)
        }
    }
}
