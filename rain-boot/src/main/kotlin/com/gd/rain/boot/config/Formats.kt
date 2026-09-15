package com.gd.rain.boot.config

import org.springframework.util.unit.DataSize
import java.time.Duration

/** A size the way a person writes it in a configuration file: the largest binary unit that divides it exactly. */
public fun DataSize.written(): String {
    val bytes = toBytes()
    for ((suffix, scale) in listOf("GB" to (1L shl 30), "MB" to (1L shl 20), "KB" to (1L shl 10))) {
        if (bytes != 0L && bytes % scale == 0L) return "${bytes / scale}$suffix"
    }
    return "${bytes}B"
}

/** ISO-8601, as the JDK prints it. */
public fun Duration.written(): String = toString()
