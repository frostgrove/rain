package com.gd.rain.web.filter

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.web.problem.ProblemWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How long this request still has. Published by [RequestBudgetFilter] as a request attribute and, for
 * code nowhere near the servlet request (a statement timeout, an outbound client), on the serving
 * thread through [current].
 */
public class RequestDeadline(
    private val startedAt: Long,
    budget: Duration,
    private val nanoClock: () -> Long,
) {
    private val budgetNanos: Long = budget.toNanos()
    private val overrun = AtomicBoolean(false)

    /** Set when the budget fired while the request was still being served. */
    public val isExpired: Boolean get() = overrun.get()

    public fun remaining(): Duration = Duration.ofNanos((startedAt + budgetNanos - nanoClock()).coerceAtLeast(0))

    internal fun expire() {
        overrun.set(true)
    }

    public companion object {
        public const val ATTRIBUTE: String = "com.gd.rain.web.filter.RequestDeadline"

        private val CURRENT = ThreadLocal<RequestDeadline?>()

        public fun of(request: ServletRequest): RequestDeadline? = request.getAttribute(ATTRIBUTE) as? RequestDeadline

        public fun current(): RequestDeadline? = CURRENT.get()

        internal fun bind(deadline: RequestDeadline) = CURRENT.set(deadline)

        internal fun unbind() = CURRENT.remove()
    }
}

/**
 * The timers of every request budget in this process: one daemon thread that only fires timers and
 * never runs a request's work. Cancelled timers leave the queue at once (`removeOnCancelPolicy`), so a
 * request that finished in time holds no memory for the rest of its budget.
 */
public class RequestBudgetTimer : AutoCloseable {
    private val executor =
        ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }

    public fun schedule(
        delay: Duration,
        action: Runnable,
    ): ScheduledFuture<*> = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS)

    /** Timers armed and not yet fired or cancelled. */
    public fun pending(): Int = executor.queue.size

    override fun close() {
        executor.shutdownNow()
    }

    public companion object {
        public const val THREAD_NAME: String = "rain-request-budget"
    }
}

/**
 * Every request gets a budget (`rain.web.request-budget`); a request that outlives it is answered
 * `503 deadline_exceeded`, never a 500.
 *
 * When the budget fires while the request is still served, the deadline is marked expired and the
 * serving thread is interrupted — blocking calls answer to the interrupt. What the interrupt causes
 * reaches the exception handler, which checks the deadline before anything else and answers 503; a
 * failure that escapes MVC altogether is answered here the same way. The timer and the filter's own
 * return hand the thread over under one lock, and an interrupt this filter delivered is cleared before
 * the thread serves anything else.
 *
 * A request that started asynchronous processing is governed by Spring MVC's async timeout from then
 * on; its timeout renders `503` through the status table.
 */
public class RequestBudgetFilter(
    private val budget: Duration,
    private val timer: RequestBudgetTimer,
    private val writer: ProblemWriter,
    private val nanoClock: () -> Long = System::nanoTime,
) : OncePerRequestFilter() {
    init {
        require(!budget.isZero && !budget.isNegative) { "a request budget is positive, got $budget" }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val deadline = RequestDeadline(nanoClock(), budget, nanoClock)
        request.setAttribute(RequestDeadline.ATTRIBUTE, deadline)
        RequestDeadline.bind(deadline)

        val serving = Thread.currentThread()
        val handoff = ReentrantLock()
        val returned = AtomicBoolean(false)
        val overrun =
            timer.schedule(budget) {
                handoff.withLock {
                    if (!returned.get()) {
                        deadline.expire()
                        serving.interrupt()
                    }
                }
            }

        var failure: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            handoff.withLock { returned.set(true) }
            overrun.cancel(false)
            if (deadline.isExpired) Thread.interrupted()
            RequestDeadline.unbind()
        }

        val escaped = failure ?: return
        if (deadline.isExpired && !response.isCommitted) {
            writer.write(response, TransportRefusal.deadlineExceeded())
            return
        }
        throw escaped
    }
}

/**
 * Refusals the transport decided while a request was served, which outrank whatever failure they
 * caused: a request whose budget fired is `503 deadline_exceeded`, and one whose body went over the
 * limit while being read is `413 too_large`.
 */
public object TransportRefusal {
    public fun of(request: ServletRequest): Fault? {
        if (RequestDeadline.of(request)?.isExpired == true) return deadlineExceeded()
        BodyLimitFilter.exceededLimit(request)?.let { return BodyLimitFilter.refusal(it) }
        return null
    }

    public fun deadlineExceeded(): Fault = Fault(FaultKind.RETRYABLE, RainErrorCodes.DEADLINE_EXCEEDED)
}
