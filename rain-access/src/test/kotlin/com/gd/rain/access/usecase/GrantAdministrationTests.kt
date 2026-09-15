package com.gd.rain.access.usecase

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.GrantedPermission
import com.gd.rain.access.HolderSearch
import com.gd.rain.access.SignUp
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectRegistrar
import com.gd.rain.access.SubjectType
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.store.DeclaredPermission
import com.gd.rain.access.internal.store.GrantStore
import com.gd.rain.access.internal.store.RoleRow
import com.gd.rain.access.internal.usecase.GrantsService
import com.gd.rain.access.internal.usecase.ProvisioningService
import com.gd.rain.access.internal.usecase.RoleAdministration
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.access.internal.web.AccessAuthentication
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AccessKit
import com.gd.rain.access.support.MemoryDirectory
import com.gd.rain.access.support.MemoryGrants
import com.gd.rain.access.support.START
import com.gd.rain.access.support.mounted
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

private fun faultOf(block: () -> Any?): Fault =
    requireNotNull(runCatching(block).exceptionOrNull() as? Fault) {
        "the call raised no fault"
    }

private fun Fault.pointed(): List<Pair<String, ErrorCode>> = violations.map { it.pointer to it.code }

private fun AccessKit.declare(vararg codes: String) {
    grants.declarePermissions(codes.map { DeclaredPermission(ids.next(), it, "Can $it", "helpdesk") }, START)
}

private fun AccessKit.active(identifier: String): SubjectRef = agents.add(identifier)

/** What a subject holds, and changing it: declared grants, active subjects, a ceiling on roles, and evidence of every change. */
class GrantsAdministrationTest {
    private val kit = AccessKit()
    private val grants = kit.grantsAdministration

    @Test
    fun `granting a role needs a declared role and an active subject, and each refusal names the field it is about`() {
        kit.roles.create("triage", "Triage")
        val subject = kit.active("inactive@example.test")
        val stranger = SubjectRef(AGENT, UUID.randomUUID())

        val unknownRole = faultOf { grants.grantRole(kit.served(subject), subject, "no-such-role") }
        kit.agents.deactivate(subject.id)
        val inactive = faultOf { grants.grantRole(kit.served(subject), subject, "triage") }
        val unknownToTheDirectory = faultOf { grants.grantRole(kit.served(stranger), stranger, "triage") }

        assertThat(unknownRole.kind).isEqualTo(FaultKind.VALIDATION)
        assertThat(unknownRole.pointed()).containsExactly("/role" to AccessErrorCodes.UNKNOWN_ROLE)
        assertThat(inactive.pointed()).containsExactly("/subjectId" to AccessErrorCodes.UNUSABLE_SUBJECT)
        assertThat(unknownToTheDirectory.pointed()).containsExactly("/subjectId" to AccessErrorCodes.UNUSABLE_SUBJECT)
        assertThat(kit.grants.holders(requireNotNull(kit.grants.roleBySlug("triage")).id)).isZero()
    }

