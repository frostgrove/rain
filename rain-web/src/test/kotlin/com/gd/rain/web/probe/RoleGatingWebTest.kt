package com.gd.rain.web.probe

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.web.filter.ProbeOnlyFilter
import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.problemCode
import com.gd.rain.web.webRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A process without the API role serves only the probes and the Actuator base path; one with it serves its routes. */
class RoleGatingWebTest {
    @RestController
    class Routes {
        @GetMapping("/api/things")
        fun things(): String = "things"

        @GetMapping("/actuator/custom")
        fun actuator(): String = "actuator"
    }

    private val runner = webRunner().withUserConfiguration(Routes::class.java)

    @Test
    fun `a worker serves the probes and the actuator, and every other path is 404 not_found`() {
        runner.withPropertyValues("rain.runtime.roles=worker").run { context ->
            val mvc = mockMvcOf(context)

            assertThat(
                mvc
                    .perform(get("/live"))
                    .andReturn()
                    .response.status,
            ).isEqualTo(200)
            assertThat(
                mvc
                    .perform(get("/ready"))
                    .andReturn()
                    .response.status,
            ).isEqualTo(200)
            assertThat(
                mvc
                    .perform(get("/actuator/custom"))
                    .andReturn()
                    .response.contentAsString,
            ).isEqualTo("actuator")
            val route = mvc.perform(get("/api/things")).andReturn().response
            assertThat(route.status).isEqualTo(404)
            assertThat(route.problemCode()).isEqualTo("not_found")
            assertThat(
                mvc
                    .perform(get("/actuatorish"))
                    .andReturn()
                    .response.status,
            ).isEqualTo(404)
            assertThat(probeOnlyRegistrations(context)).hasSize(1)
        }
    }

    @Test
    fun `an api process serves its routes and mounts no probe-only gate`() {
        runner.withPropertyValues("rain.runtime.roles=api,worker").run { context ->
            val mvc = mockMvcOf(context)

            assertThat(
                mvc
                    .perform(get("/api/things"))
                    .andReturn()
                    .response.contentAsString,
            ).isEqualTo("things")
            assertThat(
                mvc
                    .perform(get("/live"))
                    .andReturn()
                    .response.status,
            ).isEqualTo(200)
            assertThat(probeOnlyRegistrations(context)).isEmpty()
        }
    }

    @Test
    fun `the gate follows the configured probe paths and actuator base path`() {
        runner
            .withPropertyValues(
                "rain.runtime.roles=seeder",
                "rain.web.probes.live-path=/health/live",
                "rain.web.probes.ready-path=/health/ready",
                "management.endpoints.web.base-path=/manage",
            ).run { context ->
                val mvc = mockMvcOf(context)

                assertThat(
                    mvc
                        .perform(get("/health/live"))
                        .andReturn()
                        .response.contentAsString,
                ).isEqualTo("""{"status":"live"}""")
                assertThat(
                    mvc
                        .perform(get("/live"))
                        .andReturn()
                        .response.status,
                ).isEqualTo(404)
                assertThat(
                    mvc
                        .perform(get("/actuator/custom"))
                        .andReturn()
                        .response.status,
                ).isEqualTo(404)
            }
    }

    @Test
    fun `an invalid role selection fails the context instead of gating nothing`() {
        runner.withPropertyValues("rain.runtime.roles=wrker").run { context ->
            assertThat(context).hasFailed()
            assertThat(generateSequence(context.startupFailure, Throwable::cause).any { it is ConfigurationProblemsException }).isTrue()
        }
    }

    private fun probeOnlyRegistrations(context: org.springframework.context.ApplicationContext) =
        context.getBeansOfType(FilterRegistrationBean::class.java).values.filter { it.filter is ProbeOnlyFilter }
}
