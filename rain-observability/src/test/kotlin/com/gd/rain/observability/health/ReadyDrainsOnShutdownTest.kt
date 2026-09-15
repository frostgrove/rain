package com.gd.rain.observability.health

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.atomic.AtomicReference

/**
 * Gap 44: readiness answers `draining` from the moment the context starts closing — observed by a
 * lifecycle bean while the context stops, which is when a graceful web server shutdown waits for
 * in-flight requests.
 */
class ReadyDrainsOnShutdownTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application {
        @Bean
        fun database(): FakeCheck = passing("database")

        @Bean
        fun readinessWhileStopping(registry: HealthRegistry): ReadinessWhileStopping = ReadinessWhileStopping(registry)
    }

    class ReadinessWhileStopping(
        private val registry: HealthRegistry,
    ) : SmartLifecycle {
        @Volatile
        private var running = false
        val seen = AtomicReference<ReadinessReport?>()

        override fun start() {
            running = true
        }

        override fun stop() {
            seen.set(registry.ready())
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    @Test
    fun `readiness is draining while the context stops, and nobody is asked`() {
        val context =
            SpringApplicationBuilder(Application::class.java)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties("spring.application.name=sample", "rain.runtime.roles=api", "rain.deployment.stage=test")
                .run()
        val registry = context.getBean(HealthRegistry::class.java)
        val database = context.getBean(FakeCheck::class.java)
        val stopping = context.getBean(ReadinessWhileStopping::class.java)

        assertThat(registry.ready()).isEqualTo(ReadinessReport(ReadinessStatus.READY, emptyList()))
        val askedBeforeClose = database.asked.get()

        context.close()

        assertThat(stopping.seen.get()).isEqualTo(ReadinessReport(ReadinessStatus.DRAINING, emptyList()))
        assertThat(
            stopping.seen
                .get()
                ?.status
                ?.httpStatus,
        ).isEqualTo(503)
        assertThat(database.asked).hasValue(askedBeforeClose)
    }

    @Test
    fun `a child context closing does not drain its parent`() {
        val parent =
            SpringApplicationBuilder(Application::class.java)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties("spring.application.name=sample", "rain.runtime.roles=api", "rain.deployment.stage=test")
                .run()
        parent.use {
            val child = AnnotationConfigApplicationContext()
            child.parent = parent
            child.refresh()
            child.close()

            assertThat(parent.getBean(HealthRegistry::class.java).isDraining).isFalse()
        }
    }
}
