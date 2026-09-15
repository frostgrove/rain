package com.gd.rain.access.usecase

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.PermissionDef
import com.gd.rain.access.SignUp
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectRegistrar
import com.gd.rain.access.SubjectType
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.access.internal.usecase.AccessTransactions
import com.gd.rain.access.internal.usecase.CatalogueSynchronizer
import com.gd.rain.access.internal.usecase.GrantDeclarationsCheck
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.MemoryDirectory
import com.gd.rain.access.support.MemoryGrants
import com.gd.rain.access.support.NoOpTransactionManager
import com.gd.rain.access.support.SERVICE
import com.gd.rain.access.support.START
import com.gd.rain.access.support.mounted
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

private fun module(
    name: String,
    vararg codes: String,
    roles: Map<String, Set<String>> = emptyMap(),
) = ModuleGrants(name, codes.map { PermissionDef(it, "Can $it") }, roles)

private val ADMINISTRATOR = SystemRoleDeclaration("administrator", "Administrator", grantsEveryPermission = true)
private val TRIAGE = SystemRoleDeclaration("triage", "Triage", grantsEveryPermission = false)

class CatalogueSynchronizerTest {
    private val store = MemoryGrants()

    private fun synchronize(
        modules: List<ModuleGrants>,
        roles: List<SystemRoleDeclaration> = listOf(ADMINISTRATOR, TRIAGE),
    ) = CatalogueSynchronizer(
        store,
        AccessTransactions(NoOpTransactionManager()),
        modules,
        roles,
        IdGenerator(UuidV7Ids::next),
        2,
        MutableClock(START),
    )

    @Test
    fun `every declared code is written with its module and name, in chunks`() {
        synchronize(
            listOf(module("tickets", "ticket.read", "ticket.write", "ticket.close"), module("billing", "invoice.read", "invoice.send")),
        ).synchronize()

        assertThat(
            store.permissionsPage(null, 10).map {
                it.code
            },
        ).containsExactly("invoice.read", "invoice.send", "ticket.close", "ticket.read", "ticket.write")
        assertThat(store.permissionByCode("invoice.send")?.module).isEqualTo("billing")
        assertThat(store.permissionByCode("ticket.read")?.name).isEqualTo("Can ticket.read")
    }

    @Test
    fun `a system role is written as system and a module's codes for it are attached`() {
        synchronize(listOf(module("tickets", "ticket.read", "ticket.close", roles = mapOf("triage" to setOf("ticket.read"))))).synchronize()

        val triage = requireNotNull(store.roleBySlug("triage"))
        assertThat(triage.isSystem).isTrue()
        assertThat(triage.grantsEveryPermission).isFalse()
        assertThat(store.permissionCodesOf(triage.id)).containsExactly("ticket.read")
        assertThat(store.roleBySlug("administrator")?.grantsEveryPermission).isTrue()
    }

    @Test
    fun `a role granting every permission holds a permission declared after it, with no row for it`() {
        synchronize(listOf(module("tickets", "ticket.read"))).synchronize()
        val holder = SubjectRef(AGENT, UUID.randomUUID())
        store.grantRole(holder, requireNotNull(store.roleBySlug("administrator")).id, START)

        synchronize(listOf(module("tickets", "ticket.read"), module("billing", "invoice.send"))).synchronize()

        assertThat(
            store.heldCodes(holder, setOf("invoice.send", "ticket.read", "undeclared.code")),
        ).containsExactlyInAnyOrder("invoice.send", "ticket.read")
        assertThat(store.permissionCodesOf(requireNotNull(store.roleBySlug("administrator")).id)).isEmpty()
    }

    @Test
    fun `a second run changes nothing and a code taken out of the declarations keeps its attachment`() {
        val first =
            listOf(module("tickets", "ticket.read", "ticket.close", roles = mapOf("triage" to setOf("ticket.read", "ticket.close"))))
        synchronize(first).synchronize()
        val ids = store.permissionsPage(null, 10).map { it.id }

        synchronize(listOf(module("tickets", "ticket.read", roles = mapOf("triage" to setOf("ticket.read"))))).synchronize()

        assertThat(store.permissionsPage(null, 10).map { it.id }).containsExactlyElementsOf(ids)
        assertThat(
            store.permissionCodesOf(requireNotNull(store.roleBySlug("triage")).id),
        ).containsExactlyInAnyOrder("ticket.read", "ticket.close")
    }

