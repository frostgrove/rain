package com.gd.rain.i18n

/** A module-qualified stable message identity: `module.message_name`. */
public data class MessageKey(
    public val module: String,
    public val name: String,
) : Comparable<MessageKey> {
    init {
        require(IDENTIFIER.matches(module)) { "a message module matches ${IDENTIFIER.pattern}, got \"$module\"" }
        require(IDENTIFIER.matches(name)) { "a message name matches ${IDENTIFIER.pattern}, got \"$name\"" }
    }

    /** The canonical key written to artifacts, generated APIs and audit records. */
    public val value: String get() = "$module.$name"

    override fun compareTo(other: MessageKey): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        /** Lower snake-case makes generated Kotlin/TypeScript names deterministic. */
        public val IDENTIFIER: Regex = Regex("[a-z][a-z0-9_]{0,127}")

        /** Parses exactly one module separator; dots cannot be hidden inside either identifier. */
        public fun parse(value: String): MessageKey {
            val separator = value.indexOf('.')
            require(separator > 0 && separator == value.lastIndexOf('.')) {
                "a message key is module.message_name, got \"$value\""
            }
            return MessageKey(value.substring(0, separator), value.substring(separator + 1))
        }
    }
}

/** A generated/manual binding may render only the catalog contract it was compiled against. */
public data class MessageContractRef(
    public val key: MessageKey,
    public val revision: Int,
    public val digest: ContractDigest,
) {
    init {
        require(revision >= 1) { "a message contract revision is at least one" }
    }
}
