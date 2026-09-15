package com.gd.rain.core.log

/** Names shared by every module that writes or reads request correlation. */
public object Correlation {
    /** The MDC key the request log filter publishes the correlation id under. */
    public const val MDC_REQUEST_ID: String = "request_id"
}
