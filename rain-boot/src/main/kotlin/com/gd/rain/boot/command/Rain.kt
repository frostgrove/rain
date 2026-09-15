package com.gd.rain.boot.command

import com.gd.rain.boot.runtime.CommandDeclarations
import com.gd.rain.boot.runtime.RuntimeSelection
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import kotlin.system.exitProcess

/**
 * The entry point of a rain application.
 *
 * Started with roles, it returns the running context. Started as a command, it closes the context
 * once the command has run and exits with the command's code — the context closes first, so pools
 * and log exporters flush before the JVM ends.
 */
public object Rain {
    public fun run(
        application: Class<*>,
        args: Array<String>,
    ): ConfigurableApplicationContext {
        val context = SpringApplication.run(application, *args)
        val selection = RuntimeSelection.resolve(context.environment, CommandDeclarations.load(application.classLoader))
        if (selection is RuntimeSelection.Command) exitProcess(SpringApplication.exit(context))
        return context
    }
}

/** `runRain<Application>(args)` in a `main` function. */
public inline fun <reified T : Any> runRain(args: Array<String>): ConfigurableApplicationContext = Rain.run(T::class.java, args)
