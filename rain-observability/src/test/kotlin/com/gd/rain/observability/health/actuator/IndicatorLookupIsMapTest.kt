package com.gd.rain.observability.health.actuator

import com.gd.rain.observability.health.CheckDetail
import com.gd.rain.observability.health.CheckState
import com.gd.rain.observability.health.HealthContribution
import com.gd.rain.observability.health.HealthDetail
import com.gd.rain.observability.health.Importance
import com.gd.rain.observability.health.ReadinessStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Gap 44: an Actuator indicator finds its reading through the evaluation's name index. The checks
 * list counts every element read; building the evaluation reads each element once, and asking every
 * indicator afterwards reads none — a linear search per indicator would read the list N times over.
 */
class IndicatorLookupIsMapTest {
    private class CountingChecks(
        private val backing: List<CheckDetail>,
    ) : AbstractList<CheckDetail>() {
        val reads = AtomicLong()

        override val size: Int get() = backing.size

        override fun get(index: Int): CheckDetail {
            reads.incrementAndGet()
            return backing[index]
        }
    }

    private class Named(
        override val name: String,
        override val importance: Importance,
    ) : HealthContribution {
        override val code: String = name
        override val timeout: Duration? = null

        override fun probe() = error("an indicator never probes")
    }

    @Test
    fun `every indicator is answered from the index built once per evaluation`() {
        val count = 10_000
        val checks =
            CountingChecks(
                (0 until count).map { position ->
                    val state = if (position % 2 == 0) CheckState.PASSING else CheckState.FAILING
                    CheckDetail("check-$position", "check-$position", Importance.REQUIRED, state, null, Duration.ZERO)
                },
            )
        val detail = HealthDetail(ReadinessStatus.NOT_READY, Instant.EPOCH, checks)
        val readsToIndex = checks.reads.get()

        val statuses =
            (0 until count).map { position ->
                HealthContributionIndicator(Named("check-$position", Importance.REQUIRED)) { detail }.health().status
            }

        assertThat(readsToIndex).isEqualTo(count.toLong())
        assertThat(checks.reads.get()).describedAs("indicators searched the checks list").isEqualTo(readsToIndex)
        assertThat(statuses.filterIndexed { position, _ -> position % 2 == 0 }).containsOnly(Status.UP)
        assertThat(statuses.filterIndexed { position, _ -> position % 2 == 1 }).containsOnly(Status.DOWN)
    }

    @Test
    fun `an evaluation refuses two readings of one name`() {
        val reading = CheckDetail("database", "database", Importance.REQUIRED, CheckState.PASSING, null, Duration.ZERO)

        assertThat(runCatching { HealthDetail(ReadinessStatus.READY, Instant.EPOCH, listOf(reading, reading)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
