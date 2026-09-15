package com.gd.rain.realtime

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The publisher's refusals, none of which needs a database. The `DSLContext` is a strict mock, so a
 * refusal that issued any statement would fail with an unexpected call.
 */
class RealtimePublisherTest {
    private val publisher = RealtimePublisher(mockk<DSLContext>())

    private val channel = Channel.of("topic:1")

    @Test
    fun `publishing outside a transaction is refused, naming the channel`() {
        assertThatThrownBy { publisher.publish(channel, "{}") }
            .isInstanceOf(PublishRefusal.NotInTransaction::class.java)
            .hasMessageContaining("topic:1")
            .hasMessageContaining("the transaction that wrote what it is about")
    }

    @Test
    fun `a payload over the NOTIFY limit is refused before the transaction is looked at`() {
        assertThatThrownBy { publisher.publish(channel, "x".repeat(NotifyRules.MAX_PAYLOAD_BYTES + 1)) }
            .isInstanceOf(PublishRefusal.PayloadTooLarge::class.java)
            .hasMessageContaining("8000 bytes")
    }

    @Test
    fun `the limit counts UTF-8 bytes`() {
        assertThatThrownBy { publisher.publish(channel, "д".repeat(4_000)) }
            .isInstanceOf(PublishRefusal.PayloadTooLarge::class.java)
        assertThatThrownBy { publisher.publish(channel, "😀".repeat(2_000)) }
            .describedAs("a character outside the BMP is four bytes")
            .isInstanceOf(PublishRefusal.PayloadTooLarge::class.java)

        // 7999 bytes exactly is allowed, and therefore reaches the transaction check.
        assertThatThrownBy { publisher.publish(channel, "x".repeat(NotifyRules.MAX_PAYLOAD_BYTES)) }
            .isInstanceOf(PublishRefusal.NotInTransaction::class.java)
        assertThatThrownBy { publisher.publish(channel, "д".repeat(3_999) + "x") }
            .isInstanceOf(PublishRefusal.NotInTransaction::class.java)
    }

    @Test
    fun `counting stops at the limit, so an oversized payload is never measured in full`() {
        assertThatThrownBy { publisher.publish(channel, "x".repeat(1_000_000)) }
            .matches(
                { it is PublishRefusal.PayloadTooLarge && it.atLeastBytes == NotifyRules.MAX_PAYLOAD_BYTES + 1 },
                "refused at the first byte past the limit",
            )
    }

    /** PostgreSQL's text cannot hold a NUL, and the driver would report it as an unrelated encoding error. */
    @Test
    fun `a NUL in the payload is refused`() {
        assertThatThrownBy { publisher.publish(channel, "{\"a\":\"\u0000\"}") }
            .isInstanceOf(PublishRefusal.PayloadNotText::class.java)
    }

    /** An unpaired surrogate has no UTF-8 form; encoding it would silently replace it with '?'. */
    @Test
    fun `an unpaired surrogate is refused rather than altered`() {
        assertThatThrownBy { publisher.publish(channel, "a\uD800b") }.isInstanceOf(PublishRefusal.PayloadNotText::class.java)
        assertThatThrownBy { publisher.publish(channel, "a\uD800") }.isInstanceOf(PublishRefusal.PayloadNotText::class.java)
        assertThatThrownBy { publisher.publish(channel, "\uDC00") }.isInstanceOf(PublishRefusal.PayloadNotText::class.java)
    }

    @Test
    fun `a transaction that is merely synchronised is still not an actual one`() {
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertThatThrownBy { publisher.publish(channel, "{}") }
                .describedAs("synchronisation without a real transaction is what a non-transactional scope has")
                .isInstanceOf(PublishRefusal.NotInTransaction::class.java)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
