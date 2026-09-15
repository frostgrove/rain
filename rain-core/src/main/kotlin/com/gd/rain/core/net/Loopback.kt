package com.gd.rain.core.net

import java.net.InetAddress

/**
 * Whether a host names this machine, decided without a name lookup.
 *
 * - An IP literal (IPv4, or IPv6 with or without brackets) is loopback when [InetAddress.isLoopbackAddress] says so.
 * - A name is loopback when it is `localhost` or ends in `.localhost` (RFC 6761 §6.3), compared ASCII
 *   case-insensitively, one trailing dot ignored.
 *
 * Every other name is not loopback: what it resolves to is not known here and is not guessed.
 */
public object Loopback {
    private const val LOCALHOST = "localhost"

    public fun isLoopbackHost(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        literal(bare)?.let { return it.isLoopbackAddress }
        val name = bare.removeSuffix(".").lowercase()
        return name == LOCALHOST || name.endsWith(".$LOCALHOST")
    }

    private fun literal(host: String): InetAddress? =
        try {
            InetAddress.ofLiteral(host)
        } catch (_: IllegalArgumentException) {
            null
        }
}
