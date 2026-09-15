package com.gd.rain.realtime

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.problems
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `rain.realtime`. The section is optional: an application that states no key under it gets neither
 * the publisher nor the listener.
 *
 * [poolName], [subscriberBuffer] and [maxSubscriptions] have no default. The first names the
 * listener's own pool; the other two are the memory the bus may hold, at most
 * `max-subscriptions × subscriber-buffer` events of up to [NotifyRules.MAX_PAYLOAD_BYTES] bytes each,
 * which no value suits every application.
 */
@ConfigurationProperties(RealtimeProperties.PREFIX)
public data class RealtimeProperties(
    public val poolName: String,
    /** Events one subscriber may fall behind by before its stream ends with [SubscriptionEnd.OVERFLOW]. */
    public val subscriberBuffer: Int,
    /** Open subscriptions this process holds at most; one more is refused with `realtime_subscription_limit`. */
    public val maxSubscriptions: Int,
    public val minBackoff: Duration = Duration.ofMillis(200),
    public val maxBackoff: Duration = Duration.ofSeconds(10),
    /**
     * How long one wait for notifications lasts before the listener applies subscription changes again.
     * It bounds how long a subscribe or unsubscribe waits for its `LISTEN`/`UNLISTEN`, not how late an
     * event is: a notification ends the wait when it arrives.
     */
    public val pollInterval: Duration = Duration.ofMillis(200),
    /** How long the listener waits for its connection, including the first one at start-up. */
    public val connectTimeout: Duration = Duration.ofSeconds(10),
) {
    public companion object {
        public const val PREFIX: String = "rain.realtime"

        /** Hikari replaces a connection timeout below this with its own default, so a lower value is refused rather than ignored. */
        public val MIN_CONNECT_TIMEOUT: Duration = Duration.ofMillis(250)

        /** `PGConnection.getNotifications` takes whole milliseconds in an `int`, and 0 means "wait forever". */
        public val MAX_POLL_INTERVAL: Duration = Duration.ofMillis(Int.MAX_VALUE.toLong())
    }
}

internal fun RealtimeProperties.configurationProblems(): List<ConfigurationProblem> {
    val prefix = RealtimeProperties.PREFIX
    return problems {
        expect(poolName.isNotBlank(), "$prefix.pool-name") { "is blank; the listener's own pool is named after it" }
        expect(subscriberBuffer in 1 until Int.MAX_VALUE, "$prefix.subscriber-buffer") {
            "is $subscriberBuffer; it has to be between 1 and ${Int.MAX_VALUE - 1}"
        }
        expect(maxSubscriptions >= 1, "$prefix.max-subscriptions") { "is $maxSubscriptions; it has to be positive" }
        expect(positive(minBackoff), "$prefix.min-backoff") { "is ${minBackoff.written()}; it has to be positive" }
        expect(maxBackoff >= minBackoff, "$prefix.max-backoff") {
            "is ${maxBackoff.written()}, below min-backoff ${minBackoff.written()}"
        }
        expect(wholeMilliseconds(pollInterval), "$prefix.poll-interval") {
            "is ${pollInterval.written()}; it has to be a whole number of milliseconds from 1ms to ${Int.MAX_VALUE}ms"
        }
        expect(connectTimeout >= RealtimeProperties.MIN_CONNECT_TIMEOUT, "$prefix.connect-timeout") {
            "is ${connectTimeout.written()}; it has to be at least ${RealtimeProperties.MIN_CONNECT_TIMEOUT.written()}"
        }
    }
}

private fun positive(duration: Duration): Boolean = !duration.isNegative && !duration.isZero

private fun wholeMilliseconds(duration: Duration): Boolean =
    duration >= Duration.ofMillis(1) &&
        duration <= RealtimeProperties.MAX_POLL_INTERVAL &&
        duration == Duration.ofMillis(duration.toMillis())

public class RealtimeConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec(RealtimeProperties.PREFIX, RealtimeProperties::class, Presence.OPTIONAL) { properties, _ ->
                properties.configurationProblems()
            },
        )
}
