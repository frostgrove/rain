package com.gd.rain.realtime

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `max-subscriptions` is a declared bound on the process: one more is an explicit refusal, never a larger map. */
class MaxSubscriptionsRefusedTest {
    @Test
    fun `one subscription beyond max-subscriptions is refused with the module's code, and closing one frees its slot`() {
        val session = FakeSession()
        listener(session, testProperties(maxSubscriptions = 2)).use { listener ->
            listener.start()
            val first = listener.subscribe(Channel.of("a"), BOUND)
            listener.subscribe(Channel.of("a"), BOUND)

            assertThatThrownBy { listener.subscribe(Channel.of("b"), BOUND) }
                .matches(
                    { it is Fault && it.code == RealtimeErrorCodes.SUBSCRIPTION_LIMIT && it.kind == FaultKind.RETRYABLE },
                    "a retryable realtime_subscription_limit fault",
                )
            assertThat(listener.subscriptionCount()).isEqualTo(2)

            first.close()
            listener.subscribe(Channel.of("b"), BOUND)

            assertThat(listener.subscriptionCount()).isEqualTo(2)
            assertThat(session.subscriptionStatements()).containsExactly("LISTEN \"a\"", "LISTEN \"b\"")
        }
    }

    @Test
    fun `a subscriber ended by overflow gives its slot back`() {
        val session = FakeSession()
        listener(session, testProperties(subscriberBuffer = 2, maxSubscriptions = 1)).use { listener ->
            listener.start()
            val stuck = listener.subscribe(Channel.of("a"), BOUND)

            session.send("a" to "1", "a" to "2", "a" to "3")

            assertThat(stuck.end).isEqualTo(SubscriptionEnd.OVERFLOW)
            assertThat(listener.subscriptionCount()).isZero()
            listener.subscribe(Channel.of("b"), BOUND)
        }
    }

    @Test
    fun `a listener with no live session refuses with realtime_unavailable`() {
        listener(FakeSession(), testProperties()).use { listener ->
            assertThatThrownBy { listener.subscribe(Channel.of("a"), BOUND) }
                .matches({ it is Fault && it.code == RealtimeErrorCodes.UNAVAILABLE }, "a realtime_unavailable fault")
        }
    }

    private fun listener(
        session: FakeSession,
        properties: RealtimeProperties,
    ): RealtimeListener = RealtimeListener(ScriptedConnections(listOf(Opening.Served(session))), properties, RecordingWait(holdAfter = 1))
}
