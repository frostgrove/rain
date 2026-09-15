package com.gd.rain.realtime

import com.gd.rain.core.error.Fault
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * Gap 13, second part: after a session error the pooled connection went back to the pool with its
 * `LISTEN`s still registered while the listener's record of what it had issued started empty. Every
 * session now starts with `UNLISTEN *`, and a failed session evicts its connection.
 *
 * `pg_listening_channels()` only answers for the session that runs it, so it is read on the listening
 * connection itself, through the listener's probe. The reconnect is held by the test, so "while it
 * reconnects" is a state, not a race.
 */
@Tag("integration")
class ListenerReconnectUnlistenIT {
    private val database = RainPostgres.freshDatabase("realtime_reconnect")
    private val dataSource = database.dataSource()
    private val publisher = RealtimePublisher(DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES))
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val reconnect = HeldReconnect()
    private val settings =
        RealtimeProperties(
            poolName = "realtime-reconnect-it",
            subscriberBuffer = 8,
            maxSubscriptions = 16,
            pollInterval = Duration.ofMillis(50),
            connectTimeout = BOUND,
        )

    @Test
    fun `after the listening backend is terminated, the new session listens on exactly the live subscriptions`() {
        RealtimeListener(listenerConnections(database, settings), settings, reconnect).use { listener ->
            listener.start()
            val kept = channel()
            val dropped = channel()
            val added = channel()
            val first = listener.subscribe(kept, BOUND)
            val second = listener.subscribe(dropped, BOUND)
            val before = listener.probe(BOUND)
            assertThat(before.listening).containsExactlyInAnyOrder(kept.name, dropped.name)

            terminate(before.backendPid)

            assertThat(first.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.GAP))
            assertThat(second.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.GAP))
            reconnect.awaitEntered()
            second.close()
            assertThatThrownBy { listener.subscribe(added, BOUND) }
                .matches({ it is Fault && it.code == RealtimeErrorCodes.UNAVAILABLE }, "refused as unavailable while reconnecting")
            reconnect.proceed()

            assertThat(listener.awaitLive(BOUND)).isTrue()
            val resumed = listener.subscribe(kept, BOUND)
            listener.subscribe(added, BOUND)
            val after = listener.probe(BOUND)

            assertThat(after.backendPid).isNotEqualTo(before.backendPid)
            assertThat(after.listening).containsExactlyInAnyOrder(kept.name, added.name)
            assertThat(second.end).describedAs("closing after the gap keeps the gap").isEqualTo(SubscriptionEnd.GAP)

            transactions.executeWithoutResult { publisher.publish(kept, "after the reconnect") }
            assertThat(resumed.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(kept, "after the reconnect")))
        }
    }

    @Test
    fun `a session that fails on a live connection evicts it, so its LISTENs never reach the next session`() {
        val connections = FailingConnections(listenerConnections(database, settings))
        RealtimeListener(connections, settings, reconnect).use { listener ->
            listener.start()
            val stale = channel()
            val added = channel()
            val subscription = listener.subscribe(stale, BOUND)
            val before = listener.probe(BOUND)

            connections.failNextPoll()

            assertThat(subscription.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.GAP))
            reconnect.awaitEntered()
            reconnect.proceed()
            assertThat(listener.awaitLive(BOUND)).isTrue()
            listener.subscribe(added, BOUND)
            val after = listener.probe(BOUND)

            assertThat(after.backendPid)
                .describedAs("a pool of one connection hands the same backend back unless the failed one was evicted")
                .isNotEqualTo(before.backendPid)
            assertThat(after.listening).containsExactly(added.name)
        }
    }

    private fun terminate(pid: Int) {
        val terminated = JdbcTemplate(dataSource).queryForObject("SELECT pg_terminate_backend(?)", Boolean::class.javaObjectType, pid)
        assertThat(terminated).describedAs("backend %s was terminated", pid).isTrue()
    }

    private fun channel(): Channel = Channel.of("topic:${UUID.randomUUID()}")
}
