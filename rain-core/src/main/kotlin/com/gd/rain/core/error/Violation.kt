package com.gd.rain.core.error

/** One step of a violation's location: a named member or an array index. */
public sealed interface PathStep {
    public data class Name(
        public val value: String,
    ) : PathStep

    public data class Index(
        public val value: Int,
    ) : PathStep {
        init {
            require(value >= 0) { "an array index is not negative, got $value" }
        }
    }
}

/** A location written the way a caller thinks of it: `path("items", 0, "email")`. */
public fun path(vararg steps: Any): List<PathStep> =
    steps.map { step ->
        when (step) {
            is Int -> PathStep.Index(step)
            is String -> PathStep.Name(step)
            is PathStep -> step
            else -> throw IllegalArgumentException("a path step is a name or an index, not ${step::class.simpleName}")
        }
    }

/** Whether a violation is about the value that was sent or about the state it met; it only orders violations. */
public enum class ViolationOrigin { INPUT, STATE }

/**
 * One thing wrong with a request or the state it met.
 *
 * A violation with a [path] points at a member of the request (rendered as an RFC 6901 pointer); one
 * without points at the request as a whole.
 */
public data class Violation(
    public val path: List<PathStep> = emptyList(),
    public val code: ErrorCode,
    public val message: String? = null,
    public val origin: ViolationOrigin = ViolationOrigin.INPUT,
    public val parameters: ViolationParameters = ViolationParameters.EMPTY,
) {
    init {
        require(message == null || isValidMessage(message)) {
            "a violation message is non-empty and at most $MAX_MESSAGE_BYTES UTF-8 bytes"
        }
    }

    /** The RFC 6901 JSON pointer of [path]; the empty string points at the whole document. */
    public val pointer: String get() = pointerOf(path)

    public companion object {
        public const val MAX_MESSAGE_BYTES: Int = 16 shl 10

        public fun general(
            code: ErrorCode,
            message: String? = null,
            parameters: ViolationParameters = ViolationParameters.EMPTY,
            origin: ViolationOrigin = ViolationOrigin.INPUT,
        ): Violation = Violation(code = code, message = message, origin = origin, parameters = parameters)

        public fun at(
            path: List<PathStep>,
            code: ErrorCode,
            message: String? = null,
            parameters: ViolationParameters = ViolationParameters.EMPTY,
            origin: ViolationOrigin = ViolationOrigin.INPUT,
        ): Violation = Violation(path = path, code = code, message = message, origin = origin, parameters = parameters)

        public fun isValidMessage(message: String): Boolean =
            message.isNotEmpty() && message.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES

        public fun pointerOf(path: List<PathStep>): String =
            path.joinToString(separator = "") { step ->
                when (step) {
                    is PathStep.Name -> "/" + step.value.replace("~", "~0").replace("/", "~1")
                    is PathStep.Index -> "/" + step.value
                }
            }

        /**
         * A total order, so two faults assembled in a different sequence render the same bytes: by path
         * (a prefix before its extensions, names before indexes at the same depth), then origin, code,
         * message and typed presentation parameters.
         */
        public val ORDER: Comparator<Violation> =
            Comparator { first, second ->
                val byPath = comparePaths(first.path, second.path)
                when {
                    byPath != 0 -> byPath
                    first.origin != second.origin -> first.origin.compareTo(second.origin)
                    first.code.value != second.code.value -> first.code.value.compareTo(second.code.value)
                    first.message != second.message -> (first.message ?: "").compareTo(second.message ?: "")
                    else -> first.parameters.compareTo(second.parameters)
                }
            }

        private fun comparePaths(
            left: List<PathStep>,
            right: List<PathStep>,
        ): Int {
            for (index in left.indices) {
                if (index >= right.size) return 1
                val step = compareSteps(left[index], right[index])
                if (step != 0) return step
            }
            return if (right.size > left.size) -1 else 0
        }

        private fun compareSteps(
            left: PathStep,
            right: PathStep,
        ): Int =
            when (left) {
                is PathStep.Name -> {
                    when (right) {
                        is PathStep.Name -> left.value.compareTo(right.value)
                        is PathStep.Index -> -1
                    }
                }

                is PathStep.Index -> {
                    when (right) {
                        is PathStep.Name -> 1
                        is PathStep.Index -> left.value.compareTo(right.value)
                    }
                }
            }
    }
}
