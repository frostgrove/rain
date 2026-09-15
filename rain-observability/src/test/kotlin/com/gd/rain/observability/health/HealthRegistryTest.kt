package com.gd.rain.observability.health

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the registry refuses, and what an evaluation costs. */
class HealthRegistryTest {
    @Test
    fun `a process with nothing to check is ready`() {
        registryOf().use {
            assertThat(it.ready()).isEqualTo(ReadinessReport(ReadinessStatus.READY, emptyList()))
        }
    }

    @Test
    fun `two checks of one name keep the application from starting`() {
        assertThatThrownBy { registryOf(passing("database"), passing("database", code = "database.replica")) }
            .isInstanceOfSatisfying(ConfigurationProblemsException::class.java) { refusal ->
                assertThat(refusal.problems.map { it.path to it.code }).containsExactly("health:database" to ProblemCode.CONTRADICTS)
            }
    }

    @Test
    fun `two checks that publish one code keep the application from starting`() {
        assertThatThrownBy { registryOf(passing("postgres.primary", code = "database"), passing("pgbouncer", code = "database")) }
            .isInstanceOfSatisfying(ConfigurationProblemsException::class.java) { refusal ->
                assertThat(refusal.problems.map { it.message })
                    .containsExactly("publishes code \"database\", which postgres.primary already publishes")
            }
    }

    @Test
    fun `every registration problem is reported at once`() {
        assertThatThrownBy {
            HealthRegistry(
                listOf(
                    FakeCheck(name = "", code = null),
                    FakeCheck(name = "redis", timeout = Duration.ofSeconds(-1)),
                    FakeCheck(name = "Upper", code = "Bad Code"),
                    FakeCheck(name = "zero", timeout = Duration.ZERO),
                ),
                Duration.ZERO,
                Duration.ofSeconds(-2),
                MutableClock(),
            )
        }.isInstanceOfSatisfying(ConfigurationProblemsException::class.java) { refusal ->
            assertThat(refusal.problems.map { it.path to it.code }).containsExactly(
                "rain.health.check-timeout" to ProblemCode.INVALID,
                "rain.health.freshness" to ProblemCode.INVALID,
                "health:#0" to ProblemCode.INVALID,
                "health:redis" to ProblemCode.INVALID,
                "health:Upper" to ProblemCode.INVALID,
                "health:Upper" to ProblemCode.INVALID,
                "health:zero" to ProblemCode.INVALID,
            )
        }
    }

    @Test
    fun `a required failure is not ready and names its code, a degrading one alone is degraded`() {
        registryOf(failing("database"), failing("jobs.workers", Importance.DEGRADING, code = "jobs")).use {
            assertThat(it.ready()).isEqualTo(ReadinessReport(ReadinessStatus.NOT_READY, listOf("database", "jobs")))
        }
        registryOf(failing("jobs.workers", Importance.DEGRADING, code = "jobs")).use {
            assertThat(it.ready()).isEqualTo(ReadinessReport(ReadinessStatus.DEGRADED, listOf("jobs")))
        }
    }

    @Test
    fun `codes are sorted, so two scrapes of one broken deployment read the same`() {
        registryOf(failing("z", code = "zebra"), failing("a", code = "aardvark")).use {
            assertThat(it.ready().failing).containsExactly("aardvark", "zebra")
        }
    }

    @Test
    fun `a failing check with no code moves the status without naming itself`() {
        registryOf(failing("internal.thing", code = null)).use {
            assertThat(it.ready()).isEqualTo(ReadinessReport(ReadinessStatus.NOT_READY, emptyList()))
        }
    }

    @Test
    fun `an informational failure changes nothing and a disabled check is never asked`() {
        val disabled = FakeCheck("legacy", importance = Importance.DISABLED) { error("never") }
        registryOf(failing("note", Importance.INFORMATIONAL), disabled).use { registry ->
            assertThat(registry.ready()).isEqualTo(ReadinessReport(ReadinessStatus.READY, emptyList()))
            assertThat(registry.inspect().checks.map { it.name to it.state })
                .containsExactly("legacy" to CheckState.DISABLED, "note" to CheckState.FAILING)
            assertThat(disabled.asked).hasValue(0)
        }
    }

    @Test
    fun `the failure message reaches the detail and never the report`() {
        registryOf(failing("database", message = "connection refused")).use { registry ->
            assertThat(registry.inspect().reading("database")?.message).isEqualTo("connection refused")
            assertThat(registry.ready().failing).containsExactly("database")
        }
    }

