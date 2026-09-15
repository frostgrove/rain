package com.gd.rain.web.error

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.error.PathStep
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.web.filter.TransportRefusal
import com.gd.rain.web.problem.ProblemRenderer
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.problem.StatusTable
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindingResult
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Every failure that leaves a controller, rendered as problem format v1.
 *
 * Before anything else, a refusal the transport already decided wins: a request whose budget fired is
 * `503 deadline_exceeded` and one whose body went over the limit is `413 too_large`, whatever the
 * interrupt or the reader turned into. Then, in order:
 * 1. a [Fault] renders as it is;
 * 2. `MethodArgumentNotValidException` is a validation fault with one violation per field error;
 * 3. Spring's own exceptions are rendered with the status Spring chose, through the [StatusTable];
 * 4. anything else is offered to every [FaultTranslator] bean in order, and the first answer wins;
 * 5. otherwise the failure is internal.
 *
 * Extending `ResponseEntityExceptionHandler` makes Boot's own problem-details handler back off.
 */
@RestControllerAdvice
public class RainExceptionHandler(
    private val renderer: ProblemRenderer,
    private val statuses: StatusTable,
    translators: ObjectProvider<FaultTranslator>,
) : ResponseEntityExceptionHandler() {
    private val translators: List<FaultTranslator> by lazy { translators.orderedStream().toList() }

    @ExceptionHandler(Fault::class)
    public fun handleFault(
        fault: Fault,
        request: WebRequest,
    ): ResponseEntity<Any> = respond(transportRefusal(request) ?: fault)

    @ExceptionHandler(Exception::class)
    public fun handleUnmapped(
        failure: Exception,
        request: WebRequest,
    ): ResponseEntity<Any> = respond(transportRefusal(request) ?: translate(failure))

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = respond(transportRefusal(request) ?: FieldViolations.faultOf(ex.bindingResult, ex))

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if ((request as? ServletWebRequest)?.response?.isCommitted == true) return null
        return respond(transportRefusal(request) ?: statuses.faultFor(statusCode.value(), ex), headers)
    }

    private fun translate(failure: Exception): Fault {
        for (translator in translators) {
            translator.translate(failure)?.let { return it }
        }
        return Fault(FaultKind.INTERNAL, cause = failure)
    }

    private fun transportRefusal(request: WebRequest): Fault? = (request as? ServletWebRequest)?.request?.let(TransportRefusal::of)

    private fun respond(
        fault: Fault,
        frameworkHeaders: HttpHeaders? = null,
    ): ResponseEntity<Any> = ProblemWriter.entity(renderer.render(fault), frameworkHeaders)
}

/**
 * The error dispatch (`server.error.path`, `/error` by Boot's declaration): what the container refused
 * or what escaped a filter, rendered through the same renderer and status table. A dispatch that names
 * no status is internal. Declaring an `ErrorController` makes Boot's `BasicErrorController` back off.
 */
@RestController
public class RainErrorController(
    private val renderer: ProblemRenderer,
    private val statuses: StatusTable,
) : ErrorController {
    @RequestMapping("\${server.error.path:/error}")
    public fun error(request: HttpServletRequest): ResponseEntity<Any> {
        val escaped = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? Throwable
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: FaultKind.INTERNAL.status
        val fault = TransportRefusal.of(request) ?: escaped as? Fault ?: statuses.faultFor(status, escaped)
        return ProblemWriter.entity(renderer.render(fault))
    }
}

/**
 * Bean validation failures as violations: one per field error, pointing at the field, plus one general
 * violation per object error, each with code `check` and the constraint's message.
 */
public object FieldViolations {
    public fun faultOf(
        result: BindingResult,
        cause: Throwable,
    ): Fault {
        val violations =
            result.fieldErrors.map { Violation.at(FieldPaths.parse(it.field), RainErrorCodes.CHECK, usable(it.defaultMessage)) } +
                result.globalErrors.map { Violation.general(RainErrorCodes.CHECK, usable(it.defaultMessage)) }
        val named = violations.ifEmpty { listOf(Violation.general(RainErrorCodes.VALIDATION_FAILED)) }
        return Fault(FaultKind.VALIDATION, violations = named, cause = cause)
    }

    private fun usable(message: String?): String? = message?.takeIf(Violation::isValidMessage)
}

/**
 * Spring's property path (`items[0].email`, `attributes['key']`) as violation path steps: a name per
 * dotted member, an index per all-digit bracket, a name per other bracket with its quotes removed. A
 * field that does not follow that grammar is one name, spelled exactly as Spring reported it.
 */
public object FieldPaths {
    public fun parse(field: String): List<PathStep> = steps(field) ?: listOf(PathStep.Name(field))

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun steps(field: String): List<PathStep>? {
        if (field.isEmpty()) return null
        val steps = mutableListOf<PathStep>()
        val name = StringBuilder()
        var afterBracket = false
        var index = 0
        while (index < field.length) {
            when (val char = field[index]) {
                '.' -> {
                    if (name.isEmpty() && !afterBracket) return null
                    if (name.isNotEmpty()) steps += PathStep.Name(name.toString())
                    name.clear()
                    afterBracket = false
                    if (index == field.length - 1) return null
                }

                '[' -> {
                    if (name.isEmpty() && !afterBracket) return null
                    if (name.isNotEmpty()) steps += PathStep.Name(name.toString())
                    name.clear()
                    val close = field.indexOf(']', index + 1)
                    if (close < 0) return null
                    steps += bracketed(field.substring(index + 1, close)) ?: return null
                    if (close + 1 < field.length && field[close + 1] != '.' && field[close + 1] != '[') return null
                    index = close
                    afterBracket = true
                }

                ']' -> {
                    return null
                }

                else -> {
                    if (afterBracket) return null
                    name.append(char)
                }
            }
            index++
        }
        if (name.isNotEmpty()) steps += PathStep.Name(name.toString())
        return steps
    }

    private fun bracketed(key: String): PathStep? {
        if (key.isEmpty()) return null
        val quoted = key.length >= 2 && (key.first() == '\'' || key.first() == '"') && key.last() == key.first()
        if (quoted) return PathStep.Name(key.substring(1, key.length - 1))
        val position = if (key.all { it in '0'..'9' }) key.toIntOrNull() else null
        return if (position != null) PathStep.Index(position) else PathStep.Name(key)
    }
}
