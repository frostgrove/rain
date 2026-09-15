package com.gd.rain.access.web

import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.GrantsLookup
import com.gd.rain.access.PermissionPage
import com.gd.rain.access.SubjectDirectory
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.SurfaceExemption
import com.gd.rain.access.internal.web.AccessDeclarations
import com.gd.rain.access.internal.web.AccessEnforcementInterceptor
import com.gd.rain.access.internal.web.AccessSurfaceVerifier
import com.gd.rain.access.internal.web.PageRequest
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.START
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.web.route.Access
import com.gd.rain.web.route.DeclaresItsOwnAccess
import com.gd.rain.web.route.EndpointDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.thirdparty.VendorConsoleController
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.StaticWebApplicationContext
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID

private const val THING_READ = "thing.read"
private const val THING_WRITE = "thing.write"

private fun mappingOf(vararg controllers: Class<*>): RequestMappingHandlerMapping {
    val context = StaticWebApplicationContext()
    controllers.forEach { context.registerSingleton(it.name, it) }
    context.refresh()
    return RequestMappingHandlerMapping().apply {
        applicationContext = context
        afterPropertiesSet()
    }
}

private fun verify(
    vararg controllers: Class<*>,
    resources: List<DeclaresItsOwnAccess> = emptyList(),
    exemptions: List<SurfaceExemption> = emptyList(),
    codes: Set<String> = setOf(THING_READ, THING_WRITE),
): List<ConfigurationProblem> {
    val mapping = mappingOf(*controllers)
    return AccessSurfaceVerifier({ mapping }, AccessDeclarations(exemptions), resources, emptyList(), { codes }).problems()
}

private class HeldGrants(
    private val principal: AccessPrincipal?,
    private val held: Set<String>,
) : GrantsLookup {
    override fun principalOf(): AccessPrincipal? = principal

    override fun heldBy(
        subject: SubjectRef,
        codes: Set<String>,
    ): Set<String> = codes intersect held

    override fun directPermissionsOf(
        subject: SubjectRef,
        after: UUID?,
        limit: Int,
    ): PermissionPage = error("not read")

    override fun directoryOf(type: SubjectType): SubjectDirectory? = null
}

private val PRINCIPAL = AccessPrincipal(SubjectRef(AGENT, UUID.randomUUID()), UUID.randomUUID(), START, START.plusSeconds(300))

@RestController
@RequestMapping("/api/verify")
class DeclaredController {
    @Access(permissions = [THING_READ])
    @GetMapping("/mine")
    fun mine(): String = "ok"

    @Access(public = true, why = "a probe has no account")
    @GetMapping("/open")
    fun open(): String = "ok"
}

@RestController
@RequestMapping("/api/verify")
class UndeclaredController {
    @GetMapping("/silent")
    fun silent(): String = "ok"
}

@RestController
@RequestMapping("/api/verify")
class UnreasonedController {
    @Access(public = true)
    @GetMapping("/why")
    fun why(): String = "ok"
}

@RestController
@RequestMapping("/api/verify")
class ContradictoryController {
    @Access(public = true, why = "it says both", permissions = [THING_READ])
    @GetMapping("/both")
    fun both(): String = "ok"
}

@RestController
@RequestMapping("/api/verify")
class TwiceController {
    @Access(permissions = [THING_READ])
    @PostMapping("/twice", params = ["a"])
    fun first(): String = "ok"

    @Access(permissions = [THING_WRITE])
    @PostMapping("/twice", params = ["b"])
    fun second(): String = "ok"
}

@RestController
@RequestMapping("/api/verify")
class UndeclaredPermissionController {
    @Access(permissions = ["nothing.declares-this"])
    @GetMapping("/phantom-code")
    fun phantom(): String = "ok"
}

/** A table-derived controller: no annotation, its declarations answered by itself. */
@RestController
@RequestMapping("/api/jobs")
class TableController : DeclaresItsOwnAccess {
    @GetMapping("/dead")
    fun dead(): String = "ok"

