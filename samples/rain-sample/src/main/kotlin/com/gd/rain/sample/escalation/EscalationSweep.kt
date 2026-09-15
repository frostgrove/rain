package com.gd.rain.sample.escalation

import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.jobs.RecurringWork
import com.gd.rain.sample.config.TicketProperties
import com.gd.rain.sample.ticket.Escalated
import com.gd.rain.sample.ticket.TicketEvents
import com.gd.rain.sample.ticket.TicketKey
import com.gd.rain.sample.ticket.TicketRows
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * `ticket.escalation-sweep`: raises the priority of open tickets older than `sample.tickets.escalation.after` that
 * nobody has escalated. One pass walks them oldest first in keyset batches, each batch one statement in a transaction
 * of its own with its change events, and stops after `batches-per-run` batches; the next pass continues. Exactly one
 * worker in the cluster runs it per interval.
 */
class EscalationSweep(
    private val rows: TicketRows,
    private val events: TicketEvents,
    transactions: PlatformTransactionManager,
    private val settings: TicketProperties.Escalation,
    private val clock: Clock,
) : RecurringWork {
    private val transaction = TransactionTemplate(transactions)

    override val name: String = NAME
    override val interval: Duration = settings.interval

    override fun run() {
        val now = clock.instant()
        val cutoff = now.minus(settings.after)
        var after: TicketKey? = null
        var escalatedInPass = 0
        repeat(settings.batchesPerRun) {
            val batch =
                checkNotNull(
                    transaction.execute {
                        rows.escalate(cutoff, after, settings.batch, settings.priority, now).onEach { events.changed(it.revision) }
                    },
                ) { "an escalation batch answered nothing" }
            escalatedInPass += batch.size
            if (batch.size < settings.batch) {
                report(escalatedInPass)
                return
            }
            after = batch.map(Escalated::key).maxWith(TicketKey.ORDER)
        }
        report(escalatedInPass)
    }

    private fun report(escalated: Int) {
        if (escalated > 0) {
            log
                .atInfo()
                .setMessage("escalated tickets")
                .addKeyValue("count", escalated)
                .log()
        }
    }

    companion object {
        const val NAME = "ticket.escalation-sweep"
        private val log = LoggerFactory.getLogger(EscalationSweep::class.java)
    }
}

/** Recurring work is consumption, so it exists only where the `worker` role runs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnRainRole(RuntimeRole.WORKER)
class EscalationConfiguration {
    @Bean
    fun ticketEscalationSweep(
        rows: TicketRows,
        events: TicketEvents,
        transactions: PlatformTransactionManager,
        properties: TicketProperties,
        clock: Clock,
    ): RecurringWork = EscalationSweep(rows, events, transactions, properties.escalation, clock)
}
