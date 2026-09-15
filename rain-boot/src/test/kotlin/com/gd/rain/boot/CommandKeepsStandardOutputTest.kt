package com.gd.rain.boot

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.status.Status
import com.gd.rain.boot.command.CommandOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Runs [work] with the process's standard output and standard error captured, and answers what each received. */
private fun captured(work: () -> Unit): Pair<String, String> {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val originalOut = System.out
    val originalErr = System.err
    System.setOut(PrintStream(out, true, Charsets.UTF_8))
    System.setErr(PrintStream(err, true, Charsets.UTF_8))
    try {
        work()
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }
    return out.toString(Charsets.UTF_8) to err.toString(Charsets.UTF_8)
}

/**
 * A command's result is its standard output. Spring Boot printed its banner there before the command ran, so a result
 * read by another program started with it; and log lines go wherever the application's logging configuration sends them.
 */
class CommandKeepsStandardOutputTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application {
        val result = ByteArrayOutputStream()

        @Bean
        fun commandOutput(): CommandOutput = CommandOutput(PrintStream(result, true, Charsets.UTF_8), PrintStream(ByteArrayOutputStream()))
    }

    private fun start(vararg properties: String) =
        SpringApplicationBuilder(Application::class.java)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .run(*(listOf("--spring.application.name=sample", "--rain.deployment.stage=test") + properties).toTypedArray())

    @Test
    fun `a command prints no banner even where the deployment asks for one, and a process with roles still does`() {
        val (commandOut, _) =
            captured {
                val context = start("--rain.runtime.command=config-check", "--spring.main.banner-mode=console")
                assertThat(context.getBean(Application::class.java).result.toString(Charsets.UTF_8))
                    .isEqualTo("configuration: ok (stage=test)\n")
                assertThat(SpringApplication.exit(context)).isZero()
            }
        val (rolesOut, _) = captured { start("--rain.runtime.roles=api", "--spring.main.banner-mode=console").close() }

        assertThat(commandOut).doesNotContain(BANNER)
        assertThat(rolesOut).describedAs("the capture sees a banner that is printed").contains(BANNER)
    }

    @Test
    fun `a logback configuration including rain-boot's console appender writes every line to standard error`() {
        // A context of the test's own, so the JVM's logging is left as it is; SLF4J's provider would set its MDC adapter.
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        val (out, err) =
            captured {
                val configurator = JoranConfigurator()
                configurator.context = context
                configurator.doConfigure(CONFIGURATION.byteInputStream())
                context.getLogger("rain.boot.test").info("a line of the log")
                context.stop()
            }
        val complaints =
            context.statusManager.copyOfStatusList
                .filter { it.level >= Status.WARN }
                .map { "${it.message} ${it.throwable}" }

        assertThat(complaints).describedAs("what logback reported while reading the configuration").isEmpty()
        assertThat(out).isEmpty()
        assertThat(err).contains("a line of the log")
    }

    private companion object {
        const val BANNER = ":: Spring Boot ::"

        /** The logback-spring.xml an application writes, read by logback itself. */
        val CONFIGURATION =
            """
            <configuration>
                <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
                <include resource="com/gd/rain/boot/logging/logback/console-stderr-appender.xml"/>
                <root level="INFO">
                    <appender-ref ref="CONSOLE"/>
                </root>
            </configuration>
            """.trimIndent()
    }
}
