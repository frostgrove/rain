package com.gd.rain.access.support

import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.MountedSubject
import com.gd.rain.access.PermissionDef
import com.gd.rain.access.SubjectDirectory
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.web.route.Access
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Base64

const val TICKET_READ: String = "ticket.read"

@RestController
class TicketController {
    @Access(permissions = [TICKET_READ])
    @GetMapping("/tickets")
    fun tickets(): List<String> = listOf("first", "second")
}

/**
 * A helpdesk-shaped application: one kind of subject, one module of grants, one system role. It declares no job of its own:
 * rain-access's recurring work runs on rain-jobs whatever the application enqueues (`AccessWithoutJobDefinitionsIT`).
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
class AccessApplication {
    @Bean
    fun agents(): MemoryDirectory = MemoryDirectory(AGENT)

    @Bean
    fun agentsMounted(): MountedSubject = mounted(AGENT)

    @Bean
    fun helpdeskGrants(): ModuleGrants = ModuleGrants("helpdesk", listOf(PermissionDef(TICKET_READ, "Read tickets")))

    @Bean
    fun administrator(): SystemRoleDeclaration = SystemRoleDeclaration("administrator", "Administrator", grantsEveryPermission = true)

    @Bean
    fun ticketController(): TicketController = TicketController()
}

/** The directory the application declared, for a test to add subjects to. */
fun ConfigurableApplicationContext.directory(): MemoryDirectory = getBean(SubjectDirectory::class.java) as MemoryDirectory

val SIGNING_KEY: String = "base64:" + Base64.getEncoder().encodeToString(KEY)

/** The properties every rain-access application in these tests starts from; [overrides] replace entries by key. */
fun accessProperties(vararg overrides: String): Array<String> {
    val base =
        linkedMapOf(
            "spring.application.name" to "access-it",
            "rain.deployment.stage" to "test",
            "rain.runtime.roles" to "api",
            "rain.persistence.statement-timeout" to "30s",
            "spring.flyway.enabled" to "true",
            "spring.servlet.multipart.enabled" to "false",
            "rain.web.body-limit" to "1MB",
            "rain.web.request-budget" to "30s",
            "rain.web.client-address" to "direct",
            "server.forward-headers-strategy" to "none",
            "rain.health.checks.database" to "informational",
            "rain.jobs.required-recurring" to "",
            "rain.jobs.drain-grace" to "10s",
            "rain.jobs.reserved-connections" to "2",
            "rain.access.web.base-path" to "/api",
            "rain.access.web.delivery" to "both",
            "rain.access.token.issuer" to ISSUER,
            "rain.access.token.audience" to AUDIENCE,
            "rain.access.token.signing-key" to SIGNING_KEY,
            "rain.access.token.access-ttl" to "5m",
            "rain.access.session.ttl" to "30d",
            "rain.access.session.idle-ttl" to "7d",
            "rain.access.session.retention.keep-for" to "7d",
            "rain.access.session.retention.interval" to "1h",
            "rain.access.password.revoke-other-sessions-on-change" to "true",
            "rain.access.hashing.queue" to "16",
            "rain.access.hashing.argon2.memory-kib" to "1024",
            "rain.access.hashing.argon2.iterations" to "1",
            "rain.access.hashing.argon2.parallelism" to "1",
            "resilience4j.bulkhead.instances.rain-access-hashing.max-concurrent-calls" to "4",
            "resilience4j.bulkhead.instances.rain-access-hashing.max-wait-duration" to "1s",
            "rain.access.attempts.store" to "memory",
            "rain.access.attempts.memory.maximum-keys" to "10000",
            "rain.access.revocation.store" to "none",
            "rain.access.gate.throttle.per-minute" to "6000",
            "rain.access.gate.throttle.burst" to "1000",
            "rain.access.gate.throttle.callers" to "1000",
            "rain.access.grants.max-roles-per-subject" to "16",
        )
    overrides.forEach { override ->
        val (key, value) = override.split('=', limit = 2)
        base[key] = value
    }
    return base.map { (key, value) -> "$key=$value" }.toTypedArray()
}