    @Test
    fun `a subject holds at most max-roles-per-subject roles, and granting one it already holds at the ceiling changes nothing`() {
        val subject = kit.active("busy@example.test")
        val slugs = listOf("one", "two", "three", "four", "five").onEach { kit.roles.create(it, it.uppercase()) }
        slugs.take(4).forEach { grants.grantRole(kit.served(subject), subject, it) }

        val refusal = faultOf { grants.grantRole(kit.served(subject), subject, "five") }
        grants.grantRole(kit.served(subject), subject, "two")

        assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.CONFLICT to AccessErrorCodes.TOO_MANY_ROLES)
        assertThat(kit.grants.rolesHeldUpTo(subject, 10)).isEqualTo(4)
        assertThat(kit.audit.ofType(AccessAuditTypes.GRANT_CHANGED.id)).hasSize(4)
    }

    @Test
    fun `a grant or a revoke that changes something is recorded once against the subject, and one that changes nothing records nothing`() {
        kit.declare("ticket.read")
        kit.roles.create("triage", "Triage")
        val subject = kit.active("changes@example.test")

        repeat(2) { grants.grantRole(kit.served(subject), subject, "triage") }
        repeat(2) { grants.grantPermission(kit.served(subject), subject, "ticket.read") }
        assertThat(kit.grants.heldCodes(subject, setOf("ticket.read"))).containsExactly("ticket.read")
        repeat(2) { grants.revokeRole(subject, "triage") }
        repeat(2) { grants.revokePermission(subject, "ticket.read") }

        val rows = kit.audit.ofType(AccessAuditTypes.GRANT_CHANGED.id)
        assertThat(rows.map { listOf(it.detail["change"], it.detail["grant_kind"], it.detail["grant"]) }).containsExactly(
            listOf("granted", "role", "triage"),
            listOf("granted", "permission", "ticket.read"),
            listOf("revoked", "role", "triage"),
            listOf("revoked", "permission", "ticket.read"),
        )
        assertThat(rows).allMatch { it.resourceId == subject.resourceId }
        assertThat(kit.grants.heldCodes(subject, setOf("ticket.read"))).isEmpty()
    }

    @Test
    fun `revoking names a declared role or permission, and a permission is granted only when declared and only to an active subject`() {
        kit.declare("ticket.read")
        val subject = kit.active("revoker@example.test")

        assertThat(
            faultOf { grants.revokeRole(subject, "no-such-role") }.pointed(),
        ).containsExactly("/slug" to AccessErrorCodes.UNKNOWN_ROLE)
        assertThat(faultOf { grants.revokePermission(subject, "nothing.declared") }.pointed())
            .containsExactly("/code" to AccessErrorCodes.UNKNOWN_PERMISSION)
        assertThat(faultOf { grants.grantPermission(kit.served(subject), subject, "nothing.declared") }.pointed())
            .containsExactly("/permission" to AccessErrorCodes.UNKNOWN_PERMISSION)
        kit.agents.deactivate(subject.id)
        assertThat(faultOf { grants.grantPermission(kit.served(subject), subject, "ticket.read") }.pointed())
            .containsExactly("/subjectId" to AccessErrorCodes.UNUSABLE_SUBJECT)
        assertThat(kit.audit.ofType(AccessAuditTypes.GRANT_CHANGED.id)).isEmpty()
    }

    @Test
    fun `a subject's roles and direct permissions are keyset pages, each page naming where the next one starts`() {
        kit.declare("alpha.read", "beta.read", "gamma.read")
        val subject = kit.active("pages@example.test")
        listOf("r-one", "r-two", "r-three").forEach {
            kit.roles.create(it, it)
            grants.grantRole(kit.served(subject), subject, it)
        }
        listOf("alpha.read", "beta.read", "gamma.read").forEach { grants.grantPermission(kit.served(subject), subject, it) }

        val firstRoles = grants.rolesOf(subject, null, 2)
        val lastRoles = grants.rolesOf(subject, firstRoles.next, 2)
        val firstPermissions = grants.directPermissionsOf(subject, null, 2)
        val lastPermissions = grants.directPermissionsOf(subject, firstPermissions.next, 2)

        assertThat(firstRoles.items).hasSize(2)
        assertThat(firstRoles.next).isEqualTo(firstRoles.items.last().roleId)
        assertThat(lastRoles.items).hasSize(1)
        assertThat(lastRoles.next).isNull()
        assertThat((firstRoles.items + lastRoles.items).map { it.slug }).containsExactlyInAnyOrder("r-one", "r-two", "r-three")
        assertThat(firstPermissions.next).isEqualTo(firstPermissions.items.last().permissionId)
        assertThat((firstPermissions.items + lastPermissions.items).map(GrantedPermission::code))
            .containsExactlyInAnyOrder("alpha.read", "beta.read", "gamma.read")
        assertThat(lastPermissions.next).isNull()
    }
}

