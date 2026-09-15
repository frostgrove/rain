package com.gd.rain.sample.ticket

import com.gd.rain.audit.AuditEventType
import com.gd.rain.audit.AuditRecorder
import com.gd.rain.boot.runtime.ConditionalOnRainRole
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.crud.CallerLookup
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.persistence.RowReader
import com.gd.rain.crud.web.CountBody
import com.gd.rain.crud.web.CrudMvc
import com.gd.rain.crud.web.DeletedBody
import com.gd.rain.crud.web.PageBody
import com.gd.rain.jobs.admin.JobAdministration
import com.gd.rain.persistence.tx.TransactionRetry
import com.gd.rain.realtime.Next
import com.gd.rain.realtime.RealtimeListener
import com.gd.rain.realtime.RealtimePublisher
import com.gd.rain.sample.access.TicketPermissions
import com.gd.rain.sample.config.TicketProperties
import com.gd.rain.sample.summary.SummaryOrdered
import com.gd.rain.sample.summary.SummaryOrders
import com.gd.rain.web.route.Access
import com.gd.rain.web.route.DeclaresItsOwnAccess
import com.gd.rain.web.route.EndpointDeclaration
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Clock
import java.time.Duration

/**
 * The ticket resource's routes: list, count and item through rain-crud, create, change and delete through
 * [TicketWrites]. Their access comes from the resource's policy, through [TicketDeclarations.mounted].
 */
@RestController
@RequestMapping(TicketDeclarations.PREFIX)
@ConditionalOnRainRole(RuntimeRole.API)
class TicketResourceController(
    private val tickets: CrudResource<Ticket>,
    private val writes: TicketWrites,
) : DeclaresItsOwnAccess {
    private val mounted = TicketDeclarations.mounted(tickets)

    @GetMapping
    fun list(request: HttpServletRequest): PageBody<Ticket> = CrudMvc.list(tickets, request)

    @GetMapping("/count")
    fun count(request: HttpServletRequest): CountBody = CrudMvc.count(tickets, request)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
        request: HttpServletRequest,
    ): Ticket = CrudMvc.get(tickets, id, request)

    @PostMapping
    fun create(
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<Ticket> = ResponseEntity.status(HttpStatus.CREATED).body(writes.create(body))

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody(required = false) body: String?,
    ): Ticket = writes.change(id, body)

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String,
    ): DeletedBody {
        writes.delete(id)
        return DeletedBody(1)
    }

    override fun accessDeclarations(): List<EndpointDeclaration> = mounted.declarations()
}

/** What an agent does to one ticket besides changing its fields: close it, order its summary, watch it. */
@RestController
@RequestMapping("${TicketDeclarations.PREFIX}/{id}")
@ConditionalOnRainRole(RuntimeRole.API)
class TicketActionController(
    private val writes: TicketWrites,
    private val summaries: SummaryOrders,
    private val stream: TicketEventStream,
) {
    @Access(permissions = [TicketPermissions.WRITE])
    @PostMapping("/close")
    fun close(
        @PathVariable id: String,
    ): Ticket = writes.close(id)

    @Access(permissions = [TicketPermissions.WRITE])
    @PostMapping("/summary")
    fun summarize(
        @PathVariable id: String,
    ): ResponseEntity<SummaryOrdered> = ResponseEntity.accepted().body(summaries.order(id))

    @Access(authenticated = true, why = TicketDeclarations.READ_WHY)
    @GetMapping("/events")
    fun events(
        @PathVariable id: String,
        response: HttpServletResponse,
    ) {
        stream.serve(id, response)
    }
}

/**
 * A ticket's changes as server-sent events: `event: ticket` with the ticket as it is, then `event: change` with each
 * committed change's revision, and `event: end` when the stream ends — after `sample.tickets.events.stream-for`, or
 * when the subscription ends (`gap`, `overflow`, `closed`) — after which the client reads again and subscribes again.
 *
 * The subscription is taken before the ticket is read, so a change committed in between is buffered rather than
 * missed. The stream is served on its request thread and ends before `rain.web.request-budget` (a configuration rule).
 */
