package com.gd.rain.realtime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import java.time.Duration

/** The listener check fails while no session is live; a live listener is covered in RealtimeWiringIT. */
class RealtimeListenerHealthCheckTest {
    @Test
    fun `a listener with no live session fails the check, under its name and public code`() {
        val properties =
            RealtimeProperties(poolName = "health", subscriberBuffer = 1, maxSubscriptions = 1, connectTimeout = Duration.ofMillis(250))
        val source = DataSourceProperties().apply { url = "jdbc:postgresql://127.0.0.1:1/none" }

        RealtimeListener(HikariListenerConnections.of(properties, source), properties).use { listener ->
            val check = RealtimeListenerHealthCheck(listener)

            assertThat(check.name).isEqualTo("realtime.listener")
            assertThat(check.code).isEqualTo("realtime")
            assertThatThrownBy { check.probe() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("the realtime listener has no live session")
        }
    }
}
