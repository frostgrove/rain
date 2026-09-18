package com.gd.rain.web.error

import com.gd.rain.core.error.PathStep
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.ViolationParameters
import jakarta.validation.ConstraintViolation
import jakarta.validation.metadata.ConstraintDescriptor
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError
import java.math.BigDecimal
import java.lang.reflect.Array as ReflectArray

/**
 * The safe information a Bean Validation refusal offers to a targeted mapper. The rejected value is
 * deliberately absent; only its collection/string size is retained when that helps classify `@Size`.
 */
public class ValidationFailure(
    path: List<PathStep>,
    public val constraint: String?,
    public val parameters: ViolationParameters,
    public val message: String?,
    public val bindingFailure: Boolean,
    public val valueSize: Long?,
) {
    public val path: List<PathStep> = path.toList()

    init {
        require(valueSize == null || valueSize >= 0) { "a rejected value size is non-negative" }
    }
}

/**
 * Replaces one Bean Validation decision without replacing Rain's exception handler. Mappers are
 * asked in Spring order; the first answer wins and the standard mapper is the final fallback.
 */
public fun interface ValidationViolationMapper {
    public fun map(failure: ValidationFailure): Violation?
}

/** Rain's deterministic mapping for standard Jakarta constraints and binding failures. */
public object StandardValidationViolationMapper : ValidationViolationMapper {
    override fun map(failure: ValidationFailure): Violation {
        val simpleName = failure.constraint?.substringAfterLast('.')
        val code =
            when {
                failure.bindingFailure -> RainErrorCodes.INVALID_FORMAT
                simpleName in REQUIRED -> RainErrorCodes.REQUIRED
                simpleName in INVALID_FORMAT -> RainErrorCodes.INVALID_FORMAT
                simpleName == "Size" && tooLong(failure) -> RainErrorCodes.TOO_LONG
                simpleName in OUT_OF_RANGE -> RainErrorCodes.OUT_OF_RANGE
                else -> RainErrorCodes.CHECK
            }
        return Violation.at(failure.path, code, usable(failure.message), failure.parameters)
    }

    private fun tooLong(failure: ValidationFailure): Boolean {
        val size = failure.valueSize ?: return false
        val maximum = failure.parameters.integer("max") ?: return false
        return size > maximum
    }

    private fun usable(message: String?): String? = message?.takeIf(Violation::isValidMessage)

    private val REQUIRED: Set<String> = setOf("NotNull", "NotBlank", "NotEmpty")
    private val INVALID_FORMAT: Set<String> = setOf("Email", "Pattern", "Digits")
    private val OUT_OF_RANGE: Set<String> =
        setOf(
            "Size",
            "Min",
            "Max",
            "DecimalMin",
            "DecimalMax",
            "Positive",
            "PositiveOrZero",
            "Negative",
            "NegativeOrZero",
            "Past",
            "PastOrPresent",
            "Future",
            "FutureOrPresent",
        )
}

internal object ValidationFailures {
    fun of(error: ObjectError): ValidationFailure {
        val descriptor = descriptorOf(error)
        val constraint =
            descriptor?.annotation?.annotationClass?.qualifiedName
                ?: error.codes?.firstOrNull()?.substringBefore('.')
        return ValidationFailure(
            path = (error as? FieldError)?.field?.let(FieldPaths::parse).orEmpty(),
            constraint = constraint,
            parameters = parametersOf(constraint?.substringAfterLast('.'), descriptor),
            message = error.defaultMessage,
            bindingFailure = (error as? FieldError)?.isBindingFailure == true,
            valueSize = (error as? FieldError)?.rejectedValue?.let(::sizeOf),
        )
    }

    private fun descriptorOf(error: ObjectError): ConstraintDescriptor<*>? =
        try {
            if (error.contains(ConstraintViolation::class.java)) {
                error.unwrap(ConstraintViolation::class.java).constraintDescriptor
            } else {
                null
            }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parametersOf(
        constraint: String?,
        descriptor: ConstraintDescriptor<*>?,
    ): ViolationParameters {
        if (descriptor == null) return ViolationParameters.EMPTY
        val attributes = descriptor.attributes
        return try {
            ViolationParameters.build {
                when (constraint) {
                    "Size" -> {
                        attributes.long("min")?.let { integer("min", it) }
                        attributes.long("max")?.let { integer("max", it) }
                    }

                    "Min" -> {
                        attributes.long("value")?.let { integer("min", it) }
                    }

                    "Max" -> {
                        attributes.long("value")?.let { integer("max", it) }
                    }

                    "DecimalMin" -> {
                        attributes.decimal("value")?.let { decimal("min", it) }
                        attributes.boolean("inclusive")?.let { boolean("inclusive", it) }
                    }

                    "DecimalMax" -> {
                        attributes.decimal("value")?.let { decimal("max", it) }
                        attributes.boolean("inclusive")?.let { boolean("inclusive", it) }
                    }

                    "Digits" -> {
                        attributes.long("integer")?.let { integer("integer", it) }
                        attributes.long("fraction")?.let { integer("fraction", it) }
                    }

                    "Positive" -> {
                        integer("min", 0)
                        boolean("inclusive", false)
                    }

                    "PositiveOrZero" -> {
                        integer("min", 0)
                        boolean("inclusive", true)
                    }

                    "Negative" -> {
                        integer("max", 0)
                        boolean("inclusive", false)
                    }

                    "NegativeOrZero" -> {
                        integer("max", 0)
                        boolean("inclusive", true)
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            ViolationParameters.EMPTY
        }
    }

    private fun Map<String, Any>.long(name: String): Long? = (this[name] as? Number)?.toLong()

    private fun Map<String, Any>.boolean(name: String): Boolean? = this[name] as? Boolean

    private fun Map<String, Any>.decimal(name: String): BigDecimal? =
        (this[name] as? String)?.let {
            try {
                BigDecimal(it)
            } catch (_: NumberFormatException) {
                null
            }
        }

    private fun sizeOf(value: Any): Long? =
        when (value) {
            is CharSequence -> value.length.toLong()
            is Collection<*> -> value.size.toLong()
            is Map<*, *> -> value.size.toLong()
            else -> if (value.javaClass.isArray) ReflectArray.getLength(value).toLong() else null
        }
}