    override fun accessDeclarations(): List<EndpointDeclaration> =
        listOf(
            EndpointDeclaration("GET", "/api/jobs/dead", permissions = listOf(THING_READ)),
            EndpointDeclaration("GET", "/api/jobs/gone", permissions = listOf(THING_READ)),
        )
}

/** A mapping without a method, beside one with a method on the same pattern. */
@RestController
class MethodlessController {
    @Access(permissions = [THING_WRITE])
    @RequestMapping("/api/things")
    fun any(): String = "any"

    @Access(permissions = [THING_READ])
    @GetMapping("/api/things")
    fun read(): String = "read"
}

/** A table-derived controller whose method-less mapping declares GET only. */
@RestController
class MethodlessTableController : DeclaresItsOwnAccess {
    @RequestMapping("/api/rows")
    fun any(): String = "any"

    override fun accessDeclarations(): List<EndpointDeclaration> =
        listOf(EndpointDeclaration("GET", "/api/rows", permissions = listOf(THING_READ)))
}

/** Ported surface verification: every mounted route declares its access, and declarations are well formed and known. */
class AccessSurfaceVerifierTest {
    @Test
    fun `a declared and mounted surface verifies`() {
        assertThat(verify(DeclaredController::class.java)).isEmpty()
    }

    @Test
    fun `a mapped handler that declares nothing is refused`() {
        assertThat(verify(UndeclaredController::class.java)).singleElement().matches({
            it.path == "access.surface:GET /api/verify/silent" &&
                it.code == ProblemCode.REQUIRED
        }, "undeclared")
    }

    @Test
    fun `a public declaration with no reason is refused`() {
        assertThat(verify(UnreasonedController::class.java)).anyMatch { it.message.contains("says nothing about why") }
    }

    @Test
    fun `a declaration that is public and permissioned is refused`() {
        assertThat(verify(ContradictoryController::class.java)).anyMatch { it.message.contains("public and also requires") }
    }

    @Test
    fun `one route declared twice is refused`() {
        assertThat(verify(TwiceController::class.java)).anyMatch { it.message == "is declared twice" }
    }

    @Test
    fun `a permission no module declares is refused`() {
        assertThat(verify(UndeclaredPermissionController::class.java)).anyMatch { it.message.contains("nothing.declares-this") }
    }

    @Test
    fun `a table-derived controller answers its declarations, and one that mounts nothing is refused`() {
        val table = TableController()

        val problems = verify(TableController::class.java, resources = listOf(table))

        assertThat(problems).singleElement().matches({
            it.path == "access.surface:GET /api/jobs/gone" &&
                it.message.contains("mounts nothing")
        }, "gone")
    }
}

/** Gap 21: a handler is exempt only by a SurfaceExemption bean, never by the package or library it comes from. */
class ThirdPartyControllerNotExemptTest {
    @Test
    fun `a controller from an org-springframework package that declares nothing is refused`() {
        assertThat(VendorConsoleController::class.java.name).startsWith("org.springframework.")

        assertThat(
            verify(VendorConsoleController::class.java),
        ).singleElement().matches({ it.message.contains("declares no access") }, "undeclared")
    }

    @Test
    fun `the same controller with a stated exemption passes verification and enforcement`() {
        val exemption = SurfaceExemption(VendorConsoleController::class.java, "the vendor console authenticates on its own")

        assertThat(verify(VendorConsoleController::class.java, exemptions = listOf(exemption))).isEmpty()

        val handler = HandlerMethod(VendorConsoleController(), VendorConsoleController::class.java.getMethod("console"))
        val request =
            MockHttpServletRequest("GET", "/vendor/console").apply {
                setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/vendor/console")
            }
        assertThat(
            AccessEnforcementInterceptor(
                AccessDeclarations(listOf(exemption)),
                HeldGrants(null, emptySet()),
            ).preHandle(request, MockHttpServletResponse(), handler),
        ).isTrue()
    }
}

