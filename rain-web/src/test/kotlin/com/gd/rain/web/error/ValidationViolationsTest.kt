package com.gd.rain.web.error

import com.gd.rain.core.error.PathStep
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.ViolationParameters
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Negative
import jakarta.validation.constraints.NegativeOrZero
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import jakarta.validation.metadata.ConstraintDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.validation.FieldError
import org.springframework.validation.MapBindingResult
import org.springframework.validation.ObjectError
import java.math.BigDecimal

class ValidationViolationsTest {
    private class Constraints {
        @field:Size(min = 1, max = 3)
        val size: String = ""

        @field:Min(2)
        val min: Long = 0

        @field:Max(8)
        val max: Long = 0

        @field:DecimalMin(value = "1.25", inclusive = false)
        val decimalMin: BigDecimal = BigDecimal.ZERO

        @field:DecimalMax(value = "9.75", inclusive = true)
        val decimalMax: BigDecimal = BigDecimal.ZERO

        @field:Digits(integer = 4, fraction = 2)
        val digits: BigDecimal = BigDecimal.ZERO

        @field:Positive
        val positive: Long = 0

        @field:PositiveOrZero
        val positiveOrZero: Long = 0

        @field:Negative
        val negative: Long = 0

        @field:NegativeOrZero
        val negativeOrZero: Long = 0

        @field:NotNull
        val notNull: String? = null
    }

    @Test
    fun `standard mapping covers binding, constraint families, size direction, and safe messages`() {
        val maximum = ViolationParameters.build { integer("max", 3) }
        val cases =
            listOf(
                failure("Unknown", bindingFailure = true) to RainErrorCodes.INVALID_FORMAT,
                failure("jakarta.validation.constraints.NotNull") to RainErrorCodes.REQUIRED,
                failure("Email") to RainErrorCodes.INVALID_FORMAT,
                failure("Size", parameters = maximum, valueSize = 4) to RainErrorCodes.TOO_LONG,
                failure("Size", parameters = maximum, valueSize = 3) to RainErrorCodes.OUT_OF_RANGE,
                failure("Size", parameters = maximum) to RainErrorCodes.OUT_OF_RANGE,
                failure("Size", valueSize = 4) to RainErrorCodes.OUT_OF_RANGE,
                failure("Min") to RainErrorCodes.OUT_OF_RANGE,
                failure(null) to RainErrorCodes.CHECK,
            )

        cases.forEach { (failure, expected) ->
            assertThat(StandardValidationViolationMapper.map(failure).code).isEqualTo(expected)
        }

        assertThat(StandardValidationViolationMapper.map(failure("NotBlank", message = "required")).message).isEqualTo("required")
        assertThat(StandardValidationViolationMapper.map(failure("NotEmpty", message = "x".repeat(16_385))).message).isNull()
    }

