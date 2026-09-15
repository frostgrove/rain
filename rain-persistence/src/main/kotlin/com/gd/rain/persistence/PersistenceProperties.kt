package com.gd.rain.persistence

import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.core.config.problems
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `rain.persistence`.
 *
 * [statementTimeout] has no default: it is the bound every JDBC and jOOQ statement carries, and no
 * value is right for every application.
 */
@ConfigurationProperties("rain.persistence")
public data class PersistenceProperties(
    public val statementTimeout: Duration,
    public val retry: RetryProperties = RetryProperties(),
)

/** How a transaction that failed with a retryable SQLState is run again. */
public data class RetryProperties(
    public val attempts: Int = 3,
    public val initialDelay: Duration = Duration.ofMillis(50),
    public val maxDelay: Duration = Duration.ofSeconds(1),
) : ConfigurationSection

/** `rain.locks`: how long an advisory lock waits, and how many times a guarded transaction runs. */
@ConfigurationProperties("rain.locks")
public data class LockProperties(
    public val timeout: Duration = Duration.ofSeconds(10),
    public val attempts: Int = 3,
)

public class PersistenceConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec("rain.persistence", PersistenceProperties::class, Presence.REQUIRED) { properties, _ ->
                problems {
                    expect(positive(properties.statementTimeout), "rain.persistence.statement-timeout") {
                        "is ${properties.statementTimeout.written()}; it has to be positive"
                    }
                    expect(properties.retry.attempts >= 1, "rain.persistence.retry.attempts") {
                        "is ${properties.retry.attempts}; a transaction runs at least once"
                    }
                    expect(positive(properties.retry.initialDelay), "rain.persistence.retry.initial-delay") {
                        "is ${properties.retry.initialDelay.written()}; it has to be positive"
                    }
                    expect(properties.retry.maxDelay >= properties.retry.initialDelay, "rain.persistence.retry.max-delay") {
                        "is ${properties.retry.maxDelay.written()}, below the initial delay ${properties.retry.initialDelay.written()}"
                    }
                }
            },
            SectionSpec("rain.locks", LockProperties::class, Presence.OPTIONAL) { properties, _ ->
                problems {
                    expect(
                        positive(properties.timeout),
                        "rain.locks.timeout",
                    ) { "is ${properties.timeout.written()}; it has to be positive" }
                    expect(
                        properties.attempts >= 1,
                        "rain.locks.attempts",
                    ) { "is ${properties.attempts}; a guarded transaction runs at least once" }
                }
            },
        )

    private fun positive(duration: Duration): Boolean = !duration.isZero && !duration.isNegative
}
