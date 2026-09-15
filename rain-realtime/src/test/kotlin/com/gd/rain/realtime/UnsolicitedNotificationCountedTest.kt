package com.gd.rain.realtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Gap 13, third part: `Channel.ofOrNull` let the listener drop a notification whose name it could not
 * parse, and a name nobody held was dropped as well, both without a trace. Each is now counted and
 * logged by the length of the name, never its content.
 */
class UnsolicitedNotificationCountedTest {
    @Test
    fun `a notification on a name no subscription holds, or on an invalid name, is counted and logged by length only`() {
        val session = FakeSession()
        val logger = LoggerFactory.getLogger(RealtimeListener::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val connections = ScriptedConnections(listOf(Opening.Served(session)))
        try {
            RealtimeListener(connections, testProperties(), RecordingWait(holdAfter = 1)).use { listener ->
                listener.start()
                val watched = Channel.of("watched")
                listener.subscribe(watched, BOUND).use { subscription ->
                    session.send(INVALID to "x", UNHELD to "y", watched.name to "z")

                    assertThat(listener.unsolicitedNotifications()).isEqualTo(2)
                    assertThat(subscription.poll(BOUND)).isEqualTo(Next.Event(RealtimeEvent(watched, "z")))
                }
            }
        } finally {
            logger.detachAppender(appender)
        }

        val warnings =
            appender.list
                .filter { it.level == Level.WARN && it.formattedMessage.contains("unsolicited") }
                .map { it.formattedMessage }
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("the name is not a valid channel").contains("name_bytes=${INVALID.length}")
        assertThat(warnings[1]).contains("no subscription holds the channel").contains("name_bytes=${UNHELD.length}")
        assertThat(warnings).noneMatch { it.contains(INVALID) || it.contains(UNHELD) }
    }

    @Test
    fun `a delivered notification is not counted`() {
        val session = FakeSession()
        val connections = ScriptedConnections(listOf(Opening.Served(session)))
        RealtimeListener(connections, testProperties(), RecordingWait(holdAfter = 1)).use { listener ->
            listener.start()
            val watched = Channel.of("watched")
            listener.subscribe(watched, BOUND).use {
                session.send(watched.name to "one", watched.name to "two")

                assertThat(listener.unsolicitedNotifications()).isZero()
            }
        }
    }

    private companion object {
        const val INVALID = "not a channel!"
        const val UNHELD = "nobody-holds-this"
    }
}
