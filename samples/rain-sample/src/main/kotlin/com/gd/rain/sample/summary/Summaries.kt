package com.gd.rain.sample.summary

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.keyOf
import com.gd.rain.crud.CrudResource
import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.BackoffLadder
import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.EnqueueOutcome
import com.gd.rain.jobs.JobDeferredException
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobPermanentException
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.WorkQueue
import com.gd.rain.llm.LlmBreakerHeld
import com.gd.rain.llm.LlmBudgetExhausted
import com.gd.rain.llm.LlmClass
import com.gd.rain.llm.LlmGateway
import com.gd.rain.llm.LlmMessage
import com.gd.rain.llm.LlmProperties
import com.gd.rain.llm.LlmRequest
import com.gd.rain.llm.OutputBudget
import com.gd.rain.persistence.tx.TransactionRetry
import com.gd.rain.resilience.BreakerDeclaration
import com.gd.rain.resilience.BreakerName
import com.gd.rain.resilience.BreakerRegistry
import com.gd.rain.sample.config.TicketProperties
import com.gd.rain.sample.ticket.Ticket
import com.gd.rain.sample.ticket.TicketEvents
import com.gd.rain.sample.ticket.TicketFields
import com.gd.rain.sample.ticket.TicketRows
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** The payload of `ticket.summarize`: which ticket. The ticket itself is read when the job runs. */
data class SummarizeTicket(
    val ticketId: UUID,
)

data class SummaryOrdered(
    val invocation: UUID,
    /** `scheduled`, or `deduplicated` when a summary of the ticket was already ordered and has not finished. */
    val outcome: String,
)

object SummaryJobs {
    const val DEFINITION = "ticket.summarize"
    const val PROFILE = "summaries"
    val BREAKER: BreakerName = BreakerName("summarizer")
    const val INSTRUCTION = "Summarize this helpdesk ticket in one or two sentences for the agent who picks it up."

    /** Every job about one ticket carries this key, so deleting the ticket cancels all of them together. */
    fun subjectOf(ticket: UUID): SubjectKey = SubjectKey("ticket:$ticket")
}

/**
 * What runs at the start of the drafting step, before the model is asked. Nothing, in a deployment; a test puts a
 * latch here to hold an attempt inside its step for as long as the test needs.
 */
fun interface SummaryPacing {
    fun beforeDraft(ticket: UUID)

    companion object {
        val NONE: SummaryPacing = SummaryPacing { }
    }
}

/** A summary drafted, or why there is none. */
sealed interface Draft {
    /** The ticket was deleted: there is nothing to summarize, and nothing to retry. */
    data object Missing : Draft

    data class Ready(
        val text: String,
    ) : Draft

    /** Not now: the dependency is holding calls or busy, and asking again later can succeed. */
    data class Deferred(
        val after: Duration,
        val why: String,
    ) : Draft

    /** Never: asking again cannot change the answer. */
    data class Refused(
        val why: String,
    ) : Draft
}

/**
 * Drafts a ticket's summary with the language model and stores it. The work of `ticket.summarize`, called directly;
 * [SummarizeTicketHandler] only maps its outcome onto the job's.
 */