/** Counts the batches a role deletion removes its holders and permissions in. */
private class CountingBatches(
    val delegate: MemoryGrants,
) : GrantStore by delegate {
    val holderBatches = mutableListOf<Int>()
    val permissionBatches = mutableListOf<Int>()

    override fun revokeHoldersBatch(
        role: UUID,
        batch: Int,
    ): Int = delegate.revokeHoldersBatch(role, batch).also { holderBatches += it }

    override fun detachPermissionsBatch(
        role: UUID,
        batch: Int,
    ): Int = delegate.detachPermissionsBatch(role, batch).also { permissionBatches += it }
}

/** Application roles through the API: names told apart by what is wrong with them, changes recorded once, deletes drained. */
class RoleAdministrationRulesTest {
    private val kit = AccessKit()

    @Test
    fun `a role's name is required and at most 256 characters, and the refusal tells a missing name from a long one`() {
        val blank = faultOf { kit.roles.create("lead", "  ") }
        val long = faultOf { kit.roles.create("lead", "x".repeat(ROLE_NAME_LIMIT + 1)) }
        val lead = kit.roles.create("lead", "x".repeat(ROLE_NAME_LIMIT))
        val renamedLong = faultOf { kit.roles.rename(lead.id, "y".repeat(ROLE_NAME_LIMIT + 1)) }

        assertThat(blank.pointed()).containsExactly("/name" to RainErrorCodes.REQUIRED)
        assertThat(long.pointed()).containsExactly("/name" to RainErrorCodes.TOO_LONG)
        assertThat(renamedLong.pointed()).containsExactly("/name" to RainErrorCodes.TOO_LONG)
        assertThat(kit.grants.roleById(lead.id)?.name).isEqualTo("x".repeat(ROLE_NAME_LIMIT))
    }

    @Test
    fun `a rename is recorded against the role with its slug, and a role that does not exist is 404 to every change`() {
        val lead = kit.roles.create("lead", "Lead")

        val renamed = kit.roles.rename(lead.id, "Support lead")

        assertThat(renamed.name).isEqualTo("Support lead")
        val row = kit.audit.ofType(AccessAuditTypes.ROLE_CHANGED.id).last()
        assertThat(listOf(row.resourceId, row.detail["change"], row.detail["slug"])).containsExactly(lead.id.toString(), "renamed", "lead")
        val missing = UUID.randomUUID()
        listOf(
            { kit.roles.get(missing) },
            { kit.roles.attach(missing, "ticket.read") },
            { kit.roles.detach(missing, "ticket.read") },
            { kit.roles.delete(missing) },
            { kit.roles.permissionsOf(missing, null, 10) },
        ).forEach { assertThat(faultOf(it).kind).isEqualTo(FaultKind.NOT_FOUND) }
    }

    @Test
    fun `attaching or detaching what changes nothing records nothing, and detaching names a declared permission`() {
        kit.declare("ticket.read", "ticket.close")
        val lead = kit.roles.create("lead", "Lead")

        repeat(2) { kit.roles.attach(lead.id, "ticket.read") }
        kit.roles.detach(lead.id, "ticket.close")
        repeat(2) { kit.roles.detach(lead.id, "ticket.read") }
        val unknown = faultOf { kit.roles.detach(lead.id, "nothing.declared") }

        assertThat(kit.audit.ofType(AccessAuditTypes.ROLE_CHANGED.id).map { it.detail["change"] to it.detail["permission"] })
            .containsExactly("created" to null, "attached" to "ticket.read", "detached" to "ticket.read")
        assertThat(unknown.pointed()).containsExactly("/code" to AccessErrorCodes.UNKNOWN_PERMISSION)
    }

    @Test
    fun `deleting a role removes its holders and permissions a batch at a time, each in its own transaction, then the role`() {
        val store = CountingBatches(kit.grants)
        val roles = RoleAdministration(store, kit.trail, kit.transactions, kit.ids, 2, kit.clock)
        kit.declare("alpha.read", "beta.read", "gamma.read")
        val lead = roles.create("lead", "Lead")
        listOf("alpha.read", "beta.read", "gamma.read").forEach { roles.attach(lead.id, it) }
        repeat(5) { kit.grants.grantRole(SubjectRef(AGENT, UUID.randomUUID()), lead.id, START) }
        val commits = kit.manager.commits.get()

        roles.delete(lead.id)

        assertThat(store.holderBatches).containsExactly(2, 2, 1)
        assertThat(store.permissionBatches).containsExactly(2, 1)
        assertThat(kit.manager.commits.get() - commits).describedAs("one transaction per batch and one for the role").isEqualTo(6)
        assertThat(kit.grants.roleById(lead.id)).isNull()
        assertThat(kit.audit.ofType(AccessAuditTypes.ROLE_CHANGED.id).filter { it.detail["change"] == "deleted" }).hasSize(1)
    }

