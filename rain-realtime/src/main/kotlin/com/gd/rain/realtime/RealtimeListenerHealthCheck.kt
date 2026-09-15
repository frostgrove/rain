package com.gd.rain.realtime

import com.gd.rain.observability.health.HealthCheck
import java.time.Duration

/**
 * The listener's readiness check, `realtime.listener`: failing while no session is live, because no
 * subscription can be opened then. Its importance is the application's (`rain.health.checks.realtime.listener`).
 */
public class RealtimeListenerHealthCheck(
    private val listener: RealtimeListener,
) : HealthCheck {
    override val name: String = NAME
    override val code: String = CODE
    override val timeout: Duration? = null

    override fun probe() {
        check(listener.isLive()) { "the realtime listener has no live session" }
    }

    public companion object {
        public const val NAME: String = "realtime.listener"
        public const val CODE: String = "realtime"
    }
}
