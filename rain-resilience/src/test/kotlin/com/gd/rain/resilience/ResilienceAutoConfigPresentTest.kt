package com.gd.rain.resilience

import com.gd.rain.observability.health.HealthRegistry
import com.gd.rain.observability.health.Importance
import com.gd.rain.resilience.autoconfigure.RainResilienceAutoConfiguration
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Gap 10: the Resilience4j starter's auto-configuration is part of every rain application on purpose.
 * Nothing excludes it, its registry is the only one, and rain's configuration runs after it.
 */
class ResilienceAutoConfigPresentTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application {
        @Bean
        fun paymentsBreaker(): BreakerDeclaration = BreakerDeclaration(BreakerName("payments"), "payments_unavailable")
    }

    @Test
    fun `both auto-configurations are listed as import candidates`() {
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates

        assertThat(candidates).contains(CircuitBreakerAutoConfiguration::class.java.name, RainResilienceAutoConfiguration::class.java.name)
    }

    @Test
    fun `a real start applies the starter, excludes nothing, and wires rain's breakers over its registry`() {
        SpringApplicationBuilder(Application::class.java)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .properties(
                "spring.application.name=sample",
                "rain.runtime.roles=api",
                "rain.deployment.stage=test",
                "rain.health.checks.breaker.payments=degrading",
                "resilience4j.circuitbreaker.instances.payments.wait-duration-in-open-state=30s",
            ).run()
            .use { context ->
                val report = ConditionEvaluationReport.get(context.beanFactory)
                assertThat(report.exclusions).doesNotContain(CircuitBreakerAutoConfiguration::class.java.name)
                val starter = report.conditionAndOutcomesBySource[CircuitBreakerAutoConfiguration::class.java.name]
                assertThat(starter).describedAs("the starter's conditions were evaluated").isNotNull()
                assertThat(starter!!.isFullMatch).describedAs("and all of them matched").isTrue()

                val container = context.getBean(CircuitBreakerRegistry::class.java)
                assertThat(context.getBeansOfType(CircuitBreakerRegistry::class.java)).hasSize(1)
                assertThat(container.find("payments")).isPresent()
                assertThat(context.getBean(BreakerRegistry::class.java).openWait(BreakerName("payments")))
                    .isEqualTo(java.time.Duration.ofSeconds(30))
                assertThat(context.getBean(HealthRegistry::class.java).contributions().map { it.name }).contains("breaker.payments")
            }
    }
}
