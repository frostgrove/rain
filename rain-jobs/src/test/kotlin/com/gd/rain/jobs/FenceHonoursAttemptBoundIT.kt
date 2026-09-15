package com.gd.rain.jobs

import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.keyOf
import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Gap 8: a fenced effect's statements are bounded by `min(step, deadline − now)` — the fence never grants a statement
 * more time than the attempt has left.
 */
@Tag("integration")
class FenceHonoursAttemptBoundIT {
    private val profile = Fixtures.profile(attemptTimeout = Duration.ofSeconds(10), stepTimeout = Duration.ofSeconds(5))

    @Test
    fun `with less of the attempt left than a step, the fence bounds statements by what is left`() {
        val fixture = AttemptFixture("fence_bound_remaining", profile = profile)
        val id = fixture.enqueue()
        var seen = ""

        fixture.run(id) { _, attempt ->
            fixture.clock.advance(Duration.ofMillis(8_500))
            seen = attempt.fenced(listOf(Exclusively(keyOf("bound")))) { currentTimeout(fixture) }
        }

        assertThat(seen).isEqualTo("1500ms")
    }

    @Test
    fun `with more of the attempt left than a step, the fence bounds statements by the step`() {
        val fixture = AttemptFixture("fence_bound_step", profile = profile)
        val id = fixture.enqueue()
        var outside = ""
        var insideStep = ""

        fixture.run(id) { _, attempt ->
            fixture.clock.advance(Duration.ofSeconds(1))
            outside = attempt.fenced(listOf(Exclusively(keyOf("bound")))) { currentTimeout(fixture) }
            insideStep =
                attempt.step(Duration.ofSeconds(2)) { attempt.fenced(listOf(Exclusively(keyOf("bound")))) { currentTimeout(fixture) } }
        }

        assertThat(outside).describedAs("the profile's step timeout").isEqualTo("5s")
        assertThat(insideStep).describedAs("the explicit step budget").isEqualTo("2s")
    }

    @Test
    fun `an attempt with nothing left runs no fenced effect at all`() {
        val fixture = AttemptFixture("fence_bound_none", profile = profile)
        val id = fixture.enqueue()
        var ran = false

        fixture.run(id) { _, attempt ->
            fixture.clock.advance(Duration.ofSeconds(10))
            attempt.fenced(listOf(Exclusively(keyOf("bound")))) { ran = true }
        }

        assertThat(ran).isFalse()
        assertThat(fixture.row(id)["failure_code"]).isEqualTo(FailureCode.ATTEMPT_TIMEOUT)
        assertThat(fixture.row(id)["retry_spent"]).describedAs("a timeout is charged").isEqualTo(1)
    }

    private fun currentTimeout(fixture: AttemptFixture): String =
        requireNotNull(fixture.database.dsl.fetchValue(DSL.field("current_setting('statement_timeout')", String::class.java)))
}
