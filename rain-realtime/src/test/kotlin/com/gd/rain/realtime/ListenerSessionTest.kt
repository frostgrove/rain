package com.gd.rain.realtime

import com.gd.rain.core.error.Fault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How a session begins, ends and keeps its `LISTEN` set, against connections with no database behind them. */
class ListenerSessionTest {
    /** Gap 13, second part, at unit level; `ListenerReconnectUnlistenIT` proves the same on a real server and pool. */
    @Test
    fun `every session starts with UNLISTEN star, and a failed session's connection is evicted, never released`() {
        val first = FakeSession()
        val second = FakeSession()
        val connections = ScriptedConnections(listOf(Opening.Served(first), Opening.Served(second)))

        RealtimeListener(connections, testProperties(), RecordingWait(holdAfter = 2)).use { listener ->
            listener.start()
            val before = listener.subscribe(Channel.of("before"), BOUND)
            first.drop()
            assertThat(before.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.GAP))
            assertThat(listener.awaitLive(BOUND)).isTrue()
            listener.subscribe(Channel.of("after"), BOUND)

            assertThat(first.executed).startsWith(RealtimeListener.UNLISTEN_ALL)
            assertThat(
                second.executed,
            ).containsExactly(RealtimeListener.UNLISTEN_ALL, NotifyRules.SERVER_SETTINGS_QUERY, "LISTEN \"after\"")
            assertThat(connections.evicted).containsExactly(first.connection)
            assertThat(connections.released).isEmpty()
        }

        assertThat(connections.released).containsExactly(second.connection)
        assertThat(connections.closed).isTrue()
    }

    @Test
    fun `a dropped session ends every subscription with a gap before the listener waits to reconnect`() {
        val session = FakeSession()
        val subscriptions = CopyOnWriteArrayList<Subscription>()
        val endsAtWait = CopyOnWriteArrayList<SubscriptionEnd?>()
        val waited = CountDownLatch(1)
        val wait =
            ReconnectWait { _, stop ->
                subscriptions.forEach { endsAtWait += it.end }
                waited.countDown()
                stop.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)
                false
            }

        RealtimeListener(ScriptedConnections(listOf(Opening.Served(session))), testProperties(), wait).use { listener ->
            listener.start()
            subscriptions += listener.subscribe(Channel.of("a"), BOUND)
            subscriptions += listener.subscribe(Channel.of("b"), BOUND)

            session.drop()

            check(waited.await(BOUND.toNanos(), TimeUnit.NANOSECONDS)) { "the listener never waited to reconnect" }
        }

        assertThat(endsAtWait).containsExactly(SubscriptionEnd.GAP, SubscriptionEnd.GAP)
    }

    @Test
    fun `the last subscriber leaving a channel unlistens it, and one remaining keeps it listened`() {
        val session = FakeSession()
        listener(session).use { listener ->
            listener.start()
            val first = listener.subscribe(Channel.of("a"), BOUND)
            val second = listener.subscribe(Channel.of("a"), BOUND)

            first.close()
            // A subscribe returns only after its own LISTEN, so every change recorded before it has been applied.
            listener.subscribe(Channel.of("b"), BOUND)
            assertThat(session.subscriptionStatements()).containsExactly("LISTEN \"a\"", "LISTEN \"b\"")

            second.close()
            listener.subscribe(Channel.of("c"), BOUND)
            assertThat(session.subscriptionStatements()).containsExactly("LISTEN \"a\"", "LISTEN \"b\"", "UNLISTEN \"a\"", "LISTEN \"c\"")
            assertThat(first.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.UNSUBSCRIBED))
        }
    }

    @Test
    fun `a subscribe whose LISTEN is not confirmed in time is refused as unavailable and leaves nothing behind`() {
        val session = FakeSession()
        listener(session).use { listener ->
            listener.start()
            val held = session.holdNextListen()

            assertThatThrownBy { listener.subscribe(Channel.of("slow"), Duration.ofMillis(100)) }
                .matches({ it is Fault && it.code == RealtimeErrorCodes.UNAVAILABLE }, "a realtime_unavailable fault")
            assertThat(listener.subscriptionCount()).isZero()

            held.countDown()
            listener.subscribe(Channel.of("next"), BOUND)
            assertThat(session.subscriptionStatements()).containsExactly("LISTEN \"slow\"", "UNLISTEN \"slow\"", "LISTEN \"next\"")
        }
    }

    @Test
    fun `stopping ends open subscriptions as closed and returns the connection to its pool`() {
        val session = FakeSession()
        val connections = ScriptedConnections(listOf(Opening.Served(session)))
        val listener = RealtimeListener(connections, testProperties(), RecordingWait(holdAfter = 1))
        listener.start()
        val subscription = listener.subscribe(Channel.of("a"), BOUND)

        listener.close()

        assertThat(subscription.poll(BOUND)).isEqualTo(Next.Ended(SubscriptionEnd.CLOSED))
        assertThat(connections.released).containsExactly(session.connection)
        assertThat(connections.evicted).isEmpty()
        assertThat(listener.isLive()).isFalse()
    }

    @Test
    fun `a server built with other notify limits is refused at start`() {
        val listener = listener(FakeSession(blockSize = 16_384))

        assertThatThrownBy { listener.start() }
            .isInstanceOf(ListenerStartRefused::class.java)
            .hasRootCauseInstanceOf(NotifyRulesMismatch::class.java)
        listener.close()
    }

    private fun listener(session: FakeSession): RealtimeListener =
        RealtimeListener(ScriptedConnections(listOf(Opening.Served(session))), testProperties(), RecordingWait(holdAfter = 1))
}