    @Test
    fun `a message longer than 256 bytes is cut on a character boundary`() {
        val cut = HealthRegistry.truncate("я".repeat(200))

        assertThat(cut.toByteArray()).hasSizeLessThanOrEqualTo(HealthRegistry.MAX_MESSAGE_BYTES)
        assertThat(cut).isEqualTo("я".repeat(128))
        assertThat(HealthRegistry.truncate("short")).isEqualTo("short")
    }

    @Test
    fun `a probe that throws an error is a failing check and not a failing process`() {
        registryOf(FakeCheck("exploding") { throw OutOfMemoryError("simulated") }).use {
            assertThat(it.ready().status).isEqualTo(ReadinessStatus.NOT_READY)
            assertThat(it.inspect().reading("exploding")?.message).isEqualTo("simulated")
        }
    }

    /** A probe that never answers must not hang readiness: it fails that check alone, at its own budget. */
    @Test
    fun `a probe that does not answer within its timeout fails that check alone`() {
        val stuck = CountDownLatch(1)
        val checks =
            arrayOf(
                FakeCheck("stuck", importance = Importance.DEGRADING, timeout = Duration.ofMillis(150)) {
                    stuck.await(30, TimeUnit.SECONDS)
                },
                passing("database"),
            )

        try {
            registryOf(*checks, clock = Clock.systemUTC()).use { registry ->
                val report = registry.ready()

                assertThat(report).isEqualTo(ReadinessReport(ReadinessStatus.DEGRADED, listOf("stuck")))
                assertThat(registry.inspect().reading("stuck")?.message).isEqualTo("the check did not answer within 150ms")
                assertThat(registry.inspect().reading("database")?.state).isEqualTo(CheckState.PASSING)
            }
        } finally {
            stuck.countDown()
        }
    }

    /** The checks of one evaluation run together: two checks that wait for each other both answer. */
    @Test
    fun `the checks of one pass are asked concurrently`() {
        val bothArrived = CountDownLatch(2)
        val checks =
            (1..2)
                .map { position ->
                    FakeCheck("slow-$position") {
                        bothArrived.countDown()
                        check(bothArrived.await(2, TimeUnit.SECONDS)) { "the other check never started" }
                    }
                }.toTypedArray()

        registryOf(*checks, clock = Clock.systemUTC()).use {
            assertThat(it.ready().status).isEqualTo(ReadinessStatus.READY)
        }
    }

    /** Other callers share the flight: an interrupted evaluator still produces a true reading and keeps its interrupt. */
    @Test
    fun `an interrupt of the evaluating thread neither poisons the reading nor is lost`() {
        registryOf(passing("database")).use { registry ->
            Thread.currentThread().interrupt()
            val report = registry.ready()

            assertThat(Thread.interrupted()).describedAs("the interrupt was swallowed").isTrue()
            assertThat(report).isEqualTo(ReadinessReport(ReadinessStatus.READY, emptyList()))
        }
    }

    @Test
    fun `live asks nobody, even when a required check is down`() {
        val database = failing("database")
        registryOf(database).use {
            assertThat(it.live()).isEqualTo(LivenessReport)
            assertThat(LivenessReport.status).isEqualTo("live")
            assertThat(database.asked).hasValue(0)
        }
    }

    @Test
    fun `two scrapes inside the freshness window cost one pass, and one after it costs another`() {
        val clock = MutableClock()
        val database = passing("database")

        registryOf(database, clock = clock).use { registry ->
            registry.ready()
            registry.ready()
            assertThat(database.asked).describedAs("two scrapes inside the window").hasValue(1)

            clock.advance(FRESHNESS.minusMillis(1))
            registry.ready()
            assertThat(database.asked).describedAs("a scrape one millisecond before the window ends").hasValue(1)

            clock.advance(Duration.ofMillis(1))
            registry.ready()
            assertThat(database.asked).describedAs("a scrape at the moment the window ends").hasValue(2)
        }
    }

    @Test
    fun `the report and the detail of one window are one pass`() {
        val database = passing("database")

        registryOf(database).use { registry ->
            registry.ready()
            registry.inspect()

            assertThat(database.asked).hasValue(1)
        }
    }

    @Test
    fun `the declared defaults bound a pass`() {
        assertThat(HealthProperties().checkTimeout).isEqualTo(Duration.ofSeconds(2))
        assertThat(HealthProperties().freshness).isEqualTo(Duration.ofSeconds(1))
    }
}