class TicketSummaries(
    private val rows: TicketRows,
    private val llm: ObjectProvider<LlmGateway>,
    private val breakers: BreakerRegistry,
    private val events: TicketEvents,
    private val settings: TicketProperties.Summary,
    private val clock: Clock,
) {
    fun draft(ticket: UUID): Draft {
        val text = rows.text(ticket) ?: return Draft.Missing
        val messages = listOf(LlmMessage.system(SummaryJobs.INSTRUCTION), LlmMessage.user("${text.title}\n\n${text.body}"))
        val gateway = llm.getObject()
        val tokens =
            when (val budget = gateway.outputBudget(messages, settings.maxAnswerTokens)) {
                is OutputBudget.Fits -> {
                    budget.tokens
                }

                is OutputBudget.NoRoom -> {
                    return Draft.Refused(
                        "the ticket takes ${budget.promptTokens} tokens and leaves ${budget.shortBy} too few for its summary",
                    )
                }

                is OutputBudget.NotEvaluated -> {
                    return Draft.Refused("the room for a summary was not evaluated: ${budget.reason}")
                }
            }
        return try {
            Draft.Ready(gateway.complete(settings.pool, LlmClass.BULK, LlmRequest(messages, maxOutputTokens = tokens)).text)
        } catch (held: LlmBreakerHeld) {
            // A breaker forced open, or one whose open wait is spent, tells no remaining wait; the declared open wait is the one it has.
            val wait = held.retryAfter?.takeIf { it.isPositive } ?: breakers.openWait(SummaryJobs.BREAKER)
            Draft.Deferred(wait, "the ${SummaryJobs.BREAKER} breaker withholds model calls")
        } catch (busy: LlmBudgetExhausted) {
            Draft.Deferred(settings.deferWhenBusy, "every ${busy.klass.name.lowercase()} slot of pool ${busy.pool} stayed taken")
        }
    }

    /** Stores [summary] as a new version of the ticket and announces it. Called inside the job's fence. */
    fun store(
        ticket: UUID,
        summary: String,
    ) {
        val revision = rows.storeSummary(ticket, summary, clock.instant()) ?: return
        events.changed(revision)
    }
}

/**
 * `ticket.summarize`: a drafting step bounded by `sample.tickets.summary.step-budget`, then the summary stored under
 * the ticket's advisory lock inside the job's fence, so a summary never commits under a lease another attempt holds.
 */
class SummarizeTicketHandler(
    override val definition: JobDefinition<SummarizeTicket>,
    private val summaries: TicketSummaries,
    private val pacing: SummaryPacing,
    private val stepBudget: Duration,
) : JobHandler<SummarizeTicket> {
    override fun handle(
        payload: SummarizeTicket,
        attempt: Attempt,
    ) {
        val draft =
            attempt.step(stepBudget) {
                pacing.beforeDraft(payload.ticketId)
                summaries.draft(payload.ticketId)
            }
        when (draft) {
            // The ticket was deleted before its summary was drafted: there is nothing to store and nothing to retry.
            Draft.Missing -> {
                return
            }

            is Draft.Deferred -> {
                throw JobDeferredException(draft.after, draft.why)
            }

            is Draft.Refused -> {
                throw JobPermanentException(draft.why)
            }

            is Draft.Ready -> {
                attempt.fenced(listOf(Exclusively(keyOf("ticket", payload.ticketId.toString())))) {
                    summaries.store(payload.ticketId, draft.text)
                }
            }
        }
    }
}

/** Orders a ticket's summary: one live order per ticket, at the ticket's priority, cancellable with the ticket. */
class SummaryOrders(
    private val tickets: CrudResource<Ticket>,
    private val queue: WorkQueue,
    private val definition: JobDefinition<SummarizeTicket>,
    private val retry: TransactionRetry,
    transactions: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactions)

    fun order(id: String): SummaryOrdered {
        val outcome =
            retry.run {
                checkNotNull(
                    transaction.execute {
                        val ticket = tickets.get(id, emptyMap())
                        val ticketId = ticket.getValue(TicketFields.ID.name) as UUID
                        val subject = SummaryJobs.subjectOf(ticketId)
                        queue.enqueue(
                            definition,
                            SummarizeTicket(ticketId),
                            EnqueueOptions(
                                dedupe = Dedupe.Unique(subject.value),
                                priority = JobPriority(ticket.getValue(TicketFields.PRIORITY.name) as Int),
                                subjectKey = subject,
                            ),
                        )
                    },
                ) { "ordering a summary answered nothing" }
            }
        val written =
            when (outcome) {
                is EnqueueOutcome.Scheduled -> "scheduled"
                is EnqueueOutcome.Deduplicated -> "deduplicated"
            }
        return SummaryOrdered(outcome.invocation, written)
    }
}

/**
 * The model this sample asks: local and deterministic, so the stand runs without a provider or a network. It answers
 * with the ticket's own words, cut to [LENGTH] characters. A deployment declares its provider's `ChatModel` bean in its
 * place; nothing else changes.
 */
