package com.gd.rain.sample.report

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.boot.command.RainCommand
import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.ConditionalOnRainCommand
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.sample.config.TicketProperties
import com.gd.rain.sample.ticket.TicketKey
import com.gd.rain.sample.ticket.TicketRows
import org.springframework.boot.ApplicationArguments
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** `ticket-report`, declared before any bean exists (`META-INF/spring.factories`). */
class TicketReportDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "print one page of open tickets, oldest first, and exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = emptyMap()

    companion object {
        const val NAME = "ticket-report"
    }
}

/**
 * Prints one keyset page of open tickets, oldest first: one tab-separated line per ticket on standard output —
 * id, priority, assignee (`-` for none), created-at, title — and the count and where the next page starts on standard
 * error. `--after=<created-at>_<id>` continues after a ticket. Every other `--name=value` is a property of the
 * application, as Spring Boot reads it. Exits 0, or 2 for an `--after` it cannot read or a bare argument.
 */
class TicketReport(
    private val tickets: TicketRows,
    private val pageSize: Int,
) : RainCommand {
    override val name: String = TicketReportDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int {
        if (arguments.nonOptionArgs.isNotEmpty()) {
            return usage(output, "it reads --$AFTER=<created-at>_<id>; it does not read ${arguments.nonOptionArgs.joinToString(" ")}")
        }
        val written = arguments.getOptionValues(AFTER)
        val after =
            when {
                written == null -> null
                written.size == 1 -> TicketKey.parse(written.single()) ?: return usage(output, "--$AFTER is <created-at>_<id>")
                else -> return usage(output, "--$AFTER is stated once")
            }
        val page = tickets.openPage(after, pageSize)
        page.items.forEach { ticket ->
            output.out.println(
                listOf(ticket.id, ticket.priority, ticket.assignee ?: "-", ticket.createdAt, ticket.title).joinToString("\t"),
            )
        }
        val next = page.next?.let { "; next page: --$AFTER=${it.written()}" } ?: "; last page"
        output.err.println("$NAME: ${page.items.size} open tickets$next")
        return 0
    }

    private fun usage(
        output: CommandOutput,
        why: String,
    ): Int {
        output.err.println("$NAME: $why")
        return USAGE
    }

    companion object {
        const val AFTER = "after"
        const val USAGE = 2
        private const val NAME = TicketReportDeclaration.NAME
    }
}

@Configuration(proxyBeanMethods = false)
class TicketReportConfiguration {
    @Bean
    @ConditionalOnRainCommand(TicketReportDeclaration.NAME)
    fun ticketReport(
        tickets: TicketRows,
        properties: TicketProperties,
    ): RainCommand = TicketReport(tickets, properties.report.pageSize)
}
