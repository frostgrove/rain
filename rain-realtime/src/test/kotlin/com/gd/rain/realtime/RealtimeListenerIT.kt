package com.gd.rain.realtime

import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * The bus against a real PostgreSQL and the real dedicated pool.
 *
 * Negative claims ("nothing else arrived") are proven by order, never by waiting: notifications reach a
 * listener in commit order, so when a marker committed afterwards is the first thing read, nothing
 * before it was sent.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealtimeListenerIT {
    private val database = RainPostgres.freshDatabase("realtime_listener")
    private val dataSource = database.dataSource()
    private val settings =
        RealtimeProperties(
            poolName = "realtime-listener-it",
            subscriberBuffer = BUFFER,
            maxSubscriptions = 64,
            minBackoff = Duration.ofMillis(50),
            maxBackoff = Duration.ofMillis(500),
            pollInterval = Duration.ofMillis(50),
            connectTimeout = BOUND,
        )
    private val listener = RealtimeListener(listenerConnections(database, settings), settings)

    // Transaction-aware, as Boot wires jOOQ: over the bare DataSource the publisher would take a second
    // connection and notify outside the caller's transaction, and the rollback test would pass for the wrong reason.
    private val publisher = RealtimePublisher(DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES))
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))

    @BeforeAll
    fun start() {
        listener.start()
    }

    @AfterAll
    fun stop() {
        listener.close()
    }

    @Test
    fun `a notification committed on a channel reaches its subscriber`() {
        val channel = channel()

        listener.subscribe(channel, BOUND).use { subscription ->
            publish(channel, """{"type":"progress"}""")

            assertThat(subscription.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(channel, """{"type":"progress"}""")))
        }
    }

    /** The server holds a notification until commit and discards it on rollback; that is what the bus rests on. */
    @Test
    fun `a rolled-back transaction emits nothing`() {
        val channel = channel()

        listener.subscribe(channel, BOUND).use { subscription ->
            assertThatThrownBy {
                transactions.executeWithoutResult {
                    publisher.publish(channel, "rolled back")
                    error("the write failed after it published")
                }
            }.hasMessageContaining("after it published")
            publish(channel, "committed")

            assertThat(subscription.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(channel, "committed")))
            assertThat(subscription.isOpen).isTrue()
        }
    }

    @Test
    fun `every subscriber of a channel receives its events, and another channel receives only its own`() {
        val watched = channel()
        val other = channel()

        listener.subscribe(watched, BOUND).use { first ->
            listener.subscribe(watched, BOUND).use { second ->
                listener.subscribe(other, BOUND).use { elsewhere ->
                    publish(watched, "one")
                    publish(other, "marker")

                    assertThat(first.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(watched, "one")))
                    assertThat(second.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(watched, "one")))
                    assertThat(elsewhere.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(other, "marker")))
                }
            }
        }
    }

    @Test
    fun `a subscriber that stops reading ends with overflow at subscriber-buffer while the listener serves the rest`() {
        val slow = channel()
        val attentive = channel()

        listener.subscribe(slow, BOUND).use { stuck ->
            listener.subscribe(attentive, BOUND).use { reading ->
                repeat(BUFFER + OVERSHOOT) { publish(slow, "event-$it") }
                publish(attentive, "still here")

                assertThat(reading.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(attentive, "still here")))
                assertThat(stuck.end).isEqualTo(SubscriptionEnd.OVERFLOW)
                assertThat(stuck.drain().map { it.payload }).containsExactlyElementsOf((0 until BUFFER).map { "event-$it" })
                assertThat(stuck.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.OVERFLOW))
            }
        }
    }

    @Test
    fun `a closed subscription ends as unsubscribed and its channel is no longer listened to`() {
        val closed = channel()
        val kept = channel()

        listener.subscribe(kept, BOUND).use {
            val subscription = listener.subscribe(closed, BOUND)
            subscription.close()

            assertThat(subscription.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.UNSUBSCRIBED))
            assertThat(listener.probe(BOUND).listening).containsExactly(kept.name)
        }
    }

    @Test
    fun `a payload of exactly the NOTIFY limit survives the round trip`() {
        val channel = channel()
        val payload = "x".repeat(NotifyRules.MAX_PAYLOAD_BYTES)

        listener.subscribe(channel, BOUND).use { subscription ->
            publish(channel, payload)

            assertThat(subscription.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(channel, payload)))
        }
    }

    private fun publish(
        channel: Channel,
        payload: String,
    ) {
        transactions.executeWithoutResult { publisher.publish(channel, payload) }
    }

    private fun channel(): Channel = Channel.of("topic:${UUID.randomUUID()}")

    private companion object {
        const val BUFFER = 8
        const val OVERSHOOT = 4
    }
}
