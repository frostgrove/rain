package com.gd.rain.jobs

import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.keyOf
import com.gd.rain.jobs.internal.execution.Completion
import com.gd.rain.jobs.support.AttemptFixture
import com.gd.rain.jobs.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Timestamp

/** The fence: an effect commits only while the attempt owns the invocation with an unexpired lease. */
@Tag("integration")
class FenceIT {
    private val guards = listOf(Exclusively(keyOf("notes", "effect")))

    private fun fixture(prefix: String): AttemptFixture =
        AttemptFixture(prefix).also { it.sql("CREATE TABLE public.effects (invocation UUID PRIMARY KEY)") }

    private fun effects(fixture: AttemptFixture): Long = fixture.database.count("SELECT count(*) FROM public.effects")

    @Test
    fun `a fenced effect commits while the lease is held`() {
        val fixture = fixture("fence_held")
        val id = fixture.enqueue()

        val completion =
            fixture.run(id) { _, attempt ->
                attempt.fenced(guards) { fixture.database.dsl.execute("INSERT INTO public.effects VALUES ('${attempt.meta.invocation}')") }
            }

        assertThat(completion).isEqualTo(Completion.Remove)
        assertThat(effects(fixture)).isEqualTo(1)
        assertThat(fixture.row(id)["state"]).isEqualTo("succeeded")
    }

    @Test
    fun `an effect under a token another attempt re-stamped does not run, and nothing is written for the lost attempt`() {
        val fixture = fixture("fence_restamped")
        val id = fixture.enqueue()
        val otherLease = Fixtures.START.plusSeconds(25)

        val completion =
            fixture.run(id) { _, attempt ->
                fixture.sql(
                    "UPDATE rain_jobs.job_invocation SET lease_token = gen_random_uuid(), lease_expires_at = ? WHERE id = ?",
                    Timestamp.from(otherLease),
                    id,
                )
                attempt.fenced(guards) { fixture.database.dsl.execute("INSERT INTO public.effects VALUES ('$id')") }
            }

        assertThat(effects(fixture)).isZero()
        assertThat(completion).isEqualTo(Completion.Reschedule(otherLease))
        assertThat(fixture.row(id)["state"]).isEqualTo("running")
        assertThat(fixture.row(id)["retry_spent"]).describedAs("a lost lease is not charged").isEqualTo(0)
    }

    @Test
    fun `an effect under a lapsed lease does not run`() {
        val fixture = fixture("fence_lapsed")
        val id = fixture.enqueue()

        fixture.run(id) { _, attempt ->
            fixture.clock.advance(fixture.ttl)
            attempt.fenced(guards) { fixture.database.dsl.execute("INSERT INTO public.effects VALUES ('$id')") }
        }

        assertThat(effects(fixture)).isZero()
    }

    @Test
    fun `a cancelled invocation fences its own effect out and its execution is removed`() {
        val fixture = fixture("fence_cancelled")
        val id = fixture.enqueue()

        val completion =
            fixture.run(id) { _, attempt ->
                fixture.sql(
                    "UPDATE rain_jobs.job_invocation SET state = 'cancelled', finished_at = eligible_at, " +
                        "lease_token = NULL, lease_expires_at = NULL " +
                        "WHERE id = ?",
                    id,
                )
                attempt.fenced(guards) { fixture.database.dsl.execute("INSERT INTO public.effects VALUES ('$id')") }
            }

        assertThat(effects(fixture)).isZero()
        assertThat(completion).isEqualTo(Completion.Remove)
        assertThat(fixture.row(id)["state"]).isEqualTo("cancelled")
    }
}