    @Test
    fun `a bulk delete deletes each role it names once, however often it names it`() {
        val first = kit.roles.create("first", "First")
        val second = kit.roles.create("second", "Second")

        kit.roles.deleteAll(listOf(first.id, first.id, second.id))

        assertThat(
            kit.audit
                .ofType(AccessAuditTypes.ROLE_CHANGED.id)
                .filter { it.detail["change"] == "deleted" }
                .map { it.resourceId },
        ).containsExactly(first.id.toString(), second.id.toString())
    }

    @Test
    fun `a role's permissions and the catalogue are keyset pages, each page naming where the next one starts`() {
        kit.declare("alpha.read", "beta.read", "gamma.read")
        val lead = kit.roles.create("lead", "Lead")
        listOf("alpha.read", "beta.read", "gamma.read").forEach { kit.roles.attach(lead.id, it) }

        val firstOfRole = kit.roles.permissionsOf(lead.id, null, 2)
        val lastOfRole = kit.roles.permissionsOf(lead.id, firstOfRole.next, 2)
        val firstOfCatalogue = kit.roles.permissionsPage(null, 2)
        val lastOfCatalogue = kit.roles.permissionsPage(firstOfCatalogue.next, 2)

        assertThat(firstOfRole.next).isEqualTo(firstOfRole.items.last().id)
        assertThat(
            (firstOfRole.items + lastOfRole.items).map { it.code },
        ).containsExactlyInAnyOrder("alpha.read", "beta.read", "gamma.read")
        assertThat(lastOfRole.next).isNull()
        assertThat(
            firstOfCatalogue.items.map { it.code } to firstOfCatalogue.next,
        ).isEqualTo(listOf("alpha.read", "beta.read") to "beta.read")
        assertThat(lastOfCatalogue.items.map { it.code } to lastOfCatalogue.next).isEqualTo(listOf("gamma.read") to null)
    }

    private companion object {
        const val ROLE_NAME_LIMIT = 256
    }
}

/** Answers a role another caller created between this call's read and its insert. */
private class RoleCreatedElsewhere(
    val delegate: MemoryGrants,
) : GrantStore by delegate {
    var raced = false

    override fun roleBySlug(slug: String): RoleRow? {
        if (!raced) {
            raced = true
            delegate.createRole(UUID.randomUUID(), slug, "Created elsewhere", START)
            return null
        }
        return delegate.roleBySlug(slug)
    }
}

/** What seeding code is offered, beyond its happy path: evidence only of changes, refusals by name, and races absorbed. */
class ProvisioningRulesTest {
    private val kit = AccessKit()

