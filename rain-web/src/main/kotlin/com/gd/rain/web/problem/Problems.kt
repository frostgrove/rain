package com.gd.rain.web.problem

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.ErrorCodeRegistration
import com.gd.rain.core.error.ErrorCodeRegistry
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/** The codes rain-web itself declares. */
public object RainWebErrorCodes : ErrorCodeCatalog {
    override val owner: String = "rain-web"

    /** An unsafe request driven by a page this deployment does not allow, or a cross-origin request CORS refused. */
    public val CROSS_SITE: ErrorCode = ErrorCode.of("cross_site", "this request was made from another site")

    override val codes: List<ErrorCode> = listOf(CROSS_SITE)
}

/**
 * Problem format v1 — the body of every refusal rain renders, RFC 9457 `application/problem+json`.
 *
 * ```json
 * {"type":"about:blank","title":"Unprocessable Content","status":422,"detail":"the request is not valid",
 *  "code":"validation_failed","errors":[{"pointer":"/items/0/email","code":"required","message":"this field is required"}],
 *  "partial":true}
 * ```
 *
 * Rules of v1:
 * - `type` is `about:blank`, `title` is the status's reason phrase, `detail` is the fault's detail;
 * - `code` is the fault's registered error code;
 * - `errors` is present when the fault names violations: sorted by `Violation.ORDER`, at most
 *   [MAX_ERRORS], each `{pointer, code, message}` with the code's default message when the violation
 *   carries none;
 * - `partial: true` is present when the fault was partial or violations were cut, absent otherwise;
 * - `Retry-After` is sent only when the fault carries `retryAfter`, in whole seconds rounded up;
 * - an internal fault says only `the request failed`, names no violations, and its cause is logged;
 * - a fault carrying a code no catalog declares is rendered as `500 internal`, and the unregistered code
 *   is logged.
 *
 * Keys are written in exactly that order.
 */
public object ProblemFormat {
    public const val VERSION: Int = 1
    public const val MAX_ERRORS: Int = 100
    public const val TYPE: String = "about:blank"
    public const val INTERNAL_DETAIL: String = "the request failed"
    public const val CODE: String = "code"
    public const val ERRORS: String = "errors"
    public const val PARTIAL: String = "partial"
    public val MEDIA_TYPE: MediaType = MediaType.APPLICATION_PROBLEM_JSON
}

/** A problem ready for the wire: its status, the headers it adds and its serialized body. */
public class RenderedProblem(
    public val status: Int,
    public val headers: Map<String, String>,
    public val problem: ProblemDetail,
    public val body: ByteArray,
)

/**
 * An optional presentation-only adapter for a rendered problem. Implementations may replace human
 * text but must return a complete replacement RenderedProblem or null to keep the base result.
 * rain-web owns this SPI so a transport does not depend on any localization module.
 */
public fun interface ProblemLocalizer {
    public fun localize(
        fault: Fault,
        rendered: RenderedProblem,
        request: HttpServletRequest,
    ): RenderedProblem?
}

/**
 * Turns a [Fault] into problem format v1. Every refusal — the exception handler, the error
 * controller, the filters through [ProblemWriter] — renders through one renderer, so a refusal made
 * before MVC and one made by a controller are the same bytes.
 */
