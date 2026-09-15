package com.gd.rain.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Gap 13, first part: `poll` was documented to answer at once once the subscription had finished, but
 * `finish` only set a flag, so a poller parked on an empty queue slept out its whole timeout. The end
 * now travels through the queue as a sentinel.
 *
 * Every poll here asks for an hour and is answered on a daemon thread the test waits for at most
 * [BOUND]: against the old behaviour each test fails at the bound instead of holding the test JVM.
 */
class SubscriptionPollWakesOnFinishTest {
    private val channel = Channel.of("topic:1")

    private fun subscription(buffer: Int = BUFFER): Subscription = Subscription(channel, buffer) { }

    @Test
    fun `a poller parked on an empty buffer is woken by finish and answers the end at once`() {
        val subscription = subscription()
        val answer = CompletableFuture<Next>()
        val poller = Thread.ofPlatform().daemon(true).start { answer.complete(subscription.poll(FOREVER)) }
        awaitParked(poller)

        subscription.finish(SubscriptionEnd.GAP)

        assertThat(answer.get(BOUND.toNanos(), TimeUnit.NANOSECONDS)).isEqualTo(Next.Ended(SubscriptionEnd.GAP))
    }

    @Test
    fun `after the end every poll answers at once, however often it is asked`() {
        val subscription = subscription()
        subscription.finish(SubscriptionEnd.CLOSED)

        assertThat(List(3) { pollWithinBound(subscription) }).containsOnly(Next.Ended(SubscriptionEnd.CLOSED))
        assertThat(subscription.end).isEqualTo(SubscriptionEnd.CLOSED)
    }

    @Test
    fun `events delivered before the end are read before it`() {
        val subscription = subscription()
        subscription.deliver(event("one"))
        subscription.deliver(event("two"))
        subscription.finish(SubscriptionEnd.GAP)

        assertThat(List(3) { pollWithinBound(subscription) }).containsExactly(
            Next.Event(event("one")),
            Next.Event(event("two")),
            Next.Ended(SubscriptionEnd.GAP),
        )
    }

    @Test
    fun `delivery refuses at the buffer, and the end still fits behind a full buffer`() {
        val subscription = subscription(buffer = 2)

        assertThat(listOf(subscription.deliver(event("1")), subscription.deliver(event("2")), subscription.deliver(event("3"))))
            .containsExactly(Delivery.QUEUED, Delivery.QUEUED, Delivery.FULL)
        assertThat(subscription.finish(SubscriptionEnd.OVERFLOW)).isTrue()

        assertThat(subscription.drain()).containsExactly(event("1"), event("2"))
        assertThat(pollWithinBound(subscription)).isEqualTo(Next.Ended(SubscriptionEnd.OVERFLOW))
    }

    @Test
    fun `the first end wins and nothing is delivered after it`() {
        val subscription = subscription()

        assertThat(subscription.finish(SubscriptionEnd.UNSUBSCRIBED)).isTrue()
        assertThat(subscription.finish(SubscriptionEnd.GAP)).isFalse()
        assertThat(subscription.deliver(event("late"))).isEqualTo(Delivery.ENDED)
        assertThat(pollWithinBound(subscription)).isEqualTo(Next.Ended(SubscriptionEnd.UNSUBSCRIBED))
        assertThat(subscription.isOpen).isFalse()
    }

    @Test
    fun `an open subscription with nothing delivered answers idle when its timeout passes`() {
        assertThat(subscription().poll(Duration.ZERO)).isEqualTo(Next.Idle)
    }

    private fun event(payload: String) = RealtimeEvent(channel, payload)

    /** A poll asking for [FOREVER], answered on a daemon thread; a poll that does not return fails the test at [BOUND]. */
    private fun pollWithinBound(subscription: Subscription): Next {
        val answer = CompletableFuture<Next>()
        Thread.ofPlatform().daemon(true).start { answer.complete(subscription.poll(FOREVER)) }
        return answer.get(BOUND.toNanos(), TimeUnit.NANOSECONDS)
    }

    /** The poller's own state, not a sleep: it is parked once it waits inside the queue with a timeout. */
    private fun awaitParked(thread: Thread) {
        val deadline = System.nanoTime() + BOUND.toNanos()
        while (thread.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "the poller never parked; it is ${thread.state}" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val BUFFER = 4

        /** Far beyond any bound in the test: a poll that returns did not wait for it. */
        val FOREVER: Duration = Duration.ofHours(1)
    }
}
