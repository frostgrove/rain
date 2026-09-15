package com.gd.rain.sample.access

import com.gd.rain.access.GrantsLookup
import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.MountedSubject
import com.gd.rain.access.PermissionDef
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.core.actor.Actor
import com.gd.rain.crud.Caller
import com.gd.rain.crud.CallerLookup
import com.gd.rain.sample.agent.Agents
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** What an agent may do with tickets. The codes are what `@Access` and the ticket policy name. */
object TicketPermissions {
    /** Every ticket; an agent without it reads only the tickets assigned to it. */
    const val READ = "ticket.read"
    const val WRITE = "ticket.write"
    const val DELETE = "ticket.delete"

    val DECLARED: List<PermissionDef> =
        listOf(
            PermissionDef(READ, "Read every ticket, not only the ones assigned to you"),
            PermissionDef(WRITE, "Create, change, close and summarize tickets"),
            PermissionDef(DELETE, "Delete tickets"),
        )
}

/** The roles of the helpdesk: one system role that holds everything, and two the seed command writes. */
object HelpdeskRoles {
    const val ADMINISTRATOR = "administrator"
    const val SUPERVISOR = "supervisor"
    const val RESPONDER = "responder"

    val SUPERVISOR_PERMISSIONS: Set<String> = setOf(TicketPermissions.READ, TicketPermissions.WRITE, TicketPermissions.DELETE)
    val RESPONDER_PERMISSIONS: Set<String> = setOf(TicketPermissions.WRITE)

    /** The roles the seed command enrols one agent for. */
    val SEEDED: Set<String> = setOf(ADMINISTRATOR, SUPERVISOR, RESPONDER)
}

/** rain-crud's caller, answered by rain-access: the request's principal, asked only about the permissions a decision needs. */
class AccessCallers(
    private val grants: GrantsLookup,
) : CallerLookup {
    override fun current(): Caller {
        val principal = grants.principalOf() ?: return Caller.Anonymous
        return object : Caller.Authenticated {
            override val actor: Actor = Actor(principal.subject.type.name, principal.subject.id.toString())

            override fun holdsAll(permissions: Set<String>): Boolean = grants.heldBy(principal.subject, permissions) == permissions
        }
    }
}

@Configuration(proxyBeanMethods = false)
class HelpdeskAccessConfiguration {
    @Bean
    fun helpdeskGrants(): ModuleGrants = ModuleGrants("helpdesk", TicketPermissions.DECLARED)

    @Bean
    fun administratorRole(): SystemRoleDeclaration =
        SystemRoleDeclaration(HelpdeskRoles.ADMINISTRATOR, "Administrator", grantsEveryPermission = true)

    @Bean
    fun agentSubject(): MountedSubject = MountedSubject(Agents.TYPE, Agents.NORMALIZATION)

    @Bean
    fun ticketCallers(grants: GrantsLookup): CallerLookup = AccessCallers(grants)
}
