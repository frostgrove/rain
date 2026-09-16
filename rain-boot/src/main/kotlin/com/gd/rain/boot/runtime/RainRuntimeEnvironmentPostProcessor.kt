package com.gd.rain.boot.runtime

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Makes a process started as a command a command: no web server, no banner, plus whatever its declaration sets.
 *
 * A command's result is what it writes to standard output, and Spring Boot prints its banner there before any bean
 * exists; so the banner is off in every process that states `rain.runtime.command` — whatever the deployment states, and
 * even when the selection is refused (an undeclared command, a command stated with roles), because whoever started it
 * reads standard output as a command's. Log lines are the application's
 * logging configuration's: rain-boot does not read or change it, and ships
 * `com/gd/rain/boot/logging/logback/console-stderr-appender.xml` for a logback configuration to keep them on standard
 * error.
 *
 * Runs after the configuration files are loaded. The properties a command declares are part of its
 * documented contract and take precedence over the deployment's — `migrate` enables Flyway in a
 * deployment whose serving processes keep it disabled. An invalid runtime selection is left alone here
 * and reported, together with every other problem, by the configuration validator.
 */
public class RainRuntimeEnvironmentPostProcessor :
    EnvironmentPostProcessor,
    Ordered {
    override fun getOrder(): Int = ConfigDataEnvironmentPostProcessor.ORDER + 10

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (!environment.containsProperty(RuntimeSelection.COMMAND)) return
        val selection = RuntimeSelection.resolve(environment, CommandDeclarations.load(application.classLoader))

        val values = linkedMapOf<String, Any>()
        if (selection is RuntimeSelection.Command) {
            values[WEB_APPLICATION_TYPE] = "none"
            values.putAll(selection.declaration.properties)
        }
        // After the declaration's: nothing a command states puts a banner in front of its result.
        values[BANNER_MODE] = "off"

        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, values.toMap()))
    }

    public companion object {
        public const val SOURCE_NAME: String = "rain-command"

        /** The banner of a process started as a command is always off. */
        public const val BANNER_MODE: String = "spring.main.banner-mode"

        private const val WEB_APPLICATION_TYPE = "spring.main.web-application-type"
    }
}
