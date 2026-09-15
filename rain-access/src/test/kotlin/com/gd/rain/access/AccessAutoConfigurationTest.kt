package com.gd.rain.access

import com.gd.rain.access.internal.attempt.AttemptLimiter
import com.gd.rain.access.internal.attempt.MemoryAttemptLimiter
import com.gd.rain.access.internal.revocation.NoRevocationList
import com.gd.rain.access.internal.revocation.RevocationList
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.usecase.CatalogueSynchronizer
import com.gd.rain.access.internal.usecase.SessionRetentionTask
import com.gd.rain.access.internal.web.AccessEnforcementInterceptor
import com.gd.rain.access.internal.web.AuthController
import com.gd.rain.access.internal.web.RoleController
import com.gd.rain.access.internal.web.SignUpController
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.MemoryDirectory
import com.gd.rain.access.support.MemoryGrants
import com.gd.rain.access.support.START
import com.gd.rain.access.support.TICKET_READ
import com.gd.rain.access.support.TicketController
import com.gd.rain.access.support.accessWebRunner
import com.gd.rain.access.support.mockMvcWithFilters
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.error.ErrorCodeRegistry
import com.gd.rain.jobs.RecurringWork
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@RestController
class UndeclaredApplicationController {
    @GetMapping("/undeclared")
    fun undeclared(): String = "ok"
}

class AccessAutoConfigurationTest {
    private val json = JsonMapper.builder().build()

    private val registrar =
        object : SubjectRegistrar {
            override val subjectType: SubjectType = AGENT

            override fun register(signUp: SignUp): UUID = UUID.randomUUID()
        }

    @Test
    fun `an api process gets the routes, one security chain, enforcement, and no sign-up without a registrar`() {
        accessWebRunner().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context)
                .hasSingleBean(AuthController::class.java)
                .hasSingleBean(RoleController::class.java)
                .hasSingleBean(AccessEnforcementInterceptor::class.java)
                .hasSingleBean(SecurityFilterChain::class.java)
                .doesNotHaveBean(SignUpController::class.java)
                .doesNotHaveBean(UserDetailsService::class.java)
            assertThat(context.getBean(RevocationList::class.java)).isSameAs(NoRevocationList)
            assertThat(context.getBean(AttemptLimiter::class.java)).isInstanceOf(MemoryAttemptLimiter::class.java)
            assertThat(context.getBean(ErrorCodeRegistry::class.java).contains(AccessErrorCodes.BAD_CREDENTIALS)).isTrue()
            assertThat(context.getBean(CatalogueSynchronizer::class.java).isRunning).isTrue()
            assertThat(context.getBean(MemoryGrants::class.java).permissionByCode("access.role.read")).isNotNull()

            val response =
                mockMvcWithFilters(
                    context,
                ).perform(post("/api/auth/agent/register").contentType("application/json").content("{}")).andReturn().response
            assertThat(response.status).describedAs("no registrar, no sign-up route").isEqualTo(404)
        }
    }

    @Test
    fun `a registrar mounts the sign-up route`() {
        accessWebRunner().withBean(SubjectRegistrar::class.java, { registrar }).run { context ->
            assertThat(context).hasSingleBean(SignUpController::class.java)

            val response =
                mockMvcWithFilters(context)
                    .perform(
                        post("/api/auth/agent/register")
                            .header("Rain-Auth-Delivery", "body")
                            .contentType("application/json")
                            .content("""{"identifier":"new@example.test","password":"a long enough password"}"""),
                    ).andReturn()
                    .response
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(201)
            assertThat(json.readTree(response.contentAsString)["refreshToken"].asString()).isNotEmpty()
        }
    }

    @Test
    fun `a worker process runs retention and mounts no route, and still has the one chain`() {
        accessWebRunner().withPropertyValues("rain.runtime.roles=worker").run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(AuthController::class.java).hasSingleBean(SecurityFilterChain::class.java)
            assertThat(context.getBeansOfType(RecurringWork::class.java).values).anyMatch { it is SessionRetentionTask }
        }
    }

    @Test
    fun `an application route that declares no access refuses the start`() {
        accessWebRunner().withBean(UndeclaredApplicationController::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(generateSequence(context.startupFailure, Throwable::cause).last())
                .isInstanceOf(ConfigurationProblemsException::class.java)
                .hasMessageContaining("access.surface:GET /undeclared")
        }
    }

    @Test
    fun `a declared route answers 401 anonymous, 403 without the permission and 200 through a role granting every permission`() {
        accessWebRunner()
            .withBean(TicketController::class.java)
            .withBean(
                "helpdeskGrants",
                ModuleGrants::class.java,
                { ModuleGrants("helpdesk", listOf(PermissionDef(TICKET_READ, "Read tickets"))) },
            ).withBean("administrator", SystemRoleDeclaration::class.java, {
                SystemRoleDeclaration("administrator", "Administrator", true)
            })
            .run { context ->
                val mvc = mockMvcWithFilters(context)
                val directory = context.getBean(MemoryDirectory::class.java)
                val grants = context.getBean(MemoryGrants::class.java)
                val tokens = context.getBean(AccessTokenIssuer::class.java)
                val clock = context.getBean(java.time.Clock::class.java)
                val holder = directory.add("holder@example.test")
                val stranger = directory.add("stranger@example.test")
                grants.grantRole(holder, requireNotNull(grants.roleBySlug("administrator")).id, START)

                fun bearer(subject: SubjectRef) =
                    "Bearer " +
                        tokens.mint(subject, UUID.randomUUID(), clock.instant(), clock.instant().plusSeconds(3600), clock.instant()).token

                assertThat(
                    mvc
                        .perform(get("/tickets"))
                        .andReturn()
                        .response.status,
                ).isEqualTo(401)
                assertThat(
                    mvc
                        .perform(get("/tickets").header("Authorization", bearer(stranger)))
                        .andReturn()
                        .response.status,
                ).isEqualTo(403)
                assertThat(
                    mvc
                        .perform(get("/tickets").header("Authorization", bearer(holder)))
                        .andReturn()
                        .response.status,
                ).isEqualTo(200)
                directory.deactivate(holder.id)
                assertThat(
                    mvc
                        .perform(get("/tickets").header("Authorization", bearer(holder)))
                        .andReturn()
                        .response.status,
                ).isEqualTo(401)
            }
    }
}
