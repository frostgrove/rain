package com.gd.rain.web.filter

import com.gd.rain.boot.config.written
import com.gd.rain.core.error.Fault
import com.gd.rain.web.problem.ProblemWriter
import com.gd.rain.web.problem.RainWebErrorCodes
import com.gd.rain.web.route.RequestPrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.util.unit.DataSize
import org.springframework.web.cors.DefaultCorsProcessor
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.DispatcherServlet
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.Duration
import java.util.UUID

/**
 * The order of rain's servlet filters, lowest first. Every one runs before Spring Security's filter
 * chain (order −100), so every refusal below is a pre-security refusal.
 */
public object WebFilterOrder {
    public const val SECURITY_HEADERS: Int = Ordered.HIGHEST_PRECEDENCE + 5
    public const val REQUEST_LOG: Int = Ordered.HIGHEST_PRECEDENCE + 10
    public const val REQUEST_BUDGET: Int = Ordered.HIGHEST_PRECEDENCE + 20
    public const val PROBE_ONLY: Int = Ordered.HIGHEST_PRECEDENCE + 25
    public const val CORS: Int = Ordered.HIGHEST_PRECEDENCE + 30
    public const val BODY_LIMIT: Int = Ordered.HIGHEST_PRECEDENCE + 35
    public const val CROSS_SITE: Int = Ordered.HIGHEST_PRECEDENCE + 40
}

/**
 * Sets the configured security headers (`rain.web.security-headers`) on every response.
 *
 * It is the outermost rain filter so that refusals made before Spring Security — a CORS rejection, a
 * body over the limit, a cross-site write, a spent request budget, a worker's 404 — carry the headers
 * as well as answers do. Spring Security's own header writer covers only what passes through its filter
 * chain. The headers are set before the chain runs, and refusals reset only the body, never headers.
 */
public class SecurityHeadersFilter(
    headers: Map<String, String>,
) : OncePerRequestFilter() {
    private val values: Map<String, String> = headers.toMap()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        values.forEach(response::setHeader)
        filterChain.doFilter(request, response)
    }
}

/**
 * The correlation id rule: an inbound `X-Request-ID` is kept only when it is 1 to 64 characters of
 * `A-Z a-z 0-9 . _ : -`; anything else — a header is client input and lands in a log line — is replaced
 * by a random UUID. The id is echoed on the response.
 */
public object CorrelationId {
    public const val HEADER: String = "X-Request-ID"
    public const val PATTERN: String = "^[A-Za-z0-9._:-]{1,64}$"
    public const val MDC_KEY: String = "request_id"

    private val FORMAT = Regex(PATTERN)

    public fun accepts(value: String): Boolean = FORMAT.matches(value)

    public fun of(request: HttpServletRequest): String = request.getHeader(HEADER)?.takeIf(::accepts) ?: UUID.randomUUID().toString()
}

/**
 * One line per request, with the correlation id it is joined by, written after everything that could
 * change the status has run.
 *
 * Level: 5xx is ERROR, 4xx is WARN, a probe path ([quietPaths]) that answered below 400 is DEBUG, and
 * everything else is INFO. Neither the query string nor any header value is logged. The principal comes
 * from the application's [RequestPrincipal] bean, when there is one.
 */
