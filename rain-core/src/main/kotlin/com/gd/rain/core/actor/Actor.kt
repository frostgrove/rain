package com.gd.rain.core.actor

/**
 * Who performed an operation: a subject type and that subject's identifier.
 *
 * Both parts are required and bounded, so an audit row or an ownership column can store the pair
 * without a foreign key into any one subject table — a service account and a person are both actors.
 */
public data class Actor(
    public val type: String,
    public val id: String,
) {
    init {
        require(TYPE.matches(type)) { "an actor type matches ${TYPE.pattern}, got \"$type\"" }
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH) { "an actor id is 1..$MAX_ID_LENGTH characters" }
    }

    public companion object {
        private val TYPE = Regex("^[a-z][a-z0-9_-]{0,63}$")

        public const val MAX_ID_LENGTH: Int = 128
    }
}

/** The actor of the operation running on the current thread, or `null` when nothing authenticated it. */
public fun interface CurrentActor {
    public fun actor(): Actor?
}
