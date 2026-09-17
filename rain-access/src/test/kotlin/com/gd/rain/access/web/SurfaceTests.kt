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
import com.gd.rain.access.internal.web.DeclarationLookup
import com.gd.rain.access.internal.web.PageRequest
import com.gd.rain.access.internal.web.declarationFor
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
import com.gd.rain.web.route.MountsItsOwnSurface
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
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.support.RouterFunctionMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.ServletRequestPathUtils
import org.springframework.web.util.pattern.PathPatternParser
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
    return AccessSurfaceVerifier({ mapping }, { null }, AccessDeclarations(exemptions), resources, emptyList(), { codes }).problems()
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
                { emptyMap() },
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
                { emptyMap() },
            ).preHandle(request, MockHttpServletResponse(), handler)
        }.matches({ (it as Fault).kind == FaultKind.FORBIDDEN }, "403")
        assertThat(
            AccessEnforcementInterceptor(
                AccessDeclarations(emptyList()),
                HeldGrants(PRINCIPAL, setOf(THING_WRITE)),
                { emptyMap() },
            ).preHandle(request, MockHttpServletResponse(), handler),
        ).isTrue()
    }

    @Test
    fun `a table-derived method-less mapping without a declaration for every method is refused for each missing one`() {
        val problems = verify(MethodlessTableController::class.java, resources = listOf(MethodlessTableController()))

        assertThat(problems.map { it.path })
            .contains("access.surface:POST /api/rows", "access.surface:DELETE /api/rows", "access.surface:TRACE /api/rows")
            .doesNotContain("access.surface:GET /api/rows", "access.surface:HEAD /api/rows")
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
                { emptyMap() },
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
        mapOf(
            "0" to RainErrorCodes.OUT_OF_RANGE,
            "201" to RainErrorCodes.OUT_OF_RANGE,
            "ten" to RainErrorCodes.INVALID_FORMAT,
            "-1" to RainErrorCodes.INVALID_FORMAT,
        ).forEach { (written, code) ->
            assertThatThrownBy { pages.limit(MockHttpServletRequest().apply { addParameter("limit", written) }) }
                .matches(
                    {
                        (it as Fault).code == RainErrorCodes.BAD_QUERY &&
                            it.violations.map { v -> v.pointer to v.code } == listOf("/limit" to code)
                    },
                    "400 bad_query with $code at /limit",
                )
        }
    }
}

private val OK: HandlerFunction<ServerResponse> = HandlerFunction { ServerResponse.ok().build() }

/**
 * A table-derived `GET` route answers `HEAD` too. Its declaration used to be looked up as `HEAD <pattern>`, which a
 * `DeclaresItsOwnAccess` controller never declares, so every `HEAD` request was a 500; it is held to the `GET`
 * declaration, as a functional route's already was.
 */
class HeadHeldToGetDeclarationTest {
    private val table = TableController()
    private val handler = HandlerMethod(table, TableController::class.java.getMethod("dead"))

    private fun head(): MockHttpServletRequest =
        MockHttpServletRequest(
            "HEAD",
            "/api/jobs/dead",
        ).apply { setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/jobs/dead") }

    private fun interceptor(grants: GrantsLookup): AccessEnforcementInterceptor =
        AccessEnforcementInterceptor(AccessDeclarations(emptyList()), grants) { emptyMap() }

    @Test
    fun `a HEAD request to a table-derived GET route is enforced from the GET declaration`() {
        assertThat(interceptor(HeldGrants(PRINCIPAL, setOf(THING_READ))).preHandle(head(), MockHttpServletResponse(), handler)).isTrue()
        assertThatThrownBy { interceptor(HeldGrants(null, emptySet())).preHandle(head(), MockHttpServletResponse(), handler) }
            .matches({ (it as Fault).kind == FaultKind.UNAUTHORIZED }, "401")
        assertThatThrownBy { interceptor(HeldGrants(PRINCIPAL, setOf(THING_WRITE))).preHandle(head(), MockHttpServletResponse(), handler) }
            .matches({ (it as Fault).kind == FaultKind.FORBIDDEN }, "403")
    }

    @Test
    fun `only HEAD is held to GET, and only where the pattern declares GET`() {
        val declarations = AccessDeclarations(emptyList())

        val head = declarations.of(TableController::class.java, handler, table, "HEAD", "/api/jobs/dead")
        val post = declarations.of(TableController::class.java, handler, table, "POST", "/api/jobs/dead")

        assertThat((head as DeclarationLookup.Declared).declaration.key).isEqualTo("GET /api/jobs/dead")
        assertThat(post).isEqualTo(DeclarationLookup.Undeclared)
        assertThat(
            declarationFor(mapOf("PUT /x" to EndpointDeclaration("PUT", "/x", authenticated = true, why = "w")), "HEAD", "/x"),
        ).isNull()
    }
}

/** A functional route declares its access through a MountsItsOwnSurface and is enforced from that declaration. */
class FunctionalRoutesAreVerifiedTest {
    private val extra = EndpointDeclaration("GET", "/api/extra", permissions = listOf(THING_READ))
    private val routes = RouterFunctions.route().GET("/api/extra", OK).build()