    private fun provisioning(grants: GrantStore = kit.grants): ProvisioningService =
        ProvisioningService(
            kit.registry,
            kit.credentials,
            grants,
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

    @Test
    fun `setting the default role a type already has records nothing, and changing it records the change`() {
        kit.roles.create("customer", "Customer")
        kit.roles.create("guest", "Guest")
        val provisioning = provisioning()

        provisioning.setDefaultRole(AGENT, "customer")
        provisioning.setDefaultRole(AGENT, "customer")
        provisioning.setDefaultRole(AGENT, "guest")

        assertThat(
            kit.audit.ofType(AccessAuditTypes.DEFAULT_ROLE_CHANGED.id).map { it.detail["slug"] },
        ).containsExactly("customer", "guest")
        assertThat(kit.grants.defaultRoleOf(AGENT)?.slug).isEqualTo("guest")
    }

    @Test
    fun `granting a role to or enrolling a password for a subject of a type nothing serves is 400 unknown_subject_type`() {
        kit.roles.create("triage", "Triage")
        val robot = SubjectRef(SubjectType("robot"), UUID.randomUUID())

        assertThat(faultOf { provisioning().grantRole(robot, "triage") }.code).isEqualTo(AccessErrorCodes.UNKNOWN_SUBJECT_TYPE)
        assertThat(faultOf { provisioning().enrolPassword(robot, "robot@example.test", "correct horse battery") }.code)
            .isEqualTo(AccessErrorCodes.UNKNOWN_SUBJECT_TYPE)
        assertThat(provisioning().hasPassword(robot)).isFalse()
    }

    @Test
    fun `enrolling an identifier another subject signs in with is 409 identifier_taken and writes nothing`() {
        kit.enrolledAgent("taken@example.test")
        val other = kit.active("other@example.test")

        val refusal = faultOf { provisioning().enrolPassword(other, "Taken@example.test", "correct horse battery") }

        assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.CONFLICT to AccessErrorCodes.IDENTIFIER_TAKEN)
        assertThat(provisioning().hasPassword(other)).isFalse()
        assertThat(kit.audit.ofType(AccessAuditTypes.PASSWORD_ENROLLED.id)).isEmpty()
    }

    @Test
    fun `a role another caller created first is used as it is, and is not recorded as created by this call`() {
        kit.declare("ticket.read")
        val raced = RoleCreatedElsewhere(kit.grants)

        val id = provisioning(raced).ensureRole("lead", "Lead", setOf("ticket.read"))

        assertThat(id).isEqualTo(requireNotNull(kit.grants.roleBySlug("lead")).id)
        assertThat(kit.grants.roleById(id)?.name).isEqualTo("Created elsewhere")
        assertThat(kit.audit.ofType(AccessAuditTypes.ROLE_CHANGED.id).map { it.detail["change"] }).containsExactly("attached")
    }

