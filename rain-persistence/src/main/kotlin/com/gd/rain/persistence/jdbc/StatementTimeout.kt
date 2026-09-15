package com.gd.rain.persistence.jdbc

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.config.written
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.jooq.impl.DefaultConfiguration
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

/**
 * One statement timeout for every statement, whoever writes it: jOOQ's `queryTimeout` and every
 * `JdbcTemplate` (which Spring Data JDBC executes through) carry `rain.persistence.statement-timeout`.
 *
 * JDBC counts the timeout in whole seconds and treats zero as no limit, so a budget is rounded up to
 * the next second and never down to "unbounded".
 */
public class StatementTimeout(
    public val timeout: Duration,
) {
    init {
        require(!timeout.isZero && !timeout.isNegative) { "a statement timeout is positive, got $timeout" }
    }

    public val seconds: Int
        get() {
            val millis = timeout.toMillis()
            val whole = millis / 1_000 + if (millis % 1_000 == 0L) 0 else 1
            return maxOf(whole, 1L).toInt()
        }

    public fun jooqCustomizer(): DefaultConfigurationCustomizer =
        DefaultConfigurationCustomizer { configuration: DefaultConfiguration ->
            configuration.setSettings(configuration.settings().withQueryTimeout(seconds))
        }

    public fun jdbcTemplatePostProcessor(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any {
                if (bean is JdbcTemplate) bean.queryTimeout = seconds
                return bean
            }
        }
}

/** Boot's own `spring.jdbc.template.query-timeout`, when stated, has to agree with rain's single timeout. */
public class StatementTimeoutAgreementCheck(
    private val environment: Environment,
    private val timeout: StatementTimeout,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val stated = Binder.get(environment).bind(BOOT_PROPERTY, Duration::class.java).orElse(null) ?: return emptyList()
        return if (stated == timeout.timeout) {
            emptyList()
        } else {
            listOf(
                ConfigurationProblem(
                    BOOT_PROPERTY,
                    ProblemCode.CONTRADICTS,
                    "is ${stated.written()} while rain.persistence.statement-timeout is ${timeout.timeout.written()}; state one timeout",
                ),
            )
        }
    }

    private companion object {
        const val BOOT_PROPERTY = "spring.jdbc.template.query-timeout"
    }
}
