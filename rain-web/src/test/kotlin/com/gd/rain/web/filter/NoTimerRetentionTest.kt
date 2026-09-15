package com.gd.rain.web.filter

import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.webRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** Gap 30: a request that finished in time leaves no timer behind for the rest of its budget. */
class NoTimerRetentionTest {
    @RestController
    class Fast {
        @GetMapping("/fast")
        fun fast(): String = "ok"
    }

    @Test
    fun `after many completed requests no budget timer is pending`() {
        webRunner()
            .withUserConfiguration(Fast::class.java)
            .withPropertyValues("rain.web.request-budget=10m")
            .run { context ->
                val mvc = mockMvcOf(context)
                val timer = context.getBean(RequestBudgetTimer::class.java)

                repeat(REQUESTS) {
                    assertThat(
                        mvc
                            .perform(get("/fast"))
                            .andReturn()
                            .response.status,
                    ).isEqualTo(200)
                }

                assertThat(timer.pending()).isZero()
            }
    }

    @Test
    fun `a cancelled timer leaves the queue at once`() {
        RequestBudgetTimer().use { timer ->
            val armed = (1..REQUESTS).map { timer.schedule(Duration.ofMinutes(10)) {} }
            assertThat(timer.pending()).isEqualTo(REQUESTS)

            armed.forEach { it.cancel(false) }

            assertThat(timer.pending()).isZero()
        }
    }

    private companion object {
        const val REQUESTS = 500
    }
}
