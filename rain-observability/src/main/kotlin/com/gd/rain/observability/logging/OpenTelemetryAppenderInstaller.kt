package com.gd.rain.observability.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Installs the OpenTelemetry Logback appender on the root logger and hands it the SDK Boot built.
 *
 * Spring Boot 4.1 configures the OpenTelemetry SDK and the OTLP log exporter
 * (`OpenTelemetryLoggingAutoConfiguration` builds the `SdkLoggerProvider`) but installs no bridge from
 * Logback into it; without this, `management.logging.export.otlp.enabled=true` exports nothing an
 * application logs. Every MDC entry and every key-value pair of a log event is captured as an
 * attribute.
 *
 * Installing is idempotent: an appender named [APPENDER] already on the root logger is reused. SLF4J
 * bound to anything but Logback while export is enabled is a refusal, not a silent no-op.
 */
public class OpenTelemetryAppenderInstaller(
    private val openTelemetry: OpenTelemetry,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val factory = LoggerFactory.getILoggerFactory()
        val context =
            checkNotNull(factory as? LoggerContext) {
                "$EXPORT_PROPERTY is true but SLF4J is bound to ${factory.javaClass.name}, not Logback; " +
                    "the OpenTelemetry Logback appender cannot be installed"
            }
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root.getAppender(APPENDER) == null) {
            val appender = OpenTelemetryAppender()
            appender.name = APPENDER
            appender.context = context
            appender.setMdcAttributesIncluded(ALL_ATTRIBUTES)
            appender.setKeyValuePairAttributesIncluded(ALL_ATTRIBUTES)
            appender.start()
            root.addAppender(appender)
        }
        OpenTelemetryAppender.install(openTelemetry)
    }

    public companion object {
        public const val APPENDER: String = "OTEL"
        public const val EXPORT_PROPERTY: String = "management.logging.export.otlp.enabled"
        private const val ALL_ATTRIBUTES = "*"
    }
}

/**
 * Present only when the appender and Logback are on the classpath and OTLP log export is switched on
 * explicitly; the `OpenTelemetry` it installs is the one Boot's SDK auto-configuration provides.
 */
@AutoConfiguration(afterName = ["org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration"])
@ConditionalOnClass(OpenTelemetryAppender::class, LoggerContext::class, OpenTelemetry::class)
@ConditionalOnProperty(name = [OpenTelemetryAppenderInstaller.EXPORT_PROPERTY], havingValue = "true")
public class RainOpenTelemetryLoggingAutoConfiguration {
    @Bean
    public fun openTelemetryAppenderInstaller(openTelemetry: OpenTelemetry): OpenTelemetryAppenderInstaller =
        OpenTelemetryAppenderInstaller(openTelemetry)
}
