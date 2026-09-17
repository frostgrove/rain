package com.gd.rain.i18n.web

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.Violation
import com.gd.rain.i18n.DeferredMessage
import com.gd.rain.web.problem.ProblemFormat
import com.gd.rain.web.problem.ProblemLocalizer
import com.gd.rain.web.problem.ProblemRenderer
import com.gd.rain.web.problem.RenderedProblem
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail

/** A declared, typed binding from an already registered problem code to a deferred catalog message. */
public fun interface FaultMessageBinding {
    public fun bind(fault: Fault): DeferredMessage
}

/** A declared, typed binding from an already registered violation code to a deferred catalog message. */
public fun interface ViolationMessageBinding {
    public fun bind(violation: Violation): DeferredMessage
}

/**
 * The complete allowlist for localizing existing RFC 9457 problem text. A binding owns its conversion
 * into a typed deferred message; neither Fault.detail nor Violation.message is ever an i18n template.
 */
public class LocalizedProblemMessages private constructor(
    private val faultMessages: Map<String, FaultMessageBinding>,
    private val violationMessages: Map<String, ViolationMessageBinding>,
) {
    internal fun faultMessage(fault: Fault): DeferredMessage? = faultMessages[fault.code.value]?.bind(fault)

    internal fun violationMessage(violation: Violation): DeferredMessage? = violationMessages[violation.code.value]?.bind(violation)

    public class Builder internal constructor() {
        private val faults: MutableMap<String, FaultMessageBinding> = linkedMapOf()
        private val violations: MutableMap<String, ViolationMessageBinding> = linkedMapOf()

        /** Declares the one safe localized detail binding for the specified code. */
        public fun fault(
            code: ErrorCode,
            binding: FaultMessageBinding,
        ) {
            require(faults.put(code.value, binding) == null) { "a localized fault mapping for ${code.value} is already declared" }
        }

        /** Declares the one safe localized violation-message binding for the specified code. */
        public fun violation(
            code: ErrorCode,
            binding: ViolationMessageBinding,
        ) {
            require(violations.put(code.value, binding) == null) { "a localized violation mapping for ${code.value} is already declared" }
        }

        internal fun build(): LocalizedProblemMessages = LocalizedProblemMessages(faults.toMap(), violations.toMap())
    }

    public companion object {
        public fun build(block: Builder.() -> Unit): LocalizedProblemMessages = Builder().apply(block).build()
    }
}

/**
 * A safe ProblemLocalizer implementation. It declines unmapped cases and any rendering failure, so
 * Rain's original byte-compatible English problem remains the fallback.
 */
public class I18nProblemLocalizer(
    private val messages: LocalizedProblemMessages,
) : ProblemLocalizer {
    override fun localize(
        fault: Fault,
        rendered: RenderedProblem,
        request: HttpServletRequest,
    ): RenderedProblem? {
        if (fault.kind == FaultKind.INTERNAL) return null
        val context = I18nRequestContext.of(request) ?: return null
        val kept = fault.violations.sortedWith(Violation.ORDER).take(ProblemFormat.MAX_ERRORS)
        val detail = messages.faultMessage(fault)
        val violations = kept.map(messages::violationMessage)
        if (detail == null && violations.all { it == null }) return null

        return try {
            val pending = listOfNotNull(detail) + violations.filterNotNull()
            val localized = context.renderAll(pending).iterator()
            val localizedDetail = if (detail == null) null else localized.next().text
            val localizedViolations = violations.map { message -> if (message == null) null else localized.next().text }
            val problem = copyOf(rendered.problem)
            if (localizedDetail != null) problem.detail = localizedDetail
            if (kept.isNotEmpty()) {
                problem.setProperty(
                    ProblemFormat.ERRORS,
                    kept.mapIndexed { index, violation ->
                        linkedMapOf(
                            "pointer" to violation.pointer,
                            "code" to violation.code.value,
                            "message" to (localizedViolations[index] ?: violation.message ?: violation.code.defaultMessage),
                        )
                    },
                )
            }
            RenderedProblem(rendered.status, rendered.headers, problem, ProblemRenderer.serialize(problem))
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun copyOf(source: ProblemDetail): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(source.status), source.detail).also { copy ->
            copy.type = source.type
            copy.title = source.title
            copy.instance = source.instance
            source.properties.orEmpty().forEach(copy::setProperty)
        }
}
