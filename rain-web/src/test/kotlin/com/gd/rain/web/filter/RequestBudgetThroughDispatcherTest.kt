package com.gd.rain.web.filter

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.problem
import com.gd.rain.web.webRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gap 30: the budget's 503 is reachable through a real DispatcherServlet. The handler waits on a latch
 * nobody releases; the budget fires, the interrupt reaches the exception handler, and the handler
 * answers 503 `deadline_exceeded` — before any translator or any fault the handler raised instead.
 */
class RequestBudgetThroughDispatcherTest {
    @RestController
    class Stuck {
        private val neverReleased = CountDownLatch(1)

        @GetMapping("/stuck")
        fun stuck(): String {
            check(neverReleased.await(20, TimeUnit.SECONDS)) { "the request budget never fired" }
            return "released"
        }

        @GetMapping("/stuck-then-conflict")
        fun stuckThenConflict(): String {
            try {
                check(neverReleased.await(20, TimeUnit.SECONDS)) { "the request budget never fired" }
            } catch (_: InterruptedException) {
                throw Fault.conflict()
            }
            return "released"
        }
    }

    private val runner =
        webRunner()
            .withUserConfiguration(Stuck::class.java)
            .withPropertyValues("rain.web.request-budget=200ms")

    @Test
    fun `a handler that outlives its budget is answered 503 deadline_exceeded in problem+json`() {
        runner.run { context ->
            val result = mockMvcOf(context).perform(get("/stuck")).andReturn()
            val response = result.response

            assertThat(result.resolvedException).isInstanceOf(InterruptedException::class.java)
            assertThat(response.status).isEqualTo(503)
            assertThat(response.problem()["code"].asString()).isEqualTo("deadline_exceeded")
            assertThat(response.problem()["detail"].asString()).isEqualTo("the request did not finish within its time budget")
            assertThat(response.getHeader("Retry-After")).isNull()
            assertThat(Thread.currentThread().isInterrupted).describedAs("the budget's interrupt outlived the request").isFalse()
        }
    }

    @Test
    fun `the spent budget outranks the fault the interrupted handler raised and every translator`() {
        runner
            .withBean(FaultTranslator::class.java, { FaultTranslator { Fault.forbidden() } })
            .run { context ->
                val mvc = mockMvcOf(context)

                assertThat(
                    mvc
                        .perform(get("/stuck-then-conflict"))
                        .andReturn()
                        .response
                        .problem()["code"]
                        .asString(),
                ).isEqualTo("deadline_exceeded")
                assertThat(
                    mvc
                        .perform(get("/stuck"))
                        .andReturn()
                        .response
                        .problem()["code"]
                        .asString(),
                ).isEqualTo("deadline_exceeded")
            }
    }
}