public class RequestLogFilter(
    private val quietPaths: Set<String>,
    principal: () -> RequestPrincipal?,
) : OncePerRequestFilter() {
    private val principal: RequestPrincipal? by lazy(principal)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()
        val correlation = CorrelationId.of(request)
        response.setHeader(CorrelationId.HEADER, correlation)
        MDC.put(CorrelationId.MDC_KEY, correlation)
        var escaped: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (thrown: Throwable) {
            escaped = thrown
            throw thrown
        } finally {
            record(request, response, correlation, startedAt, escaped)
            MDC.remove(CorrelationId.MDC_KEY)
        }
    }

    private fun record(
        request: HttpServletRequest,
        response: HttpServletResponse,
        correlation: String,
        startedAt: Long,
        escaped: Throwable?,
    ) {
        val handled = request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE) as? Throwable
        val failure = escaped ?: handled
        val status = if (escaped != null) INTERNAL_STATUS else response.status
        val path = request.requestURI
        val line =
            log
                .atLevel(levelFor(request.mountedPath(), status))
                .setMessage(MESSAGE)
                .addKeyValue("request_id", correlation)
                .addKeyValue("method", request.method)
                .addKeyValue("path", path)
                .addKeyValue("status", status)
                .addKeyValue("took", Duration.ofNanos(System.nanoTime() - startedAt).written())
        principal?.of(request)?.let { line.addKeyValue("principal", it) }
        failure?.let { line.addKeyValue("error", it.message ?: it.javaClass.name) }
        line.log()
    }

    private fun levelFor(
        path: String,
        status: Int,
    ): Level =
        when {
            status >= INTERNAL_STATUS -> Level.ERROR
            status >= CLIENT_ERROR_STATUS -> Level.WARN
            path in quietPaths -> Level.DEBUG
            else -> Level.INFO
        }

    public companion object {
        public const val MESSAGE: String = "http request served"
        private const val INTERNAL_STATUS = 500
        private const val CLIENT_ERROR_STATUS = 400
        private val log = LoggerFactory.getLogger(RequestLogFilter::class.java)
    }
}

/**
 * Refuses a request body over `rain.web.body-limit` with `413 too_large`.
 *
 * Two arms: a `Content-Length` over the limit is refused before the handler runs; a body that announces
 * no length is counted while it is read, and the read that goes over raises the fault and marks the
 * request, so the exception handler answers 413 whatever the reader wrapped the fault in. While the container
 * parses multipart requests ([multipartParsedByContainer]) their bodies are not counted here: the container reads them
 * itself, bounded by `spring.servlet.multipart.*`, which `MultipartLimitsCheck` holds within the body limit. Without
 * multipart parsing they are counted like any body.
 */
public class BodyLimitFilter(
    limit: DataSize,
    private val writer: ProblemWriter,
    private val multipartParsedByContainer: Boolean,
) : OncePerRequestFilter() {
    private val limitBytes: Int = limitBytesOf(limit)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (multipartParsedByContainer && isMultipart(request)) {
            filterChain.doFilter(request, response)
            return
        }
        if (request.contentLengthLong > limitBytes) {
            writer.write(response, refusal(limitBytes))
            return
        }
        filterChain.doFilter(BoundedRequest(request, limitBytes), response)
    }

    private fun isMultipart(request: HttpServletRequest): Boolean =
        request.contentType
            ?.substringBefore('/')
            ?.trim()
            ?.equals(MULTIPART, ignoreCase = true) == true

    private class BoundedRequest(
        request: HttpServletRequest,
        private val limitBytes: Int,
    ) : HttpServletRequestWrapper(request) {
        private val counting: ServletInputStream by lazy { BoundedStream(super.getInputStream(), limitBytes, request) }

        override fun getInputStream(): ServletInputStream = counting

        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(counting, readerCharset()))

        /** The request's declared encoding, else the context's request encoding, else ISO-8859-1 as the Servlet specification states. */
        private fun readerCharset(): Charset =
            Charset.forName(characterEncoding ?: servletContext?.requestCharacterEncoding ?: Charsets.ISO_8859_1.name())
    }

    private class BoundedStream(
        private val delegate: ServletInputStream,
        private val limitBytes: Int,
        private val request: ServletRequest,
    ) : ServletInputStream() {
        private var read: Long = 0

        override fun read(): Int = delegate.read().also { if (it >= 0) charge(1) }

        override fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(into, offset, length).also { if (it > 0) charge(it.toLong()) }

        override fun isFinished(): Boolean = delegate.isFinished

        override fun isReady(): Boolean = delegate.isReady

        override fun setReadListener(listener: ReadListener?) = delegate.setReadListener(listener)

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()

        private fun charge(bytes: Long) {
            read += bytes
            if (read > limitBytes) {
                request.setAttribute(EXCEEDED_ATTRIBUTE, limitBytes)
                throw refusal(limitBytes)
            }
        }
    }

    public companion object {
        public const val EXCEEDED_ATTRIBUTE: String = "com.gd.rain.web.filter.BodyLimitFilter.exceeded"
        private const val MULTIPART = "multipart"

        /** The limit in bytes: positive and at most `Integer.MAX_VALUE`, which is what servlet containers count a body in. */
        public fun limitBytesOf(limit: DataSize): Int {
            require(limit.toBytes() > 0) { "a body limit is positive, got ${limit.toBytes()} bytes" }
            return Math.toIntExact(limit.toBytes())
        }

        /** The limit a request body went over while it was read, or null. */
        public fun exceededLimit(request: ServletRequest): Int? = request.getAttribute(EXCEEDED_ATTRIBUTE) as? Int

        public fun refusal(limitBytes: Int): Fault =
            Fault.tooLarge("the request body is larger than the $limitBytes bytes this service accepts")
    }
}

