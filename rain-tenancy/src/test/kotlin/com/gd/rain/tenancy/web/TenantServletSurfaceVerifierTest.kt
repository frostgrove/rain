package com.gd.rain.tenancy.web

import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

class TenantServletSurfaceVerifierTest {
    @Test
    fun `mounted controller routes need exactly one tenancy declaration`() {
        applicationContext(DeclaredController::class.java, UndeclaredController::class.java).use { context ->
            val mapping = mapping(context)
            val problems = TenantServletSurfaceVerifier({ mapping }, TenantRouteDeclarations(emptyList()), emptyList()).problems()

            assertThat(problems.map { it.path to it.message })
                .containsExactly(
                    "tenancy.surface:GET /undeclared" to
                        "is mounted by ${UndeclaredController::class.java.name}#missing() and declares no tenancy",
                )
        }
    }

    @Test
    fun `class default and method override give every mounted route an intentional surface`() {
        applicationContext(DefaultController::class.java).use { context ->
            val problems = TenantServletSurfaceVerifier({ mapping(context) }, TenantRouteDeclarations(emptyList()), emptyList()).problems()

            assertThat(problems).isEmpty()
        }
    }

    @Test
    fun `functional routes need a generated tenancy declaration and unreadable predicates are refused`() {
        val declared = TenantEndpointDeclaration("GET", "/functional", TenantRouteSurface.Tenant(TenantRouteOperation.READ))

        assertThat(functionalProblems(emptyList(), RouterFunctions.route().GET("/functional", ok).build()).map { it.code })
            .containsExactly(ProblemCode.REQUIRED)
        assertThat(functionalProblems(listOf(declared), RouterFunctions.route().GET("/functional", ok).build())).isEmpty()
        assertThat(
            functionalProblems(
                emptyList(),
                RouterFunctions.route(RequestPredicates.GET("/functional").or(RequestPredicates.GET("/other")), ok),
            ).map { it.code },
        ).containsExactly(ProblemCode.INVALID)
    }

    private fun functionalProblems(
        declared: List<TenantEndpointDeclaration>,
        routes: RouterFunction<*>,
    ): List<com.gd.rain.core.config.ConfigurationProblem> {
        val resource =
            object : DeclaresItsOwnTenancy {
                override fun tenantDeclarations(): List<TenantEndpointDeclaration> = declared
            }
        return TenantServletSurfaceVerifier(
            mapping = { null },
            declarations = TenantRouteDeclarations(emptyList()),
            resources = listOf(resource),
            routes = { routes },
        ).problems()
    }

    private fun applicationContext(vararg types: Class<*>): GenericApplicationContext =
        GenericApplicationContext().also { context ->
            types.forEach { type ->
                context.registerBeanDefinition(
                    type.simpleName.replaceFirstChar(Char::lowercase),
                    org.springframework.beans.factory.support
                        .RootBeanDefinition(type),
                )
            }
            context.refresh()
        }

    private fun mapping(context: GenericApplicationContext): RequestMappingHandlerMapping =
        RequestMappingHandlerMapping().also { mapping ->
            mapping.applicationContext = context
            mapping.afterPropertiesSet()
        }

    private companion object {
        val ok: HandlerFunction<ServerResponse> = HandlerFunction { ServerResponse.ok().build() }
    }

    @Controller
    private open class DeclaredController {
        @GetMapping("/declared")
        @TenantRoute(TenantRouteOperation.READ)
        fun declared() = "ok"
    }

    @Controller
    private open class UndeclaredController {
        @GetMapping("/undeclared")
        fun missing() = "no"
    }

    @Controller
    @RequestMapping("/default")
    @TenantRoute(TenantRouteOperation.READ)
    private open class DefaultController {
        @GetMapping("/read")
        fun read() = "read"

        @GetMapping("/write")
        @TenantRoute(TenantRouteOperation.WRITE)
        fun write() = "write"
    }
}
