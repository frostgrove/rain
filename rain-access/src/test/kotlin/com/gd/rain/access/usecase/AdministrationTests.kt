package com.gd.rain.access.usecase

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.Enrolment
import com.gd.rain.access.HolderSearch
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.internal.attempt.AttemptKeys
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.store.DeclaredPermission
import com.gd.rain.access.internal.usecase.ProvisioningService
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AGENT_OF_TESTS
import com.gd.rain.access.support.AccessKit
import com.gd.rain.access.support.FakeHasher
import com.gd.rain.access.support.START
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

private fun faultOf(change: () -> Any?): Fault =
    requireNotNull(runCatching { change() }.exceptionOrNull() as? Fault) { "the change raised no fault" }

private fun codesOf(fault: Fault): Set<ErrorCode> = setOf(fault.code) + fault.violations.map { it.code }

private fun AccessKit.declare(vararg codes: String) {
    grants.declarePermissions(codes.map { DeclaredPermission(ids.next(), it, "Can $it", "helpdesk") }, START)
}

/** The role administration's refusals: a slug in its declared form and once, a name, and system roles left alone. */
class RoleAdministrationTest {
    private val kit = AccessKit()

    @Test
    fun `a slug is stated in its declared form and once, and a role has a name`() {
        val malformed = faultOf { kit.roles.create("Support Lead", "Support lead") }
        kit.roles.create("support-lead", "Support lead")
        val taken = faultOf { kit.roles.create("support-lead", "Again") }
        val nameless = faultOf { kit.roles.create("night-shift", " ") }

        assertThat(malformed.kind).isEqualTo(FaultKind.VALIDATION)
        assertThat(malformed.violations.single().path).isEqualTo(path("slug"))
        assertThat(malformed.violations.single().code).isEqualTo(RainErrorCodes.INVALID_FORMAT)
        assertThat(taken.violations.single().code).isEqualTo(RainErrorCodes.UNIQUE)
        assertThat(nameless.violations.single().path).isEqualTo(path("name"))
        assertThat(kit.grants.rolesPage(null, 10).map { it.slug }).containsExactly("support-lead")
    }

    @Test
    fun `a system role is never renamed, stripped or deleted, alone or in a bulk delete, and nothing else is deleted with it`() {
        kit.declare("ticket.read")
        val system = kit.grants.declareSystemRole(kit.ids.next(), "administrator", "Administrator", false, START)
        kit.grants.attach(system, listOf(requireNotNull(kit.grants.permissionByCode("ticket.read")).id), START)
        val lead = kit.roles.create("support-lead", "Support lead")

        listOf(
            { kit.roles.rename(system, "Root") },
            { kit.roles.detach(system, "ticket.read") },
            { kit.roles.delete(system) },
            { kit.roles.deleteAll(listOf(lead.id, system)) },
        ).forEach { change ->
            val refusal = faultOf(change)
            assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.FORBIDDEN to AccessErrorCodes.SYSTEM_ROLE)
        }

        assertThat(kit.grants.roleById(lead.id)).isNotNull()
        assertThat(kit.grants.permissionCodesOf(system)).containsExactly("ticket.read")
        assertThat(kit.audit.ofType(AccessAuditTypes.ROLE_CHANGED.id).map { it.detail["change"] }).containsExactly("created")
    }

    @Test
    fun `attaching a code no module declares is refused naming the field, and an unknown role is not found`() {
        val lead = kit.roles.create("support-lead", "Support lead")

        assertThat(faultOf { kit.roles.attach(lead.id, "nothing.declared") }.violations.single().code)
            .isEqualTo(AccessErrorCodes.UNKNOWN_PERMISSION)
        assertThat(faultOf { kit.roles.rename(UUID.randomUUID(), "Anything") }.kind).isEqualTo(FaultKind.NOT_FOUND)
    }
}

/** What an application's seeding code is offered: roles merged in, never detached; holders searched within a declared budget. */
class AccessProvisioningTest {
    private val kit = AccessKit()
    private val provisioning =
        ProvisioningService(
            kit.registry,
            kit.credentials,
            kit.grants,
            kit.grantsAdministration,
            kit.hasher,
            kit.bulkhead,
            kit.rules,
            kit.trail,
            kit.transactions,
            kit.ids,
            2,
            2,
            kit.clock,
        )

    private fun holder(
        role: UUID,
        active: Boolean,
        password: Boolean,
    ): SubjectRef {
        val identifier = "holder-${UUID.randomUUID()}@example.test"
        val subject = kit.agents.add(identifier)
        if (!active) kit.agents.deactivate(subject.id)
        if (password) kit.credentials.insert(kit.ids.next(), subject, identifier, "fake:password", START)
        kit.grants.grantRole(subject, role, START)
        return subject
    }