    @Test
    fun `a role name the API would refuse is refused to seeding code too`() {
        assertThatThrownBy {
            provisioning().ensureRole(
                "lead",
                "x".repeat(257),
                emptySet(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { provisioning().ensureRole("lead", " ", emptySet()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(kit.grants.roleBySlug("lead")).isNull()
    }

    @Test
    fun `a holder search passes over holders of a type nothing serves, and a role nobody declared is 422 unknown_role`() {
        val onCall = kit.roles.create("on-call", "On call")
        repeat(2) { kit.grants.grantRole(SubjectRef(SubjectType("aardvark"), UUID.randomUUID()), onCall.id, START) }
        val usable = kit.enrolledAgent("usable@example.test")
        kit.grants.grantRole(usable, onCall.id, START)

        assertThat(provisioning().usableHolderOf("on-call")).isEqualTo(HolderSearch.Found(usable))
        assertThat(
            faultOf { provisioning().usableHolderOf("no-such-role") }.pointed(),
        ).containsExactly("/slug" to AccessErrorCodes.UNKNOWN_ROLE)
    }
}

/** Counts the questions asked of the grant store. */
private class CountingQuestions(
    val delegate: MemoryGrants,
) : GrantStore by delegate {
    val asked = mutableListOf<Set<String>>()

    override fun heldCodes(
        subject: SubjectRef,
        codes: Set<String>,
    ): Set<String> = delegate.heldCodes(subject, codes).also { asked += codes }
}

/** What an application reads about grants: one question per code per request, bounded pages, and only rain-access's principal. */
class GrantsLookupTest {
    private val kit = AccessKit()
    private val store = CountingQuestions(kit.grants)
    private val lookup = GrantsService(kit.registry, store, 3)
    private val subject = kit.active("asker@example.test")

    private fun grantDirectly(vararg codes: String) {
        kit.declare(*codes)
        codes.forEach { kit.grants.grantPermission(subject, requireNotNull(kit.grants.permissionByCode(it)).id, START) }
    }

    @Test
    fun `within one request a code asked again is answered from what the request already learnt`() {
        grantDirectly("ticket.read")
        kit.declare("ticket.close", "ticket.assign")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        try {
            assertThat(lookup.heldBy(subject, setOf("ticket.read", "ticket.close"))).containsExactly("ticket.read")
            assertThat(lookup.heldBy(subject, setOf("ticket.read"))).containsExactly("ticket.read")
            assertThat(lookup.heldBy(subject, setOf("ticket.close", "ticket.assign"))).isEmpty()
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }

        assertThat(store.asked).containsExactly(setOf("ticket.read", "ticket.close"), setOf("ticket.assign"))
    }

    @Test
    fun `outside a request every question reaches the store, and a question about no code reaches nothing`() {
        grantDirectly("ticket.read")

        repeat(2) { assertThat(lookup.heldBy(subject, setOf("ticket.read"))).containsExactly("ticket.read") }
        assertThat(lookup.heldBy(subject, emptySet())).isEmpty()

        assertThat(store.asked).hasSize(2)
    }

    @Test
    fun `a page of direct permissions holds one to max-size items, and any other limit is 422 out_of_range at limit`() {
        grantDirectly("alpha.read", "beta.read", "gamma.read", "delta.read")

        val first = lookup.directPermissionsOf(subject, null, 3)
        val last = lookup.directPermissionsOf(subject, first.next, 3)

        assertThat(first.items).hasSize(3)
        assertThat(first.next).isEqualTo(first.items.last().permissionId)
        assertThat(last.items).hasSize(1)
        assertThat(last.next).isNull()
        listOf(0, 4).forEach { limit ->
            assertThat(faultOf { lookup.directPermissionsOf(subject, null, limit) }.pointed())
                .containsExactly("/limit" to RainErrorCodes.OUT_OF_RANGE)
        }
    }

    @Test
    fun `the directory of a served type is its own, and a type nothing serves has none`() {
        assertThat(lookup.directoryOf(AGENT)).isSameAs(kit.agents)
        assertThat(lookup.directoryOf(SubjectType("robot"))).isNull()
    }

    @Test
    fun `only a request rain-access authenticated has a principal`() {
        val principal = AccessPrincipal(subject, UUID.randomUUID(), START, START.plusSeconds(300))
        try {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated("someone", null, emptyList())
            assertThat(lookup.principalOf()).isNull()

            SecurityContextHolder.getContext().authentication = AccessAuthentication(principal)
            assertThat(lookup.principalOf()).isEqualTo(principal)
        } finally {
            SecurityContextHolder.clearContext()
        }
        assertThat(lookup.principalOf()).isNull()
    }
}

/** A type is served only when it is mounted once, with one directory; a second directory or registrar serves nothing of it. */
class SubjectRegistryServesOnlyWhatIsDeclaredOnceTest {
    private fun registrar(type: SubjectType) =
        object : SubjectRegistrar {
            override val subjectType: SubjectType = type

            override fun register(signUp: SignUp): UUID = UUID.randomUUID()
        }

    @Test
    fun `a type with two directories is refused naming them, and is not served`() {
        val registry = SubjectRegistry(listOf(mounted(AGENT)), listOf(MemoryDirectory(AGENT), MemoryDirectory(AGENT)), emptyList())

        assertThat(registry.problems()).singleElement().matches(
            { it.path == "access.subject:agent" && it.code == ProblemCode.CONTRADICTS && it.message.contains("2 directories") },
            "two directories",
        )
        assertThat(registry.served(AGENT)).isNull()
        assertThat(faultOf { registry.resolve("agent", "subjectType") }.code).isEqualTo(AccessErrorCodes.UNKNOWN_SUBJECT_TYPE)
    }

    @Test
    fun `a type with two registrars is refused, and signs nobody up`() {
        val registry = SubjectRegistry(listOf(mounted(AGENT)), listOf(MemoryDirectory(AGENT)), listOf(registrar(AGENT), registrar(AGENT)))

        assertThat(registry.problems()).singleElement().matches({ it.message.contains("2 registrars") }, "two registrars")
        assertThat(registry.served(AGENT)?.registrar).isNull()
    }
}