    @Test
    fun `a code another replica wrote first keeps its row`() {
        store.declarePermissions(
            listOf(
                com.gd.rain.access.internal.store
                    .DeclaredPermission(UUID.randomUUID(), "ticket.read", "A peer's name", "tickets"),
            ),
            START,
        )

        synchronize(listOf(module("tickets", "ticket.read"))).synchronize()

        assertThat(store.permissionByCode("ticket.read")?.name).isEqualTo("A peer's name")
    }

    @Test
    fun `declarations that contradict each other are never written`() {
        val contradicting = listOf(module("tickets", "ticket.read"), module("support", "ticket.read"))

        assertThatThrownBy { synchronize(contradicting).synchronize() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(store.permissionsPage(null, 10)).isEmpty()
    }

    @Test
    fun `starting the lifecycle synchronises before anything later in the start`() {
        val synchronizer = synchronize(listOf(module("tickets", "ticket.read")))

        synchronizer.start()

        assertThat(synchronizer.isRunning).isTrue()
        assertThat(synchronizer.phase).isEqualTo(CatalogueSynchronizer.PHASE)
        assertThat(store.permissionByCode("ticket.read")).isNotNull()
    }
}

class GrantDeclarationsCheckTest {
    @Test
    fun `consistent declarations have no problem`() {
        assertThat(
            GrantDeclarationsCheck.problemsOf(
                listOf(module("tickets", "ticket.read", roles = mapOf("triage" to setOf("ticket.read")))),
                listOf(TRIAGE),
            ),
        ).isEmpty()
    }

    @Test
    fun `a code, a module or a system role declared twice, an undeclared role and a foreign code are each refused`() {
        val problems =
            GrantDeclarationsCheck.problemsOf(
                listOf(
                    module("tickets", "ticket.read", roles = mapOf("triage" to setOf("invoice.send"), "ghost" to setOf("ticket.read"))),
                    module("tickets", "ticket.close"),
                    module("support", "ticket.read"),
                ),
                listOf(TRIAGE, TRIAGE),
            )

        assertThat(problems.map { it.path to it.code }).contains(
            "access.grants:tickets" to ProblemCode.CONTRADICTS,
            "access.permission:ticket.read" to ProblemCode.CONTRADICTS,
            "access.system-role:triage" to ProblemCode.CONTRADICTS,
            "access.system-role:ghost" to ProblemCode.REQUIRED,
            "access.grants:tickets" to ProblemCode.INVALID,
        )
    }
}

class SubjectRegistryTest {
    private val agents = MemoryDirectory(AGENT)
    private val services = MemoryDirectory(SERVICE)

    private fun registrar(type: SubjectType) =
        object : SubjectRegistrar {
            override val subjectType: SubjectType = type

            override fun register(signUp: SignUp): UUID = UUID.randomUUID()
        }

    @Test
    fun `a type mounted once with one directory is served and resolved from request text`() {
        val registry = SubjectRegistry(listOf(mounted(AGENT)), listOf(agents), listOf(registrar(AGENT)))

        assertThat(registry.problems()).isEmpty()
        assertThat(registry.resolve("agent", "subjectType").registrar).isNotNull()
        listOf("service", "Agent", "").forEach { text ->
            assertThatThrownBy { registry.resolve(text, "subjectType") }
                .matches({ (it as Fault).code == AccessErrorCodes.UNKNOWN_SUBJECT_TYPE }, "unknown subject type")
        }
    }

    @Test
    fun `every mismatch between mounts, directories and registrars is a problem and serves nothing`() {
        val registry =
            SubjectRegistry(
                listOf(mounted(AGENT), mounted(AGENT), mounted(SubjectType("robot"))),
                listOf(services, agents),
                listOf(registrar(SubjectType("guest")), registrar(SubjectType("guest"))),
            )

        assertThat(registry.problems().map { it.path to it.code }).contains(
            "access.subject:agent" to ProblemCode.CONTRADICTS,
            "access.subject:robot" to ProblemCode.REQUIRED,
            "access.subject:service" to ProblemCode.CONTRADICTS,
            "access.subject:guest" to ProblemCode.CONTRADICTS,
        )
        assertThat(registry.types()).isEmpty()
    }

    @Test
    fun `an application that mounts no subject is refused`() {
        assertThat(SubjectRegistry(emptyList(), emptyList(), emptyList()).problems()).singleElement().matches({
            it.code ==
                ProblemCode.REQUIRED
        }, "required")
    }
}