    private fun problems(
        declared: List<EndpointDeclaration>,
        function: RouterFunction<*>,
    ): List<ConfigurationProblem> {
        val surface =
            object : MountsItsOwnSurface {
                override fun mountedDeclarations(): List<EndpointDeclaration> = declared
            }
        return AccessSurfaceVerifier({
            null
        }, { function }, AccessDeclarations(emptyList()), emptyList(), listOf(surface), { setOf(THING_READ, THING_WRITE) })
            .problems()
    }

    @Test
    fun `an undeclared functional route is refused, a declared one verifies, and an unreadable one is refused`() {
        assertThat(
            problems(emptyList(), routes).map {
                it.path to it.code
            },
        ).containsExactly("access.surface:GET /api/extra" to ProblemCode.REQUIRED)
        assertThat(problems(listOf(extra), routes)).isEmpty()
        assertThat(
            problems(emptyList(), RouterFunctions.route(RequestPredicates.GET("/a").or(RequestPredicates.GET("/b")), OK)).map { it.code },
        ).containsExactly(ProblemCode.INVALID)
    }

    /** A request carrying the pattern the way `RouterFunctionMapping` records it once it has routed the request. */
    private fun request(method: String): MockHttpServletRequest =
        MockHttpServletRequest(method, "/api/extra").apply {
            setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/extra")
        }

    @Test
    fun `a request Spring's own router mapping routed is enforced from the pattern that mapping recorded`() {
        val request = MockHttpServletRequest("GET", "/api/extra")
        ServletRequestPathUtils.parseAndCache(request)
        val routed = checkNotNull(RouterFunctionMapping(routes).getHandler(request)) { "the router mapped nothing" }

        assertThat(
            interceptor(HeldGrants(PRINCIPAL, setOf(THING_READ))).preHandle(request, MockHttpServletResponse(), routed.handler),
        ).isTrue()
        assertThatThrownBy { interceptor(HeldGrants(null, emptySet())).preHandle(request, MockHttpServletResponse(), routed.handler) }
            .matches({ (it as Fault).kind == FaultKind.UNAUTHORIZED }, "unauthorized")
    }

    private fun interceptor(grants: GrantsLookup): AccessEnforcementInterceptor =
        AccessEnforcementInterceptor(AccessDeclarations(emptyList()), grants) { mapOf(extra.key to extra) }

    @Test
    fun `a functional route is enforced from its declaration, and HEAD is held to the GET declaration`() {
        assertThat(interceptor(HeldGrants(PRINCIPAL, setOf(THING_READ))).preHandle(request("GET"), MockHttpServletResponse(), OK)).isTrue()
        assertThat(interceptor(HeldGrants(PRINCIPAL, setOf(THING_READ))).preHandle(request("HEAD"), MockHttpServletResponse(), OK)).isTrue()
        assertThatThrownBy { interceptor(HeldGrants(null, emptySet())).preHandle(request("GET"), MockHttpServletResponse(), OK) }
            .isInstanceOf(Fault::class.java)
            .matches({ (it as Fault).kind == FaultKind.UNAUTHORIZED }, "unauthorized")
        assertThatThrownBy {
            interceptor(
                HeldGrants(PRINCIPAL, setOf(THING_WRITE)),
            ).preHandle(request("GET"), MockHttpServletResponse(), OK)
        }.isInstanceOf(Fault::class.java)
            .matches({ (it as Fault).kind == FaultKind.FORBIDDEN }, "forbidden")
        assertThatThrownBy {
            interceptor(
                HeldGrants(PRINCIPAL, setOf(THING_READ)),
            ).preHandle(request("POST"), MockHttpServletResponse(), OK)
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
