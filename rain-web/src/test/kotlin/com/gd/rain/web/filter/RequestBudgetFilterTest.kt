package com.gd.rain.web.filter

import com.gd.rain.web.problemCode
import com.gd.rain.web.problemWriter
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The budget filter on its own: the cut, the handover of the thread, and the published deadline. */
class RequestBudgetFilterTest {
    private val timer = RequestBudgetTimer()

    @AfterEach
    fun stop() {
        timer.close()
    }

    @Test
    fun `a failure that escapes after the budget fired is answered 503 deadline_exceeded`() {
        val response = MockHttpServletResponse()
        val stopped = AtomicReference<Throwable?>()

        budgeted(Duration.ofMillis(150)).doFilter(MockHttpServletRequest("GET", "/slow"), response, blocking(stopped))

        assertThat(stopped.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(response.status).isEqualTo(503)
        assertThat(response.problemCode()).isEqualTo("deadline_exceeded")
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `a handler that finished inside its budget is left alone and leaves no timer`() {
        val chain = MockFilterChain()
        val request = MockHttpServletRequest("GET", "/fast")

        budgeted(Duration.ofSeconds(30)).doFilter(request, MockHttpServletResponse(), chain)

        assertThat(chain.request).isSameAs(request)
        assertThat(RequestDeadline.of(request)?.isExpired).isFalse()
        assertThat(timer.pending()).isZero()
    }

    @Test
    fun `a failure with time left on the clock is not turned into a 503`() {
        val failure = IllegalStateException("the query is broken")

        assertThatThrownBy {
            budgeted(Duration.ofSeconds(30)).doFilter(
                MockHttpServletRequest("GET", "/broken"),
                MockHttpServletResponse(),
                FilterChain {
                    _,
                    _,
                    ->
                    throw failure
                },
            )
        }.isSameAs(failure)
    }

    @Test
    fun `an interrupt that arrives as the handler returns is not left on the thread`() {
        val filter = budgeted(Duration.ofMillis(1))

        repeat(200) {
            filter.doFilter(MockHttpServletRequest("GET", "/racy"), MockHttpServletResponse(), MockFilterChain())
            assertThat(
                Thread.currentThread().isInterrupted,
            ).describedAs("the timer's interrupt outlived the request that armed it").isFalse()
        }
    }

    @Test
    fun `an interrupt the budget did not deliver is not cleared`() {
        Thread.currentThread().interrupt()

        budgeted(Duration.ofSeconds(30)).doFilter(MockHttpServletRequest("GET", "/fast"), MockHttpServletResponse(), MockFilterChain())

        assertThat(Thread.interrupted()).isTrue()
    }

    @Test
    fun `the deadline is published on the request and on the serving thread for as long as the request runs`() {
        val request = MockHttpServletRequest("GET", "/slow")
        val seen = AtomicReference<Duration?>()

        budgeted(Duration.ofSeconds(30)).doFilter(
            request,
            MockHttpServletResponse(),
            FilterChain { _, _ -> seen.set(RequestDeadline.current()?.remaining()) },
        )

        assertThat(seen.get()).isNotNull().isBetween(Duration.ofSeconds(20), Duration.ofSeconds(30))
        assertThat(RequestDeadline.of(request)).isNotNull()
        assertThat(RequestDeadline.current()).describedAs("the deadline outlived the request").isNull()
    }

    private fun budgeted(budget: Duration) = RequestBudgetFilter(budget, timer, problemWriter())

    private fun blocking(stopped: AtomicReference<Throwable?>) =
        FilterChain { _, _ ->
            try {
                check(CountDownLatch(1).await(5, TimeUnit.SECONDS)) { "the budget never fired" }
            } catch (interrupted: InterruptedException) {
                stopped.set(interrupted)
                throw interrupted
            }
        }
}