public class ProblemRenderer(
    private val registry: ErrorCodeRegistry,
    private val localizers: () -> List<ProblemLocalizer> = { emptyList() },
) {
    public fun render(
        fault: Fault,
        request: HttpServletRequest? = null,
    ): RenderedProblem {
        val unregistered = (listOf(fault.code) + fault.violations.map(Violation::code)).filterNot(registry::contains).distinct()
        if (unregistered.isNotEmpty()) {
            log
                .atError()
                .setMessage("a fault carries an error code no catalog declares; it is rendered as internal")
                .addKeyValue("codes", unregistered.joinToString(",") { it.value })
                .setCause(fault)
                .log()
            return internal(RainErrorCodes.INTERNAL)
        }
        if (fault.kind == FaultKind.INTERNAL) {
            log
                .atError()
                .setMessage("a request failed")
                .addKeyValue("code", fault.code.value)
                .setCause(fault.cause ?: fault)
                .log()
            return internal(fault.code)
        }

        val sorted = fault.violations.sortedWith(Violation.ORDER)
        val kept = sorted.take(ProblemFormat.MAX_ERRORS)
        val problem = base(fault.kind.status, fault.detail, fault.code)
        if (kept.isNotEmpty()) {
            problem.setProperty(
                ProblemFormat.ERRORS,
                kept.map { violation ->
                    linkedMapOf(
                        "pointer" to violation.pointer,
                        "code" to violation.code.value,
                        "message" to (violation.message ?: violation.code.defaultMessage),
                    )
                },
            )
        }
        if (fault.partial || kept.size < sorted.size) problem.setProperty(ProblemFormat.PARTIAL, true)

        val headers = fault.retryAfter?.let { mapOf(HttpHeaders.RETRY_AFTER to wholeSecondsUp(it).toString()) } ?: emptyMap()
        val rendered = RenderedProblem(fault.kind.status, headers, problem, serialize(problem))
        return if (request == null) {
            rendered
        } else {
            localize(fault, rendered, request)
        }
    }

    private fun localize(
        fault: Fault,
        rendered: RenderedProblem,
        request: HttpServletRequest,
    ): RenderedProblem {
        for (localizer in localizers()) {
            try {
                localizer.localize(fault, rendered, request)?.let { return it }
            } catch (failure: RuntimeException) {
                log
                    .atWarn()
                    .setMessage("a problem localizer failed; the safe base problem is used")
                    .addKeyValue("code", fault.code.value)
                    .setCause(failure)
                    .log()
            }
        }
        return rendered
    }

    private fun internal(code: ErrorCode): RenderedProblem {
        val problem = base(FaultKind.INTERNAL.status, ProblemFormat.INTERNAL_DETAIL, code)
        return RenderedProblem(FaultKind.INTERNAL.status, emptyMap(), problem, serialize(problem))
    }

    private fun base(
        status: Int,
        detail: String,
        code: ErrorCode,
    ): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail)
        problem.type = TYPE_URI
        problem.title = checkNotNull(HttpStatus.resolve(status)) { "status $status has no reason phrase" }.reasonPhrase
        problem.setProperty(ProblemFormat.CODE, code.value)
        return problem
    }

    public companion object {
        private val log = LoggerFactory.getLogger(ProblemRenderer::class.java)
        private val json: JsonMapper = JsonMapper.builder().build()
        private val TYPE_URI: URI = URI.create(ProblemFormat.TYPE)

        /** A duration in whole seconds, rounded up: 1.2 s is `2`, 2 s is `2`. */
        public fun wholeSecondsUp(duration: java.time.Duration): Long =
            if (duration.nano >
                0
            ) {
                Math.addExact(duration.seconds, 1L)
            } else {
                duration.seconds
            }

        /** The body bytes, keys in format v1 order, written by a mapper no application setting reaches. */
        public fun serialize(problem: ProblemDetail): ByteArray {
            val body = linkedMapOf<String, Any?>()
            body["type"] = checkNotNull(problem.type) { "a rain problem states its type" }.toString()
            body["title"] = problem.title
            body["status"] = problem.status
            body["detail"] = problem.detail
            problem.instance?.let { body["instance"] = it.toString() }
            val properties = problem.properties.orEmpty()
            listOf(ProblemFormat.CODE, ProblemFormat.ERRORS, ProblemFormat.PARTIAL).forEach { key ->
                if (properties.containsKey(key)) body[key] = properties[key]
            }
            properties.keys
                .filterNot { it in body }
                .sorted()
                .forEach { key -> body[key] = properties[key] }
            return json.writeValueAsBytes(body)
        }
    }
}

/**
 * Maps an HTTP status a framework chose to the [FaultKind] rendered for it — status table v1.
 *
 * Every entry maps a status to the kind that renders with that same status, so the status Spring chose
 * is the status the client sees. A status outside the table is not guessed at: it renders as
 * `500 unmapped_status` and is logged.
 */