class TicketEventStream(
    private val tickets: CrudResource<Ticket>,
    private val listener: RealtimeListener,
    private val settings: TicketProperties.Events,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    fun serve(
        id: String,
        response: HttpServletResponse,
    ) {
        val ticketId = TicketWrites.idOf(tickets.get(id, emptyMap()))
        listener.subscribe(TicketEvents.channelOf(ticketId), settings.subscribeTimeout).use { subscription ->
            val current = tickets.get(id, emptyMap())
            response.status = HttpServletResponse.SC_OK
            response.contentType = CONTENT_TYPE
            response.setHeader("Cache-Control", "no-store")
            val out = response.outputStream
            try {
                out.event("ticket", json.writeValueAsString(current))
                val until = clock.instant().plus(settings.streamFor)
                while (true) {
                    val left = Duration.between(clock.instant(), until)
                    if (!left.isPositive) {
                        out.event("end", """{"reason":"renew"}""")
                        return
                    }
                    when (val next = subscription.poll(minOf(settings.pollEvery, left))) {
                        is Next.Event -> {
                            out.event("change", next.event.payload)
                        }

                        Next.Idle -> {
                            out.comment()
                        }

                        is Next.Ended -> {
                            out.event("end", """{"reason":"${next.end.name.lowercase()}"}""")
                            return
                        }
                    }
                }
            } catch (gone: IOException) {
                log.debug("a ticket event stream's client went away", gone)
            }
        }
    }

    private fun ServletOutputStream.event(
        name: String,
        data: String,
    ) {
        write("event: $name\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    private fun ServletOutputStream.comment() {
        write(": keep-alive\n\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    private companion object {
        const val CONTENT_TYPE = "text/event-stream;charset=UTF-8"
        val log = LoggerFactory.getLogger(TicketEventStream::class.java)
    }
}

@Configuration(proxyBeanMethods = false)
class TicketConfiguration {
    @Bean
    fun sampleErrorCodes(): ErrorCodeCatalog = SampleErrorCodes

    @Bean
    fun ticketCreatedEvent(): AuditEventType = TicketAudit.CREATED

    @Bean
    fun ticketUpdatedEvent(): AuditEventType = TicketAudit.UPDATED

    @Bean
    fun ticketClosedEvent(): AuditEventType = TicketAudit.CLOSED

    @Bean
    fun ticketDeletedEvent(): AuditEventType = TicketAudit.DELETED

    @Bean
    fun ticketStore(
        dsl: DSLContext,
        ids: IdGenerator,
    ): JooqResourceStore<Ticket> = JooqResourceStore(TicketFields.SCHEMA, dsl, ids, RowReader.fields(TicketFields.SCHEMA))

    /** One resource for every agent: the policy's scope gives each caller its rows. */
    @Bean
    fun ticketResource(
        store: JooqResourceStore<Ticket>,
        callers: CallerLookup,
        properties: TicketProperties,
    ): CrudResource<Ticket> = TicketDeclarations.resource(store, callers, properties.pages)

    @Bean
    fun ticketRows(dsl: DSLContext): TicketRows = TicketRows(dsl)

    @Bean
    fun ticketEvents(
        publisher: RealtimePublisher,
        json: JsonMapper,
    ): TicketEvents = TicketEvents(publisher, json)

    @Bean
    fun ticketWrites(
        tickets: CrudResource<Ticket>,
        rows: TicketRows,
        audit: AuditRecorder,
        events: TicketEvents,
        jobs: JobAdministration,
        retry: TransactionRetry,
        transactions: PlatformTransactionManager,
        clock: Clock,
    ): TicketWrites = TicketWrites(tickets, rows, audit, events, jobs, retry, transactions, clock)

    /** The listener runs only where the `api` role does, and so do the streams it feeds. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnRainRole(RuntimeRole.API)
    class Streaming {
        @Bean
        fun ticketEventStream(
            tickets: CrudResource<Ticket>,
            listener: RealtimeListener,
            properties: TicketProperties,
            json: JsonMapper,
            clock: Clock,
        ): TicketEventStream = TicketEventStream(tickets, listener, properties.events, json, clock)
    }
}
