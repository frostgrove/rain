package com.gd.rain.jobs

import com.gd.rain.jobs.internal.ledger.JooqAttemptLedger
import com.gd.rain.jobs.support.AdminFixture
import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.util.UUID

/** An invocation holds at most one reservation, and every statement that releases it finds it by that key. */
@Tag("integration")
class HeldReservationIsKeyedByInvocationIT {
    @Test
    fun `a terminal write releases the held reservation through a keyed lookup`() {
        val fixture = AdminFixture("held_reservation_plan")
        val attempts = JooqAttemptLedger(fixture.database.dsl)
        val invocation = UUID.randomUUID()

        val plan =
            QueryPlans.explain(
                fixture.database.dataSource,
                fixture.database.dsl.renderInlined(
                    attempts.terminalQuery(
                        invocation,
                        JooqAttemptLedger.J.LEASE_TOKEN.eq(UUID.randomUUID()),
                        JobState.SUCCEEDED,
                        null,
                        null,
                        Fixtures.START,
                    ),
                ),
                generic = false,
            )

        assertThat(plan.usesIndex("uq_job_intent_held_invocation")).describedAs(plan.json).isTrue()
        assertThat(plan.boundedScan("rain_jobs", "job_intent")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `a second held reservation for one invocation is refused by the database`() {
        val fixture = AdminFixture("held_reservation_unique")
        val invocation = UUID.randomUUID()
        val reserve = { key: String ->
            fixture.database.jdbc.update(
                "INSERT INTO rain_jobs.job_intent (id, definition, profile, dedupe_key, mode, invocation_id, reserved_at) " +
                    "VALUES (gen_random_uuid(), 'notes.write', 'standard', ?, 'unique', ?, now())",
                key,
                invocation,
            )
        }

        reserve("first")

        assertThatThrownBy { reserve("second") }.isInstanceOf(DuplicateKeyException::class.java)
    }
}
