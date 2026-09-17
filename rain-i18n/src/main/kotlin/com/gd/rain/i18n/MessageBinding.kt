package com.gd.rain.i18n

/**
 * Marks one compiler-generated binding factory with the exact catalog contract it constructs.
 *
 * The marker is binary-retained so the optional K2 usage plugin can distinguish a resolved
 * generated-binder invocation from a look-alike source token. It is not used by rendering or
 * contract validation; those paths always validate [MessageContractRef] against a snapshot.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class RainI18nGeneratedBinding(
    public val key: String,
    public val revision: Int,
    public val contractDigest: String,
)

/** One explicitly named deferred-message argument. */
public data class MessageArgument(
    public val name: String,
    public val value: MessageValue,
) {
    init {
        require(MessageKey.IDENTIFIER.matches(name)) { "an argument name matches ${MessageKey.IDENTIFIER.pattern}" }
    }
}

/** A bounded, duplicate-free argument collection. The API deliberately has no raw map overload. */
public data class MessageArguments private constructor(
    public val entries: List<MessageArgument>,
) {
    init {
        require(entries.map(MessageArgument::name).toSet().size == entries.size) { "a message argument is supplied at most once" }
    }

    public companion object {
        public fun of(vararg entries: MessageArgument): MessageArguments = MessageArguments(entries.toList())

        public fun build(block: Builder.() -> Unit): MessageArguments = Builder().apply(block).finish()
    }

    /** The low-level SDK builder; each method still constructs a closed [MessageValue]. */
    public class Builder internal constructor() {
        private val entries = mutableListOf<MessageArgument>()

        public fun value(
            name: String,
            value: MessageValue,
        ) {
            entries += MessageArgument(name, value)
        }

        public fun text(
            name: String,
            value: String,
        ) {
            value(name, MessageValue.Text(value))
        }

        public fun integer(
            name: String,
            value: Long,
        ) {
            value(name, MessageValue.IntegerValue(value))
        }

        public fun decimal(
            name: String,
            value: java.math.BigDecimal,
        ) {
            value(name, MessageValue.DecimalValue(value))
        }

        public fun nullValue(name: String) {
            value(name, MessageValue.Null)
        }

        internal fun finish(): MessageArguments = MessageArguments(entries.toList())
    }
}

/** One message intent that can cross a boundary without rendering a locale-specific string. */
public data class DeferredMessage internal constructor(
    public val contract: MessageContractRef,
    public val arguments: MessageArguments,
)

/** The complete diagnostics for an invalid typed bind. */
public class MessageBindingException(
    public val problems: List<String>,
) : IllegalArgumentException(problems.joinToString(prefix = "a message bind is invalid: ", separator = "; ")) {
    init {
        require(problems.isNotEmpty()) { "a binding exception names at least one problem" }
    }
}

/** A generated or hand-written typed binding that has already been tied to one snapshot contract. */
public class MessageDefinition<A> internal constructor(
    public val contract: MessageContractRef,
    private val binder: (A) -> MessageArguments,
    private val validator: (MessageContractRef, MessageArguments) -> DeferredMessage,
) {
    public fun bind(value: A): DeferredMessage = validator(contract, binder(value))
}

/** Creates a generated-style typed binding after verifying the provided contract against this snapshot. */
public fun <A> CatalogSnapshot.definition(
    contract: MessageContractRef,
    encode: (A) -> MessageArguments,
): MessageDefinition<A> {
    require(this.contract(contract.key) == contract) { "a message definition contract does not match this catalog snapshot" }
    return MessageDefinition(contract, encode, ::bind)
}

/** The complete manual escape hatch: the caller provides a closed typed argument list, never a raw map. */
public fun CatalogSnapshot.definition(contract: MessageContractRef): MessageDefinition<MessageArguments> = definition(contract) { it }

/** Binds a deferred message directly against the snapshot's exact contract. */
public fun CatalogSnapshot.bind(
    contract: MessageContractRef,
    arguments: MessageArguments,
): DeferredMessage {
    val record =
        message(contract.key)
            ?: throw MessageBindingException(listOf("key ${contract.key} is not declared by this catalog"))
    if (record.contract != contract) {
        throw MessageBindingException(listOf("key ${contract.key} does not match the supplied revision or contract digest"))
    }
    if (arguments.entries.size > limits.maxArguments) {
        throw MessageBindingException(listOf("${contract.key} supplies more than ${limits.maxArguments} arguments"))
    }
    val supplied = arguments.entries.associateBy(MessageArgument::name)
    val problems = mutableListOf<String>()
    record.spec.arguments.forEach { specification ->
        val argument = supplied[specification.name]
        when {
            argument == null && specification.required -> {
                problems += "${specification.name} is required"
            }

            argument == null -> {}

            argument.value == MessageValue.Null && !specification.nullable -> {
                problems += "${specification.name} is not nullable"
            }

            argument.value != MessageValue.Null && !matches(specification, argument.value) -> {
                problems += "${specification.name} is not a ${specification.type.name.lowercase()} value"
            }

            argument.value is MessageValue.EnumValue && argument.value.value !in specification.enumValues -> {
                problems += "${specification.name} is not one of ${specification.enumValues.sorted().joinToString(", ")}"
            }
        }
    }
    supplied.keys.filter { suppliedName -> record.spec.arguments.none { it.name == suppliedName } }.sorted().forEach { name ->
        problems += "$name is not declared"
    }
    if (problems.isNotEmpty()) throw MessageBindingException(problems)
    return DeferredMessage(contract, arguments)
}

private fun matches(
    specification: ArgumentSpec,
    value: MessageValue,
): Boolean =
    when (specification.type) {
        ArgumentType.TEXT -> value is MessageValue.Text
        ArgumentType.BOOLEAN -> value is MessageValue.BooleanValue
        ArgumentType.INTEGER -> value is MessageValue.IntegerValue
        ArgumentType.UNSIGNED_INTEGER -> value is MessageValue.UnsignedIntegerValue
        ArgumentType.BIG_INTEGER -> value is MessageValue.BigIntegerValue
        ArgumentType.DECIMAL -> value is MessageValue.DecimalValue
        ArgumentType.MONEY -> value is MessageValue.MoneyValue
        ArgumentType.DATE -> value is MessageValue.DateValue
        ArgumentType.INSTANT -> value is MessageValue.InstantValue
        ArgumentType.ENUM -> value is MessageValue.EnumValue
    }
