package com.gd.rain.observability.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.status.Status
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** The Logback bridge receives the SDK the context provides, only when OTLP log export is switched on. */
class OtelAppenderInstalledTest {
    private val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder().setLoggerProvider(SdkLoggerProvider.builder().build()).build()

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainOpenTelemetryLoggingAutoConfiguration::class.java))
            .withBean(OpenTelemetry::class.java, { sdk })

    private val root: Logger get() = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)

    @AfterEach
    fun detach() {
        root.getAppender(OpenTelemetryAppenderInstaller.APPENDER)?.let {
            root.detachAppender(it)
            it.stop()
        }
        sdk.close()
    }

    @Test
    fun `the appender is installed on the root logger and holds the context's OpenTelemetry`() {
        runner.withPropertyValues("management.logging.export.otlp.enabled=true").run { context ->
            assertThat(context).hasNotFailed()
            val appender = root.getAppender(OpenTelemetryAppenderInstaller.APPENDER)

            assertThat(appender).isInstanceOf(OpenTelemetryAppender::class.java)
            assertThat(installedOpenTelemetry(appender as OpenTelemetryAppender)).isSameAs(sdk)
        }
    }

    /** Numbers, strings and the message travel different conversion paths inside the appender; none may report an error. */
    @Test
    fun `a line with key-value attributes reaches the installed pipeline without a logback error`() {
        runner.withPropertyValues("management.logging.export.otlp.enabled=true").run { context ->
            assertThat(context).hasNotFailed()
            val logback = LoggerFactory.getILoggerFactory() as LoggerContext
            val before = logback.statusManager.copyOfStatusList.size

            LoggerFactory
                .getLogger("rain.otel.probe")
                .atInfo()
                .setMessage("http request served")
                .addKeyValue("request_id", "probe-1")
                .addKeyValue("status", 200)
                .log()

            val errors =
                logback.statusManager.copyOfStatusList
                    .drop(before)
                    .filter { it.level == Status.ERROR }
            assertThat(errors).describedAs(errors.joinToString()).isEmpty()
        }
    }

    @Test
    fun `nothing is installed unless export is switched on`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(OpenTelemetryAppenderInstaller::class.java)
            assertThat(root.getAppender(OpenTelemetryAppenderInstaller.APPENDER)).isNull()
        }
        runner.withPropertyValues("management.logging.export.otlp.enabled=false").run { context ->
            assertThat(context).doesNotHaveBean(OpenTelemetryAppenderInstaller::class.java)
        }
    }

    private fun installedOpenTelemetry(appender: OpenTelemetryAppender): Any? =
        OpenTelemetryAppender::class.java
            .getDeclaredField("openTelemetry")
            .apply { isAccessible = true }
            .get(appender)
}
