package com.gd.rain.access.internal.net

import java.net.InetAddress

/**
 * Whether a host names this machine, decided by rule and never by resolving it.
 *
 * - an IP literal is loopback when [InetAddress.isLoopbackAddress] says so (all of `127.0.0.0/8`, `::1`), read with
 *   [InetAddress.ofLiteral], which parses and never looks a name up; an IPv6 literal may be written in brackets;
 * - a name is loopback when it is `localhost` or ends in `.localhost` (RFC 6761 §6.3), compared ASCII
 *   case-insensitively;
 * - anything else is not.
 */
public object Loopback {
    public fun isLoopbackHost(host: String): Boolean {
        val literal = host.removeSurrounding("[", "]")
        val address =
            try {
                InetAddress.ofLiteral(literal)
            } catch (notALiteral: IllegalArgumentException) {
                null
            }
        if (address != null) return address.isLoopbackAddress
        val name = host.lowercase()
        return name == LOCALHOST || name.endsWith(".$LOCALHOST")
    }

    private const val LOCALHOST = "localhost"
}
