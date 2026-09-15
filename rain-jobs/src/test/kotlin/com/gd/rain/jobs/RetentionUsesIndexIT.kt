package com.gd.rain.jobs

import com.gd.rain.jobs.support.Fixtures
import com.gd.rain.jobs.support.HousekeepingFixture
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Gap 5: a retention batch reaches its rows through the partial retention index under a limit (criterion v1), never by a scan. */
@Tag("integration")
class RetentionUsesIndexIT {
    @Test
    fun `the terminal-invocation batch seeks the partial retention index under a limit`() {
        val fixture = HousekeepingFixture("retention_plan")

        val plan =
            QueryPlans.explain(
                fixture.database.dataSource,
                fixture.database.dsl.renderInlined(fixture.jooq.deleteTerminalQuery("standard", Fixtures.START, 1_000)),
                generic = false,
            )

        assertThat(plan.usesIndex("ix_job_invocation_retention")).describedAs(plan.json).isTrue()
        assertThat(plan.boundedScan("job_invocation")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `the released-reservation batch seeks the partial intent retention index under a limit`() {
        val fixture = HousekeepingFixture("retention_intent_plan")

        val plan =
            QueryPlans.explain(
                fixture.database.dataSource,
                fixture.database.dsl.renderInlined(fixture.jooq.deleteReleasedIntentsQuery("standard", Fixtures.START, 1_000)),
                generic = false,
            )

        assertThat(plan.usesIndex("ix_job_intent_retention")).describedAs(plan.json).isTrue()
        assertThat(plan.boundedScan("job_intent")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }

    @Test
    fun `finding the next profile that owns retained rows is one seek on each retention index`() {
        val fixture = HousekeepingFixture("retention_profile_plan")

        listOf(
            fixture.jooq.nextTerminalProfileQuery("standard") to ("ix_job_invocation_retention" to "job_invocation"),
            fixture.jooq.nextReleasedIntentProfileQuery("standard") to ("ix_job_intent_retention" to "job_intent"),
        ).forEach { (query, expected) ->
            val plan = QueryPlans.explain(fixture.database.dataSource, fixture.database.dsl.renderInlined(query), generic = false)

            assertThat(plan.usesIndex(expected.first)).describedAs(plan.json).isTrue()
            assertThat(plan.boundedScan(expected.second)).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
        }
    }
}
