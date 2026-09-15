package com.gd.rain.web.filter

import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.problemCode
import com.gd.rain.web.webRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** CORS is Spring's processor over `rain.web.cors`; a rejection is `403 cross_site` problem+json. */
class CorsTest {
    @RestController
    class Things {
        @GetMapping("/things")
        fun things(): String = "ok"

        @PostMapping("/things")
        fun create(): String = "created"
    }

    private val allowing =
        webRunner()
            .withUserConfiguration(Things::class.java)
            .withPropertyValues(
                "rain.web.cors.allowed-origins=https://app.example",
                "rain.web.cors.allowed-methods=GET,POST",
                "rain.web.cors.allowed-headers=Content-Type",
                "rain.web.cors.exposed-headers=X-Request-ID",
                "rain.web.cors.max-age=10m",
                "rain.web.cors.allow-credentials=true",
            )

    @Test
    fun `a preflight from an allowed origin is answered with the configured policy`() {
        allowing.run { context ->
            val response =
                mockMvcOf(context)
                    .perform(
                        options("/things")
                            .header("Origin", "https://app.example")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Content-Type"),
                    ).andReturn()
                    .response

            assertThat(response.status).isEqualTo(200)
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://app.example")
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true")
            assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,POST")
            assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("600")
        }
    }

    @Test
    fun `an actual request from an allowed origin exposes the configured headers`() {
        allowing.run { context ->
            val response = mockMvcOf(context).perform(get("/things").header("Origin", "https://app.example")).andReturn().response

            assertThat(response.status).isEqualTo(200)
            assertThat(response.getHeader("Access-Control-Expose-Headers")).isEqualTo("X-Request-ID")
        }
    }

    @Test
    fun `a preflight and a request from an origin nobody allowed are refused with cross_site`() {
        allowing.run { context ->
            val mvc = mockMvcOf(context)
            val preflight =
                mvc
                    .perform(options("/things").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST"))
                    .andReturn()
                    .response
            val read = mvc.perform(get("/things").header("Origin", "https://evil.example")).andReturn().response

            assertThat(preflight.status).isEqualTo(403)
            assertThat(preflight.problemCode()).isEqualTo("cross_site")
            assertThat(read.status).isEqualTo(403)
            assertThat(read.problemCode()).isEqualTo("cross_site")
        }
    }

    @Test
    fun `with no allowed origin every cross-origin request is refused and a same-origin one is served`() {
        webRunner().withUserConfiguration(Things::class.java).run { context ->
            val mvc = mockMvcOf(context)

            assertThat(
                mvc
                    .perform(get("/things").header("Origin", "https://app.example"))
                    .andReturn()
                    .response.status,
            ).isEqualTo(403)
            assertThat(
                mvc
                    .perform(get("/things").header("Origin", "http://localhost"))
                    .andReturn()
                    .response.status,
            ).isEqualTo(200)
        }
    }
}
