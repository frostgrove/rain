package com.gd.rain.web.problem

import com.gd.rain.core.error.Fault
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity

/**
 * Writes a problem straight onto a servlet response, for the refusals made before MVC — a filter's.
 * The bytes, status and headers are the ones the exception handler answers with for the same fault.
 */
public class ProblemWriter(
    private val renderer: ProblemRenderer,
) {
    public fun write(
        response: HttpServletResponse,
        fault: Fault,
    ) {
        val rendered = renderer.render(fault)
        if (response.isCommitted) {
            log
                .atWarn()
                .setMessage("a refusal could not be written because the response was already committed")
                .addKeyValue("status", rendered.status)
                .addKeyValue("code", fault.code.value)
                .log()
            return
        }
        // `resetBuffer`, not `reset`: headers set further out (security headers, the correlation id) stay.
        response.resetBuffer()
        response.status = rendered.status
        rendered.headers.forEach(response::setHeader)
        response.contentType = ProblemFormat.MEDIA_TYPE.toString()
        response.setContentLength(rendered.body.size)
        response.outputStream.write(rendered.body)
        response.flushBuffer()
    }

    public companion object {
        private val log = LoggerFactory.getLogger(ProblemWriter::class.java)

        /** The same problem as an MVC answer: the body bytes are written as they are, whatever the request accepts. */
        public fun entity(
            rendered: RenderedProblem,
            frameworkHeaders: HttpHeaders? = null,
        ): ResponseEntity<Any> {
            val headers = HttpHeaders()
            frameworkHeaders?.forEach { name, values ->
                if (!name.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true) &&
                    !name.equals(HttpHeaders.CONTENT_LENGTH, ignoreCase = true)
                ) {
                    headers.addAll(name, values)
                }
            }
            rendered.headers.forEach(headers::set)
            headers.contentType = ProblemFormat.MEDIA_TYPE
            return ResponseEntity<Any>(rendered.body, headers, HttpStatusCode.valueOf(rendered.status))
        }
    }
}
