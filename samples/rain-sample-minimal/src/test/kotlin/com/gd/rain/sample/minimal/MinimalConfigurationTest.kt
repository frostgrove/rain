package com.gd.rain.sample.minimal

import com.gd.rain.core.config.ConfigurationProblemsException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.builder.SpringApplicationBuilder

/** The configuration as a deployment meets it: one refusal naming everything, and the one-shot check. */
class MinimalConfigurationTest {
    @Test
    fun `a deployment that states nothing is refused once, naming every missing value of rain and of the application`() {
        val failure =
            catchThrowable {
                SpringApplicationBuilder(MinimalApplication::class.java)
                    .properties("server.port=0", "spring.config.location=classpath:/refusal/nothing-stated.yml")
                    .run()
                    .close()
            }

        val refusal = generateSequence(failure, Throwable::cause).filterIsInstance<ConfigurationProblemsException>().first()
        val byPath = refusal.problems.associateBy { it.path }
        assertThat(byPath.keys).contains("rain.deployment.stage", "rain.runtime", "rain.web", "sample.greetings")
        assertThat(checkNotNull(byPath["rain.web"]).message)
            .contains("rain.web.body-limit", "rain.web.request-budget", "rain.web.client-address")
        assertThat(checkNotNull(byPath["sample.greetings"]).message)
            .contains("sample.greetings.reserved-names", "sample.greetings.max-name-length")
    }

    @Test
    fun `config-check starts no server, runs every check and exits 0`() {
        val context =
            SpringApplicationBuilder(MinimalApplication::class.java)
                .properties("rain.deployment.stage=prod", "rain.runtime.command=config-check")
                .run()

        assertThat(context.environment.getProperty("spring.main.web-application-type")).isEqualTo("none")
        assertThat(SpringApplication.exit(context)).isZero()
    }
}
