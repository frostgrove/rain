package com.gd.rain.realtime

import java.sql.Statement

/**
 * PostgreSQL's limits on `LISTEN`/`NOTIFY`, declared once and checked against the server.
 *
 * Version 1 is the rule set of a PostgreSQL build with the default `NAMEDATALEN` (64) and `BLCKSZ`
 * (8192). An identifier holds at most `NAMEDATALEN - 1` bytes, and `NOTIFY` refuses a payload of
 * `NOTIFY_PAYLOAD_MAX_LENGTH = BLCKSZ - NAMEDATALEN - 128` bytes or more (`src/include/commands/async.h`):
 * 8000 bytes counting the terminator, so 7999 of text.
 *
 * Every listening session reads `max_identifier_length` and `block_size` from the server it connected
 * to and refuses to serve when either differs from what is declared here, so channel names and
 * payloads are never checked against the limits of a different build.
 */
public object NotifyRules {
    public const val VERSION: Int = 1

    /** `NAMEDATALEN - 1`, measured in bytes. */
    public const val MAX_CHANNEL_NAME_BYTES: Int = 63

    /** `BLCKSZ`, which the payload limit is derived from. */
    public const val BLOCK_SIZE: Int = 8192

    /** `NOTIFY_PAYLOAD_MAX_LENGTH`, the terminator included. */
    public const val PAYLOAD_LIMIT_BYTES: Int = BLOCK_SIZE - (MAX_CHANNEL_NAME_BYTES + 1) - 128

    /** The largest payload `NOTIFY` accepts, in UTF-8 bytes. */
    public const val MAX_PAYLOAD_BYTES: Int = PAYLOAD_LIMIT_BYTES - 1

    public const val CHANNEL_CHARACTERS: String = "A-Z a-z 0-9 _ - : ."

    public fun isChannelCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '_' ||
            character == '-' ||
            character == ':' ||
            character == '.'

    internal const val SERVER_SETTINGS_QUERY: String =
        "SELECT current_setting('max_identifier_length'), current_setting('block_size')"

    /** Refuses a server whose build does not have the limits declared above. */
    internal fun verify(statement: Statement) {
        statement.executeQuery(SERVER_SETTINGS_QUERY).use { rows ->
            check(rows.next()) { "realtime: the server answered no row for its identifier length and block size" }
            val identifier = rows.getString(1).toInt()
            val block = rows.getString(2).toInt()
            if (identifier != MAX_CHANNEL_NAME_BYTES) throw NotifyRulesMismatch("max_identifier_length", MAX_CHANNEL_NAME_BYTES, identifier)
            if (block != BLOCK_SIZE) throw NotifyRulesMismatch("block_size", BLOCK_SIZE, block)
        }
    }
}

/** The server a listening session connected to was built with limits other than [NotifyRules] declares. */
public class NotifyRulesMismatch(
    public val setting: String,
    public val declared: Int,
    public val server: Int,
) : IllegalStateException(
        "realtime: the server's $setting is $server, but notify rules version ${NotifyRules.VERSION} declare $declared; " +
            "channel names and payloads would be checked against limits this server does not have",
    )