    @Test
    fun `validation failure owns its path and refuses impossible sizes`() {
        val source = mutableListOf<PathStep>(PathStep.Name("value"))
        val failure = failure("NotNull", path = source)
        source += PathStep.Index(0)

        assertThat(failure.path).containsExactly(PathStep.Name("value"))
        assertThatThrownBy { failure("Size", valueSize = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `standard constraint descriptors expose only bounded localization facts`() {
        val size = described("size", mapOf("min" to 1, "max" to 3), listOf(1, 2, 3, 4))
        val min = described("min", mapOf("value" to 2L), mapOf("a" to 1))
        val max = described("max", mapOf("value" to 8), intArrayOf(1, 2))
        val decimalMin = described("decimalMin", mapOf("value" to "1.25", "inclusive" to false), Any())
        val decimalMax = described("decimalMax", mapOf("value" to "9.75", "inclusive" to true), null)
        val digits = described("digits", mapOf("integer" to 4, "fraction" to 2), "12.34")
        val positive = described("positive", emptyMap(), "1")
        val positiveOrZero = described("positiveOrZero", emptyMap(), "0")
        val negative = described("negative", emptyMap(), "-1")
        val negativeOrZero = described("negativeOrZero", emptyMap(), "0")
        val notNull = described("notNull", emptyMap(), "present")

        assertThat(size.valueSize).isEqualTo(4)
        assertThat(size.parameters.integer("min")).isEqualTo(1)
        assertThat(size.parameters.integer("max")).isEqualTo(3)
        assertThat(min.valueSize).isEqualTo(1)
        assertThat(min.parameters.integer("min")).isEqualTo(2)
        assertThat(max.valueSize).isEqualTo(2)
        assertThat(max.parameters.integer("max")).isEqualTo(8)
        assertThat(decimalMin.valueSize).isNull()
        assertThat(decimalMin.parameters.decimal("min")).isEqualByComparingTo("1.25")
        assertThat(decimalMin.parameters.boolean("inclusive")).isFalse()
        assertThat(decimalMax.valueSize).isNull()
        assertThat(decimalMax.parameters.decimal("max")).isEqualByComparingTo("9.75")
        assertThat(decimalMax.parameters.boolean("inclusive")).isTrue()
        assertThat(digits.parameters.integer("integer")).isEqualTo(4)
        assertThat(digits.parameters.integer("fraction")).isEqualTo(2)
        assertThat(positive.parameters.integer("min")).isZero()
        assertThat(positive.parameters.boolean("inclusive")).isFalse()
        assertThat(positiveOrZero.parameters.boolean("inclusive")).isTrue()
        assertThat(negative.parameters.integer("max")).isZero()
        assertThat(negative.parameters.boolean("inclusive")).isFalse()
        assertThat(negativeOrZero.parameters.boolean("inclusive")).isTrue()
        assertThat(notNull.parameters).isEqualTo(ViolationParameters.EMPTY)
    }

    @Test
    fun `malformed or unsupported descriptor attributes are ignored`() {
        val malformedDecimal = described("decimalMin", mapOf("value" to "not-a-number", "inclusive" to "false"), "value")
        val wrongNumbers = described("size", mapOf("min" to "one", "max" to Any()), "value")
        val oversizedDecimal = described("decimalMax", mapOf("value" to "1e1000", "inclusive" to true), "value")

        assertThat(malformedDecimal.parameters).isEqualTo(ViolationParameters.EMPTY)
        assertThat(wrongNumbers.parameters).isEqualTo(ViolationParameters.EMPTY)
        assertThat(oversizedDecimal.parameters).isEqualTo(ViolationParameters.EMPTY)
    }

    @Test
    fun `plain Spring errors fall back to codes without retaining rejected values`() {
        val coded = FieldError("form", "nested.value", Any(), true, arrayOf("Email.form.nested.value"), emptyArray(), "bad")
        val uncoded = ObjectError("form", null, null, "global")

        val fieldFailure = ValidationFailures.of(coded)
        val globalFailure = ValidationFailures.of(uncoded)

        assertThat(fieldFailure.path).containsExactly(PathStep.Name("nested"), PathStep.Name("value"))
        assertThat(fieldFailure.constraint).isEqualTo("Email")
        assertThat(fieldFailure.bindingFailure).isTrue()
        assertThat(fieldFailure.valueSize).isNull()
        assertThat(fieldFailure.parameters).isEqualTo(ViolationParameters.EMPTY)
        assertThat(globalFailure.path).isEmpty()
        assertThat(globalFailure.constraint).isNull()
        assertThat(globalFailure.bindingFailure).isFalse()
        assertThat(globalFailure.message).isEqualTo("global")
    }

    @Test
    fun `a broken targeted mapper cannot suppress the next mapping decision`() {
        val result = MapBindingResult(mutableMapOf<String, Any>(), "form")
        result.addError(FieldError("form", "value", "bad", false, arrayOf("Email.form.value"), emptyArray(), "invalid"))
        val broken = ValidationViolationMapper { throw IllegalStateException("broken mapper") }
        val working =
            ValidationViolationMapper { failure ->
                Violation.at(failure.path, RainErrorCodes.INVALID_ENUM)
            }

        val fault = FieldViolations.faultOf(result, IllegalArgumentException("invalid"), listOf(broken, working))

        assertThat(fault.violations.single().code).isEqualTo(RainErrorCodes.INVALID_ENUM)
    }

    private fun failure(
        constraint: String?,
        parameters: ViolationParameters = ViolationParameters.EMPTY,
        message: String? = null,
        bindingFailure: Boolean = false,
        valueSize: Long? = null,
        path: List<PathStep> = listOf(PathStep.Name("value")),
    ): ValidationFailure = ValidationFailure(path, constraint, parameters, message, bindingFailure, valueSize)

    private fun described(
        field: String,
        attributes: Map<String, Any>,
        rejectedValue: Any?,
    ): ValidationFailure {
        val annotation =
            Constraints::class.java
                .getDeclaredField(field)
                .annotations
                .single()
        val descriptor = mockk<ConstraintDescriptor<Annotation>>()
        every { descriptor.annotation } returns annotation
        every { descriptor.attributes } returns attributes
        val constraint = mockk<ConstraintViolation<Any>>()
        every { constraint.constraintDescriptor } returns descriptor
        val error = FieldError("form", "value", rejectedValue, false, null, null, "invalid")
        error.wrap(constraint)
        return ValidationFailures.of(error)
    }
}