public class StatusTable private constructor(
    private val kinds: Map<Int, FaultKind>,
) {
    public val statuses: Set<Int> get() = kinds.keys

    public fun kindOf(status: Int): FaultKind? = kinds[status]

    /** The fault for a refusal the framework made with [status]; [cause] is kept for the log. */
    public fun faultFor(
        status: Int,
        cause: Throwable?,
    ): Fault {
        val kind = kinds[status]
        if (kind == null) {
            log
                .atError()
                .setMessage("a status outside the status table is rendered as unmapped_status")
                .addKeyValue("status", status)
                .setCause(cause)
                .log()
            return Fault(FaultKind.INTERNAL, RainErrorCodes.UNMAPPED_STATUS, cause = cause)
        }
        return when (kind) {
            FaultKind.VALIDATION -> Fault(kind, violations = listOf(Violation.general(RainErrorCodes.VALIDATION_FAILED)), cause = cause)
            else -> Fault(kind, cause = cause)
        }
    }

    public companion object {
        private val log = LoggerFactory.getLogger(StatusTable::class.java)

        public val V1: StatusTable =
            of(
                mapOf(
                    400 to FaultKind.BAD_REQUEST,
                    401 to FaultKind.UNAUTHORIZED,
                    403 to FaultKind.FORBIDDEN,
                    404 to FaultKind.NOT_FOUND,
                    405 to FaultKind.METHOD_NOT_ALLOWED,
                    406 to FaultKind.NOT_ACCEPTABLE,
                    409 to FaultKind.CONFLICT,
                    413 to FaultKind.TOO_LARGE,
                    415 to FaultKind.UNSUPPORTED_MEDIA_TYPE,
                    422 to FaultKind.VALIDATION,
                    429 to FaultKind.TOO_MANY_REQUESTS,
                    500 to FaultKind.INTERNAL,
                    503 to FaultKind.RETRYABLE,
                ),
            )

        public fun of(entries: Map<Int, FaultKind>): StatusTable {
            val incoherent = entries.filter { (status, kind) -> kind.status != status }
            require(incoherent.isEmpty()) { "a status table maps each status to a kind rendered with that status: $incoherent" }
            return StatusTable(entries.toMap())
        }
    }
}

/**
 * Builds the [ErrorCodeRegistry] from every catalog, refusing with every problem at once: a catalog
 * with a blank owner, two catalogs with one owner, a catalog that cannot produce its codes (a
 * malformed code is refused when it is constructed), and a code declared more than once.
 */
public object ErrorCodeRegistrar {
    public fun register(catalogs: List<ErrorCodeCatalog>): ErrorCodeRegistry {
        val problems = mutableListOf<ConfigurationProblem>()
        val declared = linkedMapOf<String, MutableList<String>>()
        catalogs.groupBy { it.owner }.filterKeys(String::isNotBlank).filterValues { it.size > 1 }.toSortedMap().forEach { (owner, owners) ->
            problems +=
                ConfigurationProblem(
                    "error-catalog:$owner",
                    ProblemCode.CONTRADICTS,
                    "is the owner of ${owners.joinToString(", ") { it.javaClass.name }}",
                )
        }
        catalogs.forEach { catalog ->
            if (catalog.owner.isBlank()) {
                problems += ConfigurationProblem("error-catalog:${catalog.javaClass.name}", ProblemCode.INVALID, "declares no owner")
            }
            val codes =
                try {
                    catalog.codes
                } catch (malformed: IllegalArgumentException) {
                    problems +=
                        ConfigurationProblem(
                            "error-catalog:${catalog.owner.ifBlank { catalog.javaClass.name }}",
                            ProblemCode.INVALID,
                            "cannot declare its codes: ${malformed.message}",
                        )
                    emptyList()
                }
            codes.forEach { code -> declared.getOrPut(code.value) { mutableListOf() } += catalog.owner }
        }
        declared.filterValues { it.size > 1 }.toSortedMap().forEach { (value, owners) ->
            problems += ConfigurationProblem("error-code:$value", ProblemCode.CONTRADICTS, "is declared by ${owners.joinToString(", ")}")
        }
        if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)

        return when (val registration = ErrorCodeRegistry.of(catalogs)) {
            is ErrorCodeRegistration.Registered -> registration.registry

            is ErrorCodeRegistration.Refused -> throw ConfigurationProblemsException(
                registration.problems.map { ConfigurationProblem("error-codes", ProblemCode.CONTRADICTS, it) },
            )
        }
    }
}
