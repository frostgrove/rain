package com.gd.rain.realtime

import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** A real application start, so configuration validation, the role condition and the lifecycle all take part. */
@Tag("integration")
class RealtimeWiringIT {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application

    @Test
    fun `an api process listens from its own pool, delivers what a committed transaction published, and closes streams on shutdown`() {
        val database = RainPostgres.freshDatabase("realtime_wiring")
        val properties =
            database.springProperties() +
                listOf(
                    "spring.application.name=sample",
                    "rain.runtime.roles=api",
                    "rain.deployment.stage=test",
                    "rain.persistence.statement-timeout=5s",
                    "spring.flyway.enabled=false",
                    "rain.realtime.pool-name=sample-realtime",
                    "rain.realtime.subscriber-buffer=8",
                    "rain.realtime.max-subscriptions=16",
                    "rain.realtime.poll-interval=20ms",
                )
        val context =
            SpringApplicationBuilder(Application::class.java)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties(*properties.toTypedArray())
                .run()
        val channel = Channel.of("topic:${UUID.randomUUID()}")
        val subscription: Subscription
        try {
            val listener = context.getBean(RealtimeListener::class.java)
            assertThat(listener.isRunning()).isTrue()
            assertThat(listener.isLive()).isTrue()
            subscription = listener.subscribe(channel, BOUND)

            TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
                context.getBean(RealtimePublisher::class.java).publish(channel, "saved")
            }

            assertThat(subscription.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(channel, "saved")))
            assertThat(listener.probe(BOUND).listening).containsExactly(channel.name)
        } finally {
            context.close()
        }

        assertThat(subscription.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.CLOSED))
    }
}
