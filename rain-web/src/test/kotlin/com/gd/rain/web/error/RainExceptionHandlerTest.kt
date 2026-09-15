package com.gd.rain.web.error

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.error.PathStep
import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.problem
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.problemCode
import com.gd.rain.web.webRunner
import jakarta.servlet.RequestDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController
import org.springframework.core.MethodParameter
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.validation.FieldError
import org.springframework.validation.MapBindingResult
import org.springframework.validation.ObjectError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.time.Duration

/** The handler's order of decisions, the error dispatch, and one set of bytes for both MVC and filters. */
class RainExceptionHandlerTest {
    @RestController
    class Failures {
        @GetMapping("/fault")
        fun fault(): String = throw Fault.retryable(message = "the upstream is restarting", retryAfter = Duration.ofMillis(1500))

        @GetMapping("/translated")
        fun translated(): String = throw IllegalStateException("translated")

        @GetMapping("/untranslated")
        fun untranslated(): String = throw UnsupportedOperationException("nobody translates this")

        @PostMapping("/invalid")
        fun invalid(): String {
            val result = MapBindingResult(mutableMapOf<String, Any>(), "form")
            result.addError(FieldError("form", "name", "size must be between 1 and 64"))
            result.addError(FieldError("form", "items[0].email", "must not be blank"))
            result.addError(ObjectError("form", "the dates are in the wrong order"))
            throw MethodArgumentNotValidException(MethodParameter(Failures::class.java.getMethod("invalid"), -1), result)
        }
    }

    @Order(1)
    class FirstTranslator : FaultTranslator {
        override fun translate(failure: Throwable): Fault? = if (failure is IllegalStateException) Fault.conflict() else null
    }

    @Order(2)
    class SecondTranslator : FaultTranslator {
        override fun translate(failure: Throwable): Fault? = if (failure is IllegalStateException) Fault.forbidden() else null
    }

    private val runner =
        webRunner()
            .withUserConfiguration(Failures::class.java)
            .withBean(SecondTranslator::class.java)
            .withBean(FirstTranslator::class.java)

    @Test
    fun `the first translator in order answers, and a failure nobody translates is internal`() {
        runner.run { context ->
            val mvc = mockMvcOf(context)
            val translated = mvc.perform(get("/translated")).andReturn().response
            val untranslated = mvc.perform(get("/untranslated")).andReturn().response

            assertThat(translated.status).isEqualTo(409)
            assertThat(translated.problemCode()).isEqualTo("conflict")
            assertThat(untranslated.status).isEqualTo(500)
            assertThat(untranslated.problem()["detail"].asString()).isEqualTo("the request failed")
        }
    }

    @Test
    fun `a failed validation is one violation per error, pointing at the field`() {
        runner.run { context ->
            val response = mockMvcOf(context).perform(post("/invalid")).andReturn().response
            val errors = response.problem()["errors"]

            assertThat(response.status).isEqualTo(422)
            assertThat(response.problemCode()).isEqualTo("validation_failed")
            assertThat((0 until errors.size()).map { errors[it]["pointer"].asString() to errors[it]["message"].asString() })
                .containsExactly(
                    "" to "the dates are in the wrong order",
                    "/items/0/email" to "must not be blank",
                    "/name" to "size must be between 1 and 64",
                )
            assertThat((0 until errors.size()).map { errors[it]["code"].asString() }).containsOnly("check")
        }
    }

    @Test
    fun `the problem writer and the exception handler answer the same bytes for the same fault`() {
        runner.run { context ->
            val advised = mockMvcOf(context).perform(get("/fault")).andReturn().response
            val written = MockHttpServletResponse()
            context
                .getBean(ProblemWriter::class.java)
                .write(written, Fault.retryable(message = "the upstream is restarting", retryAfter = Duration.ofMillis(1500)))

            assertThat(advised.status).isEqualTo(503).isEqualTo(written.status)
            assertThat(advised.contentAsByteArray).isEqualTo(written.contentAsByteArray)
            assertThat(advised.contentType).isEqualTo(written.contentType).isEqualTo("application/problem+json")
            assertThat(advised.getHeader("Retry-After")).isEqualTo("2").isEqualTo(written.getHeader("Retry-After"))
        }
    }

    @Test
    fun `the error dispatch renders through the same renderer and status table`() {
        runner.run { context ->
            val mvc = mockMvcOf(context)
            val notFound = mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)).andReturn().response
            val nameless = mvc.perform(get("/error")).andReturn().response
            val teapot = mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 418)).andReturn().response
            val escaped =
                mvc
                    .perform(
                        get("/error")
                            .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                            .requestAttr(RequestDispatcher.ERROR_EXCEPTION, Fault.conflict()),
                    ).andReturn()
                    .response

            assertThat(notFound.status to notFound.problemCode()).isEqualTo(404 to "not_found")
            assertThat(nameless.status to nameless.problemCode()).isEqualTo(500 to "internal")
            assertThat(teapot.status to teapot.problemCode()).isEqualTo(500 to "unmapped_status")
            assertThat(escaped.status to escaped.problemCode()).isEqualTo(409 to "conflict")
        }
    }

    @Test
    fun `rain's handler and error controller replace Boot's, even with Boot's problem details switched on`() {
        webRunner().withPropertyValues("spring.mvc.problemdetails.enabled=true").run { context ->
            assertThat(context.getBeansOfType(ResponseEntityExceptionHandler::class.java).values.single())
                .isInstanceOf(RainExceptionHandler::class.java)
            assertThat(context).hasSingleBean(RainErrorController::class.java).doesNotHaveBean(BasicErrorController::class.java)
        }
    }

    @Test
    fun `field paths follow Spring's property path grammar, and anything else is one name`() {
        assertThat(FieldPaths.parse("items[0].email")).containsExactly(PathStep.Name("items"), PathStep.Index(0), PathStep.Name("email"))
        assertThat(FieldPaths.parse("attributes['colour'].value"))
            .containsExactly(PathStep.Name("attributes"), PathStep.Name("colour"), PathStep.Name("value"))
        assertThat(FieldPaths.parse("matrix[1][2]")).containsExactly(PathStep.Name("matrix"), PathStep.Index(1), PathStep.Index(2))
        assertThat(FieldPaths.parse("labels[en]")).containsExactly(PathStep.Name("labels"), PathStep.Name("en"))
        listOf("a..b", "a[0", "[0]", "a.", "a]b", "a[0]b").forEach { malformed ->
            assertThat(FieldPaths.parse(malformed)).describedAs(malformed).containsExactly(PathStep.Name(malformed))
        }
    }
}