class HelpdeskSummaryModel : ChatModel {
    override fun call(prompt: Prompt): ChatResponse {
        val asked =
            prompt.instructions.filterIsInstance<UserMessage>().joinToString(" ") {
                checkNotNull(it.text) { "a user message rain-llm builds carries its text" }
            }
        val summary = summaryOf(asked)
        return ChatResponse(
            listOf(Generation(AssistantMessage(summary), ChatGenerationMetadata.builder().finishReason("stop").build())),
            ChatResponseMetadata.builder().build(),
        )
    }

    companion object {
        const val LENGTH = 160

        fun summaryOf(text: String): String =
            "Summary: " +
                text
                    .split(WHITESPACE)
                    .filter(String::isNotEmpty)
                    .joinToString(" ")
                    .take(LENGTH)

        private val WHITESPACE = Regex("\\s+")
    }
}

/** The summary pool is one `rain.llm` configures; otherwise every summary would fail at its first ask instead of the start. */
class SummaryPoolCheck(
    private val llm: ObjectProvider<LlmProperties>,
    private val pool: String,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val configured = llm.ifAvailable
        return when {
            configured == null -> {
                listOf(
                    ConfigurationProblem(
                        LlmProperties.ENABLED,
                        ProblemCode.REQUIRED,
                        "is not true; ticket summaries are drafted by the model, so ${LlmProperties.PREFIX} is enabled",
                    ),
                )
            }

            pool !in configured.pools -> {
                listOf(
                    ConfigurationProblem(
                        "${TicketProperties.PREFIX}.summary.pool",
                        ProblemCode.CONTRADICTS,
                        "names pool $pool, which ${LlmProperties.PREFIX}.pools does not configure",
                    ),
                )
            }

            else -> {
                emptyList()
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
class SummaryConfiguration {
    @Bean
    fun summariesProfile(properties: TicketProperties): JobProfile {
        val summary = properties.summary
        return JobProfile(
            id = SummaryJobs.PROFILE,
            attemptTimeout = summary.attemptTimeout,
            stepTimeout = summary.stepBudget,
            backoff = BackoffLadder(summary.backoffInitial, summary.backoffMaximum),
            retries = summary.retries,
            deferrals = summary.deferrals,
            retention = summary.retention,
        )
    }

    @Bean
    fun summarizeTicket(): JobDefinition<SummarizeTicket> = JobDefinition.of(SummaryJobs.DEFINITION, SummaryJobs.PROFILE)

    @Bean
    fun summaryPacing(): SummaryPacing = SummaryPacing.NONE

    @Bean
    fun summarizerBreaker(): BreakerDeclaration = BreakerDeclaration(SummaryJobs.BREAKER, healthCode = "summarizer")

    @Bean
    fun helpdeskSummaryModel(): ChatModel = HelpdeskSummaryModel()

    @Bean
    fun summaryPoolCheck(
        llm: ObjectProvider<LlmProperties>,
        properties: TicketProperties,
    ): ConfigurationCheck = SummaryPoolCheck(llm, properties.summary.pool)

    @Bean
    fun ticketSummaries(
        rows: TicketRows,
        llm: ObjectProvider<LlmGateway>,
        breakers: BreakerRegistry,
        events: TicketEvents,
        properties: TicketProperties,
        clock: Clock,
    ): TicketSummaries = TicketSummaries(rows, llm, breakers, events, properties.summary, clock)

    @Bean
    fun summarizeTicketHandler(
        definition: JobDefinition<SummarizeTicket>,
        summaries: TicketSummaries,
        pacing: SummaryPacing,
        properties: TicketProperties,
    ): JobHandler<SummarizeTicket> = SummarizeTicketHandler(definition, summaries, pacing, properties.summary.stepBudget)

    @Bean
    fun summaryOrders(
        @Qualifier("ticketResource") tickets: CrudResource<Ticket>,
        queue: WorkQueue,
        definition: JobDefinition<SummarizeTicket>,
        retry: TransactionRetry,
        transactions: PlatformTransactionManager,
    ): SummaryOrders = SummaryOrders(tickets, queue, definition, retry, transactions)
}
