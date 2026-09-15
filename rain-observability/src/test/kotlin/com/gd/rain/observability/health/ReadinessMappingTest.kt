package com.gd.rain.observability.health

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Which failure closes the door, which one only says so, and the HTTP status each readiness status is served with. */
class ReadinessMappingTest {
    @Test
    fun `a required failure is 503 and a degrading one is still 200`() {
        registryOf(failing("database")).use {
            val report = it.ready()

            assertThat(report).isEqualTo(ReadinessReport(ReadinessStatus.NOT_READY, listOf("database")))
            assertThat(report.status.httpStatus).isEqualTo(503)
        }

        registryOf(failing("jobs.workers", Importance.DEGRADING, code = "jobs")).use {
            val report = it.ready()

            assertThat(report).isEqualTo(ReadinessReport(ReadinessStatus.DEGRADED, listOf("jobs")))
            assertThat(report.status.httpStatus).isEqualTo(200)
        }
    }

    @Test
    fun `a required failure beside a degrading one closes the door and reports both codes`() {
        registryOf(failing("database"), failing("jobs.workers", Importance.DEGRADING, code = "jobs")).use {
            val report = it.ready()

            assertThat(report.status.httpStatus).isEqualTo(503)
            assertThat(report.failing).containsExactly("database", "jobs")
        }
    }

    @Test
    fun `an informational failure is not counted and not named`() {
        registryOf(failing("note", Importance.INFORMATIONAL)).use {
            assertThat(it.ready()).isEqualTo(ReadinessReport(ReadinessStatus.READY, emptyList()))
        }
    }

    @Test
    fun `a healthy deployment reports a status and nothing else`() {
        registryOf(passing("database")).use {
            assertThat(it.ready()).isEqualTo(ReadinessReport(ReadinessStatus.READY, emptyList()))
        }
    }

    @Test
    fun `live is 200 while ready is 503`() {
        registryOf(failing("database")).use {
            assertThat(it.live().status).isEqualTo("live")
            assertThat(LivenessReport.HTTP_STATUS).isEqualTo(200)
            assertThat(it.ready().status.httpStatus).isEqualTo(503)
        }
    }

    @Test
    fun `every readiness status has one wire name and one HTTP status`() {
        assertThat(ReadinessStatus.entries.map { Triple(it, it.wire, it.httpStatus) }).containsExactly(
            Triple(ReadinessStatus.READY, "ready", 200),
            Triple(ReadinessStatus.DEGRADED, "degraded", 200),
            Triple(ReadinessStatus.NOT_READY, "not_ready", 503),
            Triple(ReadinessStatus.DRAINING, "draining", 503),
        )
    }

    @Test
    fun `a draining process is 503 and asks nobody`() {
        val database = passing("database")
        registryOf(database).use {
            it.startDraining()

            assertThat(it.ready()).isEqualTo(ReadinessReport(ReadinessStatus.DRAINING, emptyList()))
            assertThat(database.asked).hasValue(0)
        }
    }

    @Test
    fun `an incoherent report cannot be built`() {
        assertThatThrownBy {
            ReadinessReport(
                ReadinessStatus.NOT_READY,
                listOf("b", "a"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReadinessReport(ReadinessStatus.READY, listOf("a")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReadinessReport(ReadinessStatus.DRAINING, listOf("a")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
