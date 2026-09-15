package com.gd.rain.sample.config

import com.gd.rain.boot.config.BoundSections
import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.CrossSectionRule
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.RuleOutcome
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.boot.runtime.DeploymentStage
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.ProblemCollector
import com.gd.rain.core.config.problems
import com.gd.rain.llm.LlmProperties
import com.gd.rain.sample.seed.SeedProperties
import com.gd.rain.sample.ticket.TicketInputs
import com.gd.rain.web.config.RainWebProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `sample.tickets` — how the helpdesk serves, summarizes, escalates and reports tickets. Every value is stated. */
@ConfigurationProperties(TicketProperties.PREFIX)
data class TicketProperties(
    val pages: Pages,
    val events: Events,
    val summary: Summary,
    val escalation: Escalation,
    val report: Report,
) {
    /** How the ticket list pages: the numbers of the resource's rain-crud `Pagination`. */
    data class Pages(
        val defaultLimit: Int,
        val maxLimit: Int,
        val maxOffset: Long,
        val countCap: Long,
    ) : ConfigurationSection

    /** One server-sent event stream of a ticket. */
    data class Events(
        /** How long one response streams before it ends with `event: end`, after which the client subscribes again. */
        val streamFor: Duration,
        /** How long the stream waits for a change before it writes a keep-alive comment. */
        val pollEvery: Duration,
        /** How long a subscribe waits for the listener to confirm its `LISTEN`. */
        val subscribeTimeout: Duration,
    ) : ConfigurationSection

    /** The `ticket.summarize` job and its profile. */
    data class Summary(
        /** The `rain.llm.pools` pool summaries are admitted to. */
        val pool: String,
        val maxAnswerTokens: Int,
        /** The budget of the drafting step, which is also the profile's step timeout. */
        val stepBudget: Duration,
        val attemptTimeout: Duration,
        val retries: Int,
        val deferrals: Int,
        val backoffInitial: Duration,
        val backoffMaximum: Duration,
        val retention: Duration,
        /** How long a summary waits when every slot of its pool stayed taken for the whole call budget. */
        val deferWhenBusy: Duration,
    ) : ConfigurationSection

    /** The recurring sweep that raises the priority of open tickets nobody has escalated. */
    data class Escalation(
        /** How old an open ticket is before the sweep escalates it. */
        val after: Duration,
        val interval: Duration,
        /** Tickets one statement escalates. */
        val batch: Int,
        /** Batches one pass runs before it leaves the rest to the next pass. */
        val batchesPerRun: Int,
        /** The priority an escalated ticket has at least. */
        val priority: Int,
    ) : ConfigurationSection

    /** The `ticket-report` command. */
    data class Report(
        val pageSize: Int,
    ) : ConfigurationSection

    fun problems(): List<ConfigurationProblem> =
        problems {
            pagesProblems()
            eventsProblems()
            summaryProblems()
            escalationProblems()
            expect(report.pageSize >= 1, "$PREFIX.report.page-size") { "is ${report.pageSize}; a page holds at least one ticket" }
            expect(report.pageSize <= pages.maxLimit, "$PREFIX.report.page-size", ProblemCode.CONTRADICTS) {
                "is ${report.pageSize}, above pages.max-limit ${pages.maxLimit}; the report pages no deeper than the list does"
            }
        }

    private fun ProblemCollector.pagesProblems() {
        val path = "$PREFIX.pages"
        expect(pages.maxLimit in 1 until Int.MAX_VALUE, "$path.max-limit") { "is ${pages.maxLimit}; it is within 1..${Int.MAX_VALUE - 1}" }
        expect(pages.defaultLimit in 1..pages.maxLimit, "$path.default-limit") {
            "is ${pages.defaultLimit}; it is within 1..max-limit ${pages.maxLimit}"
        }
        expect(pages.maxOffset >= 0, "$path.max-offset") { "is ${pages.maxOffset}; it is not negative" }
        expect(
            pages.countCap in 1 until Long.MAX_VALUE,
            "$path.count-cap",
        ) { "is ${pages.countCap}; it is within 1..${Long.MAX_VALUE - 1}" }
    }

    private fun ProblemCollector.eventsProblems() {
        val path = "$PREFIX.events"
        positive(events.streamFor, "$path.stream-for")
        positive(events.pollEvery, "$path.poll-every")
        positive(events.subscribeTimeout, "$path.subscribe-timeout")
        expect(events.pollEvery <= events.streamFor, "$path.poll-every", ProblemCode.CONTRADICTS) {
            "is ${events.pollEvery.written()}, longer than stream-for ${events.streamFor.written()}; a stream would never write a keep-alive"
        }
    }

    private fun ProblemCollector.summaryProblems() {
        val path = "$PREFIX.summary"
        expect(Regex(LlmProperties.POOL_PATTERN).matches(summary.pool), "$path.pool") {
            "\"${summary.pool}\" does not match ${LlmProperties.POOL_PATTERN}"
        }
        expect(summary.maxAnswerTokens >= 1, "$path.max-answer-tokens") { "is ${summary.maxAnswerTokens}; a summary is at least one token" }
        positive(summary.stepBudget, "$path.step-budget")
        positive(summary.attemptTimeout, "$path.attempt-timeout")
        positive(summary.backoffInitial, "$path.backoff-initial")
        positive(summary.retention, "$path.retention")
        positive(summary.deferWhenBusy, "$path.defer-when-busy")
        expect(summary.stepBudget <= summary.attemptTimeout, "$path.step-budget", ProblemCode.CONTRADICTS) {
            "is ${summary.stepBudget.written()}, longer than attempt-timeout ${summary.attemptTimeout.written()} it runs inside"
        }
        expect(summary.backoffMaximum >= summary.backoffInitial, "$path.backoff-maximum", ProblemCode.CONTRADICTS) {
            "is ${summary.backoffMaximum.written()}, below backoff-initial ${summary.backoffInitial.written()}"
        }
        expect(summary.retries >= 0, "$path.retries") { "is ${summary.retries}; it is not negative" }
        expect(summary.deferrals >= 0, "$path.deferrals") { "is ${summary.deferrals}; it is not negative" }
    }

    private fun ProblemCollector.escalationProblems() {
        val path = "$PREFIX.escalation"
        positive(escalation.after, "$path.after")
        positive(escalation.interval, "$path.interval")
        expect(escalation.batch >= 1, "$path.batch") { "is ${escalation.batch}; a batch holds at least one ticket" }
        expect(escalation.batchesPerRun >= 1, "$path.batches-per-run") { "is ${escalation.batchesPerRun}; a pass runs at least one batch" }
        expect(escalation.priority in TicketInputs.PRIORITIES, "$path.priority") {
            "is ${escalation.priority}; a priority is within ${TicketInputs.PRIORITIES.first}..${TicketInputs.PRIORITIES.last}"
        }
    }

    private fun ProblemCollector.positive(
        value: Duration,
        path: String,
    ) {
        expect(value.isPositive, path) { "is ${value.written()}; it has to be positive" }
    }

    companion object {
        const val PREFIX = "sample.tickets"
    }
}