    @Test
    fun `a missing role is created as an application role with its codes, and a second call only adds what is missing`() {
        kit.declare("ticket.read", "ticket.close", "ticket.assign")
        val id = provisioning.ensureRole("support-lead", "Support lead", setOf("ticket.read"))
        kit.roles.attach(id, "ticket.assign")

        assertThat(provisioning.ensureRole("support-lead", "Renamed", setOf("ticket.read", "ticket.close"))).isEqualTo(id)

        val role = requireNotNull(kit.grants.roleById(id))
        assertThat(role.isSystem).isFalse()
        assertThat(role.name).isEqualTo("Support lead")
        assertThat(kit.grants.permissionCodesOf(id)).containsExactlyInAnyOrder("ticket.read", "ticket.close", "ticket.assign")
    }

    @Test
    fun `a code no module declares, a slug no role has and a type nothing serves are each refused by name`() {
        kit.declare("ticket.read")

        assertThat(codesOf(faultOf { provisioning.ensureRole("support-lead", "Support lead", setOf("nothing.declared")) }))
            .contains(AccessErrorCodes.UNKNOWN_PERMISSION)
        assertThat(codesOf(faultOf { provisioning.setDefaultRole(AGENT, "no-such-role") })).contains(AccessErrorCodes.UNKNOWN_ROLE)
        assertThat(codesOf(faultOf { provisioning.setDefaultRole(SubjectType("robot"), "support-lead") }))
            .contains(AccessErrorCodes.UNKNOWN_SUBJECT_TYPE)
    }

    @Test
    fun `a password is enrolled once under the normalised identifier`() {
        val subject = kit.agents.add("ada@example.test")

        assertThat(provisioning.enrolPassword(subject, " Ada@Example.test ", "correct horse battery")).isEqualTo(Enrolment.Enrolled)
        assertThat(provisioning.enrolPassword(subject, "ada@example.test", "another long password")).isEqualTo(Enrolment.AlreadyEnrolled)

        assertThat(kit.credentials.findBySubject(subject)?.identifier).isEqualTo("ada@example.test")
        assertThat(provisioning.hasPassword(subject)).isTrue()
        assertThat(kit.audit.ofType(AccessAuditTypes.PASSWORD_ENROLLED.id)).hasSize(1)
    }

    @Test
    fun `a usable holder is found, none is found, or the page budget runs out and nothing is decided`() {
        val examined = kit.roles.create("on-call", "On call").id
        repeat(3) { holder(examined, active = true, password = false) }
        assertThat(provisioning.usableHolderOf("on-call")).isEqualTo(HolderSearch.NoneFound)

        repeat(2) { holder(examined, active = false, password = true) }
        assertThat(provisioning.usableHolderOf("on-call")).isEqualTo(HolderSearch.NotEvaluated(2))

        val backup = kit.roles.create("backup", "Backup").id
        holder(backup, active = false, password = true)
        val usable = holder(backup, active = true, password = true)
        assertThat(provisioning.usableHolderOf("backup")).isEqualTo(HolderSearch.Found(usable))
    }
}

/** Gap 17 and 23: a failed attempt is its own row, carrying a fingerprint of the identifier and never the identifier. */
class SignInAttemptAuditTest {
    @Test
    fun `a failed attempt is recorded independently with the normalised identifier's fingerprint`() {
        val kit = AccessKit()
        val subject = kit.enrolledAgent()

        runCatching { kit.login.signIn(kit.served(subject), " Ada@Example.test ", "not the password", AGENT_OF_TESTS) }

        val failed = kit.audit.independent.single { it.type == AccessAuditTypes.SIGN_IN_FAILED }
        assertThat(failed.detail["identifier_fp"]).isEqualTo(AttemptKeys.fingerprint("ada@example.test"))
        assertThat(failed.detail["address"]).isEqualTo(AGENT_OF_TESTS.address)
        assertThat(failed.detail.keys.map { failed.detail[it].toString() }).noneMatch { it.contains("@example") }
        assertThat(failed.resourceId).isEqualTo(AGENT.name)
        assertThat(failed.actor).isNull()
        assertThat(kit.audit.inTransaction).isEmpty()
    }

    @Test
    fun `an identifier nobody signs in with costs one verification and leaves the same kind of row`() {
        val hasher = FakeHasher()
        val kit = AccessKit(hasher = hasher)

        runCatching {
            kit.login.signIn(
                requireNotNull(kit.registry.served(AGENT)),
                "nobody@example.test",
                "whatever password",
                AGENT_OF_TESTS,
            )
        }

        assertThat(hasher.verifications.get()).isEqualTo(1)
        assertThat(kit.audit.all.map { it.type }).containsExactly(AccessAuditTypes.SIGN_IN_FAILED)
    }
}