/** Gap 21: a mapping without a method answers every method, and so its declaration covers every method. */
class MethodlessMappingCoversAllMethodsTest {
    @Test
    fun `a method-less mapping beside a GET mapping is neither undeclared nor declared twice`() {
        assertThat(verify(MethodlessController::class.java)).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["POST", "PUT", "PATCH", "DELETE", "OPTIONS"])
    fun `every method a method-less mapping answers is enforced against its declaration`(method: String) {
        val handler = HandlerMethod(MethodlessController(), MethodlessController::class.java.getMethod("any"))
        val request =
            MockHttpServletRequest(method, "/api/things").apply {
                setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/things")
            }

        assertThatThrownBy {
            AccessEnforcementInterceptor(
                AccessDeclarations(emptyList()),
                HeldGrants(PRINCIPAL, setOf(THING_READ)),
            ).preHandle(request, MockHttpServletResponse(), handler)
        }.matches({ (it as Fault).kind == FaultKind.FORBIDDEN }, "403")
        assertThat(
            AccessEnforcementInterceptor(
                AccessDeclarations(emptyList()),
                HeldGrants(PRINCIPAL, setOf(THING_WRITE)),
            ).preHandle(request, MockHttpServletResponse(), handler),
        ).isTrue()
    }

    @Test
    fun `a table-derived method-less mapping without a declaration for every method is refused for each missing one`() {
        val problems = verify(MethodlessTableController::class.java, resources = listOf(MethodlessTableController()))

        assertThat(problems.map { it.path })
            .contains("access.surface:POST /api/rows", "access.surface:DELETE /api/rows", "access.surface:TRACE /api/rows")
            .doesNotContain("access.surface:GET /api/rows")
    }

    @Test
    fun `an authenticated declaration refuses an anonymous caller with 401`() {
        val handler = HandlerMethod(DeclaredController(), DeclaredController::class.java.getMethod("mine"))
        val request =
            MockHttpServletRequest("GET", "/api/verify/mine").apply {
                setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/verify/mine")
            }

        assertThatThrownBy {
            AccessEnforcementInterceptor(
                AccessDeclarations(emptyList()),
                HeldGrants(null, emptySet()),
            ).preHandle(request, MockHttpServletResponse(), handler)
        }.matches({ (it as Fault).code == RainErrorCodes.UNAUTHENTICATED }, "401")
    }
}

/** Gap 21: a list route reads the parameters it names and nothing else; there is no preload to guess at. */
class RoleListRefusesPreloadParameterTest {
    private val pages =
        PageRequest(
            com.gd.rain.access.AccessProperties
                .Page(),
        )

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = ["include=permissions", "preload=permissions", "with=permission", "preloads=Permissions", "limit=5&permissionsToo=1"],
    )
    fun `a parameter the route does not name is 400 unknown_parameter`(query: String) {
        val request = MockHttpServletRequest("GET", "/api/roles")
        query.split('&').forEach { pair -> pair.split('=').let { (name, value) -> request.addParameter(name, value) } }

        assertThatThrownBy { pages.only(request, PageRequest.AFTER, PageRequest.LIMIT) }
            .matches({ (it as Fault).code == RainErrorCodes.UNKNOWN_PARAMETER }, "unknown parameter")
    }

    @Test
    fun `the named parameters pass, and a limit outside the declared page is refused`() {
        val request =
            MockHttpServletRequest("GET", "/api/roles").apply {
                addParameter("after", "triage")
                addParameter("limit", "25")
            }
        pages.only(request, PageRequest.AFTER, PageRequest.LIMIT)

        assertThat(pages.limit(request)).isEqualTo(25)
        assertThat(pages.after(request, PageRequest::slug)).isEqualTo("triage")
        listOf("0", "201", "ten", "-1").forEach { written ->
            assertThatThrownBy { pages.limit(MockHttpServletRequest().apply { addParameter("limit", written) }) }
                .matches({ (it as Fault).code == RainErrorCodes.BAD_QUERY }, "bad query")
        }
    }
}