/**
 * The one rule of this application that reads a rain section, `rain.web`: an event stream ends on its own before the
 * request budget runs out. Otherwise the budget would interrupt every stream mid-write, and a client would read a
 * broken stream where the application meant `event: end`.
 */
class EventStreamEndsWithinRequestBudget : CrossSectionRule {
    override val id: String = "sample.event-stream-ends-within-request-budget"
    override val reads: Set<String> = setOf(TicketProperties.PREFIX, RainWebProperties.PREFIX)

    override fun evaluate(
        sections: BoundSections,
        stage: DeploymentStage,
    ): RuleOutcome {
        val streamFor = sections.get(TicketProperties.PREFIX, TicketProperties::class).events.streamFor
        val budget = sections.get(RainWebProperties.PREFIX, RainWebProperties::class).requestBudget
        if (streamFor < budget) return RuleOutcome.Satisfied
        return RuleOutcome.Violated(
            listOf(
                ConfigurationProblem(
                    "${TicketProperties.PREFIX}.events.stream-for",
                    ProblemCode.CONTRADICTS,
                    "is ${streamFor.written()}, not shorter than ${RainWebProperties.PREFIX}.request-budget ${budget.written()}; " +
                        "the budget would cut every stream off before it ends",
                ),
            ),
        )
    }
}

/** Declares the helpdesk's sections and its cross-section rule to rain's configuration validation. */
class SampleConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(
            SectionSpec(TicketProperties.PREFIX, TicketProperties::class, Presence.REQUIRED) { tickets, _ -> tickets.problems() },
            SectionSpec(SeedProperties.PREFIX, SeedProperties::class, Presence.REQUIRED) { seed, _ -> seed.problems() },
        )

    override val rules: List<CrossSectionRule> = listOf(EventStreamEndsWithinRequestBudget())
}
