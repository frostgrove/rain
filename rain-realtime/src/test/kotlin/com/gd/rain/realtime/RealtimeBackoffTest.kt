package com.gd.rain.realtime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration

/**
 * The reconnect ladder, with the waiting injected: nothing here sleeps. The ladder resets on every
 * connection, not when the listening loop returns, so a drop after a healthy session costs
 * `min-backoff` again rather than wherever the ladder had climbed to.
 */
class RealtimeBackoffTest {
    @Test
    fun `every reconnect after a session that connected waits min-backoff again`() {
        val connections = ScriptedConnections(List(DROPS) { droppingSession() })
        val waits = RecordingWait(holdAfter = DROPS)

        RealtimeListener(connections, testProperties(minBackoff = MIN, maxBackoff = MAX), waits).use { listener ->
            listener.start()
            waits.awaitRecorded(DROPS)
        }

        assertThat(waits.durations).containsExactly(MIN, MIN, MIN, MIN)
    }

    @Test
    fun `failures to connect double the wait up to max-backoff, and a session that connects resets it`() {
        val connections = ScriptedConnections(listOf(droppingSession(), refused(), refused(), refused(), droppingSession()))
        val waits = RecordingWait(holdAfter = 5)

        RealtimeListener(connections, testProperties(minBackoff = MIN, maxBackoff = MAX), waits).use { listener ->
            listener.start()
            waits.awaitRecorded(5)
        }

        assertThat(waits.durations).containsExactly(MIN, MIN.multipliedBy(2), MAX, MAX, MIN)
    }

    @Test
    fun `a first session that cannot connect refuses start and is not retried`() {
        val connections = ScriptedConnections(listOf(refused()))
        val waits = RecordingWait(holdAfter = 1)
        val listener = RealtimeListener(connections, testProperties(), waits)

        assertThatThrownBy { listener.start() }
            .isInstanceOf(ListenerStartRefused::class.java)
            .hasRootCauseInstanceOf(SQLException::class.java)
        listener.close()

        assertThat(connections.opened.get()).isEqualTo(1)
        assertThat(waits.durations).isEmpty()
        assertThat(listener.isRunning()).isFalse()
    }

    @Test
    fun `the ladder doubles without passing its ceiling`() {
        val backoff = ReconnectBackoff(Duration.ofMillis(200), Duration.ofSeconds(1))

        assertThat(generateSequence(backoff.min, backoff::next).take(6).toList()).containsExactly(
            Duration.ofMillis(200),
            Duration.ofMillis(400),
            Duration.ofMillis(800),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
        )
    }

    private fun droppingSession(): Opening = Opening.Served(FakeSession().apply { drop() })

    private fun refused(): Opening = Opening.Refused(SQLException("connection refused", "08001"))

    private companion object {
        val MIN: Duration = Duration.ofMillis(20)
        val MAX: Duration = Duration.ofMillis(50)

        /** Enough drops that a ladder which never reset would be at its ceiling by the third. */
        const val DROPS = 4
    }
}
