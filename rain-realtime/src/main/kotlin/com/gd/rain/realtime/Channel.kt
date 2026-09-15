package com.gd.rain.realtime

/**
 * A `LISTEN`/`NOTIFY` channel name, valid by construction against [NotifyRules].
 *
 * The byte ceiling is the reason the type exists. PostgreSQL truncates an identifier at
 * `NAMEDATALEN - 1` bytes without saying so, so two names that agree in their first 63 bytes would be
 * one channel and every subscriber of one would receive the other's events. Such a name is refused
 * here instead.
 *
 * The character set is narrower than an identifier's on purpose: `LISTEN` takes an identifier, not a
 * parameter, so the name is written into the statement as a quoted identifier, and a set with no
 * quote and no backslash in it cannot end one.
 */
@JvmInline
public value class Channel private constructor(
    public val name: String,
) {
    override fun toString(): String = name

    /** How the name is written inside `LISTEN`/`UNLISTEN`. Doubling a quote the set cannot hold keeps the escape correct on its own. */
    internal fun quoted(): String = "\"" + name.replace("\"", "\"\"") + "\""

    public companion object {
        /** The channel named [name]; [IllegalArgumentException] naming the rule it breaks otherwise. */
        public fun of(name: String): Channel =
            when (val parsed = parse(name)) {
                is ChannelParse.Valid -> parsed.channel
                is ChannelParse.Invalid -> throw IllegalArgumentException(parsed.message)
            }

        /** The channel named [name], or the rule that refuses it. Rules are checked in the order of [ChannelRule]. */
        public fun parse(name: String): ChannelParse {
            if (name.isEmpty()) return ChannelParse.Invalid(ChannelRule.EMPTY, "realtime: a channel name cannot be empty")
            val bytes = name.toByteArray(Charsets.UTF_8).size
            if (bytes > NotifyRules.MAX_CHANNEL_NAME_BYTES) {
                return ChannelParse.Invalid(
                    ChannelRule.TOO_LONG,
                    "realtime: the channel name \"$name\" is $bytes bytes; PostgreSQL truncates a name at " +
                        "${NotifyRules.MAX_CHANNEL_NAME_BYTES} bytes and would merge it with another",
                )
            }
            val refused = name.firstOrNull { !NotifyRules.isChannelCharacter(it) }
            if (refused != null) {
                return ChannelParse.Invalid(
                    ChannelRule.CHARACTER,
                    "realtime: the channel name \"$name\" contains '$refused', which is not allowed in one " +
                        "(allowed: ${NotifyRules.CHANNEL_CHARACTERS})",
                )
            }
            return ChannelParse.Valid(Channel(name))
        }
    }
}

/** The rules a channel name is checked against, in the order they are checked. */
public enum class ChannelRule {
    EMPTY,
    TOO_LONG,
    CHARACTER,
}

public sealed interface ChannelParse {
    public data class Valid(
        public val channel: Channel,
    ) : ChannelParse

    /** [message] quotes the refused name; it is for the code that chose the name, not for a log of names received. */
    public data class Invalid(
        public val rule: ChannelRule,
        public val message: String,
    ) : ChannelParse
}

/** One notification as it left the database. */
public data class RealtimeEvent(
    public val channel: Channel,
    public val payload: String,
)
