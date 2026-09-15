package com.gd.rain.boot.config

import com.gd.rain.core.config.ConfigurationProblemsException
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/** Prints every configuration problem with its path and source instead of a stack trace. */
public class ConfigurationProblemsFailureAnalyzer : AbstractFailureAnalyzer<ConfigurationProblemsException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: ConfigurationProblemsException,
    ): FailureAnalysis =
        FailureAnalysis(
            cause.message,
            "State or correct the listed properties, then start the application again.",
            cause,
        )
}
