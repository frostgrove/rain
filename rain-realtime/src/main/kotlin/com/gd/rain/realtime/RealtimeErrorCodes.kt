package com.gd.rain.realtime

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog

/** The codes rain-realtime refuses a subscription with. */
public object RealtimeErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-realtime"

    /** No listening session is live, or the `LISTEN` was not confirmed in time. */
    public val UNAVAILABLE: ErrorCode = ErrorCode.of("realtime_unavailable", "live updates are not available right now; try again")

    /** `rain.realtime.max-subscriptions` subscriptions are already open in this process. */
    public val SUBSCRIPTION_LIMIT: ErrorCode =
        ErrorCode.of("realtime_subscription_limit", "too many live update subscriptions are open; try again later")

    override val codes: List<ErrorCode> = listOf(UNAVAILABLE, SUBSCRIPTION_LIMIT)
}
