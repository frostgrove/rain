package com.gd.rain.boot.runtime

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Makes a process started as a command a command: no web server, plus whatever its declaration sets.
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
        val selection = RuntimeSelection.resolve(environment, CommandDeclarations.load(application.classLoader))
        if (selection !is RuntimeSelection.Command) return

        val values = linkedMapOf(WEB_APPLICATION_TYPE to "none")
        values.putAll(selection.declaration.properties)

        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, values.toMap()))
    }

    public companion object {
        public const val SOURCE_NAME: String = "rain-command"
        private const val WEB_APPLICATION_TYPE = "spring.main.web-application-type"
    }
}
