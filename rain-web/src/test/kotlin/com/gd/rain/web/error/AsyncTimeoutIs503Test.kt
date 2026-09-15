package com.gd.rain.web.error

import com.gd.rain.web.get
import com.gd.rain.web.port
import com.gd.rain.web.readJson
import com.gd.rain.web.startServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult

/** Gap 31: an async request that outlives Spring MVC's async timeout on a real server is 503, not 500. */
class AsyncTimeoutIs503Test {
    @RestController
    class Never {
        @GetMapping("/never")
        fun never(): DeferredResult<String> = DeferredResult()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application {
        @Bean
        fun never(): Never = Never()
    }

    @Test
    fun `an async request that times out is answered 503 in problem+json`() {
        startServer(Application::class, "spring.mvc.async.request-timeout=100ms").use { context ->
            val answer = get(context.port(), "/never")

            assertThat(answer.statusCode()).isEqualTo(503)
            assertThat(answer.headers().firstValue("Content-Type")).hasValue("application/problem+json")
            assertThat(readJson(answer.body())["code"].asString()).isEqualTo("unavailable")
        }
    }
}