/**
 * Refuses an unsafe request a page on another site drove, with `403 cross_site`.
 *
 * A browser attaches cookies to whoever asks, so CORS hides the answer from a foreign page but not the
 * write. The rule, for every method but GET, HEAD and OPTIONS:
 * - a request with an `Origin` passes only when that origin is one of `rain.web.cors.allowed-origins`
 *   (or the list is `*`), compared ASCII case-insensitively;
 * - a request without `Origin` passes only when `Sec-Fetch-Site` is absent, `same-origin` or `none`.
 *
 * A request with neither header was driven by no page (a CLI, a server), so it borrows no ambient
 * cookie; every browser sends `Origin` on unsafe methods.
 */
public class CrossSiteFilter(
    allowedOrigins: List<String>,
    private val writer: ProblemWriter,
) : OncePerRequestFilter() {
    private val permitted: Set<String> = allowedOrigins.map { it.lowercase() }.toSet()
    private val anyOrigin: Boolean = ANY_ORIGIN in permitted

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isSafeMethod(request.method) || isSameSite(request)) {
            filterChain.doFilter(request, response)
            return
        }
        writer.write(response, Fault.forbidden(RainWebErrorCodes.CROSS_SITE))
    }

    private fun isSameSite(request: HttpServletRequest): Boolean {
        val origin = request.getHeader(HttpHeaders.ORIGIN)
        if (origin != null) return anyOrigin || origin.lowercase() in permitted
        return when (request.getHeader(FETCH_SITE)?.lowercase()) {
            null, SAME_ORIGIN, NONE -> true
            else -> false
        }
    }

    public companion object {
        public const val FETCH_SITE: String = "Sec-Fetch-Site"
        private const val SAME_ORIGIN = "same-origin"
        private const val NONE = "none"
        private const val ANY_ORIGIN = "*"
    }
}

/** Spring's CORS processing, with a rejection rendered as `403 cross_site` problem+json instead of a plain-text body. */
public class ProblemCorsProcessor(
    private val writer: ProblemWriter,
) : DefaultCorsProcessor() {
    override fun rejectRequest(response: ServerHttpResponse) {
        val servlet = (response as ServletServerHttpResponse).servletResponse
        // Writes the headers the processor already set (Vary) onto the servlet response; no body yet, so nothing is committed.
        response.flush()
        writer.write(servlet, Fault.forbidden(RainWebErrorCodes.CROSS_SITE))
    }
}

/**
 * In a process where the API role is not active, only the probes and the Actuator base path are
 * served; every other path is `404 not_found`, whatever controllers the classpath brings.
 *
 * An Actuator mounted at the root (`management.endpoints.web.base-path` empty or `/`) is not exempt.
 */
public class ProbeOnlyFilter(
    private val probePaths: Set<String>,
    actuatorBasePath: String,
    private val writer: ProblemWriter,
) : OncePerRequestFilter() {
    private val actuator: String? = actuatorBasePath.trimEnd('/').ifEmpty { null }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.mountedPath()
        if (path in probePaths || isActuator(path)) {
            filterChain.doFilter(request, response)
            return
        }
        writer.write(response, Fault.notFound())
    }

    private fun isActuator(path: String): Boolean = actuator != null && (path == actuator || path.startsWith("$actuator/"))
}
