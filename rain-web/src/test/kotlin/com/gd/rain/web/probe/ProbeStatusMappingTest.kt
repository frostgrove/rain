package com.gd.rain.web.probe

import com.gd.rain.observability.health.HealthContribution
import com.gd.rain.observability.health.HealthRegistry
import com.gd.rain.observability.health.Importance
import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.webRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** The readiness contract over HTTP: status, body and HTTP code, whatever the caller accepts. */
class ProbeStatusMappingTest {
    class Check(
        override val name: String,
        override val importance: Importance,
        private val passes: Boolean,
    ) : HealthContribution {
        val asked = AtomicInteger()
        override val code: String = name
        override val timeout: Duration? = null

        override fun probe() {
            asked.incrementAndGet()
            check(passes) { "$name is down" }
        }
    }

    @Test
    fun `a process with nothing failing is ready`() {
        webRunner().run { context ->
            val response = mockMvcOf(context).perform(get("/ready")).andReturn().response

            assertThat(response.status).isEqualTo(200)
            assertThat(response.contentType).isEqualTo("application/json")
            assertThat(response.contentAsString).isEqualTo("""{"status":"ready","failing":[]}""")
        }
    }

    @Test
    fun `a required failure is 503 not_ready and a degrading one alone is 200 degraded`() {
        webRunner()
            .withBean("database", HealthContribution::class.java, { Check("database", Importance.REQUIRED, passes = false) })
            .withBean("search", HealthContribution::class.java, { Check("search", Importance.DEGRADING, passes = false) })
            .run { context ->
                val response = mockMvcOf(context).perform(get("/ready")).andReturn().response

                assertThat(response.status).isEqualTo(503)
                assertThat(response.contentAsString).isEqualTo("""{"status":"not_ready","failing":["database","search"]}""")
            }
        webRunner()
            .withBean("search", HealthContribution::class.java, { Check("search", Importance.DEGRADING, passes = false) })
            .run { context ->
                val response = mockMvcOf(context).perform(get("/ready")).andReturn().response

                assertThat(response.status).isEqualTo(200)
                assertThat(response.contentAsString).isEqualTo("""{"status":"degraded","failing":["search"]}""")
            }
    }

    @Test
    fun `a draining process is 503 draining`() {
        webRunner().run { context ->
            context.getBean(HealthRegistry::class.java).startDraining()
            val response = mockMvcOf(context).perform(get("/ready")).andReturn().response

            assertThat(response.status).isEqualTo(503)
            assertThat(response.contentAsString).isEqualTo("""{"status":"draining","failing":[]}""")
        }
    }

    @Test
    fun `live is 200 and asks nobody, even with a required check down`() {
        webRunner()
            .withBean("database", Check::class.java, { Check("database", Importance.REQUIRED, passes = false) })
            .run { context ->
                val response = mockMvcOf(context).perform(get("/live")).andReturn().response

                assertThat(response.status).isEqualTo(200)
                assertThat(response.contentAsString).isEqualTo("""{"status":"live"}""")
                assertThat(context.getBean(Check::class.java).asked).hasValue(0)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/plain", "text/html", "application/xml", "application/json", "*/*"])
    fun `no Accept header turns a probe into a 406`(accept: String) {
        webRunner()
            .withBean("database", HealthContribution::class.java, { Check("database", Importance.REQUIRED, passes = false) })
            .run { context ->
                val mvc = mockMvcOf(context)
                val ready = mvc.perform(get("/ready").header(HttpHeaders.ACCEPT, accept)).andReturn().response
                val live = mvc.perform(get("/live").header(HttpHeaders.ACCEPT, accept)).andReturn().response

                assertThat(ready.status).isEqualTo(503)
                assertThat(ready.contentAsString).isEqualTo("""{"status":"not_ready","failing":["database"]}""")
                assertThat(live.status).isEqualTo(200)
                assertThat(live.contentType).isEqualTo("application/json")
            }
    }
}
