package com.gd.rain.access.web

import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.MountedSubject
import com.gd.rain.access.SignUp
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectRegistrar
import com.gd.rain.access.SubjectType
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.web.CredentialCookies
import com.gd.rain.access.internal.web.ProblemAccessDenied
import com.gd.rain.access.internal.web.ProblemEntryPoint
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.MemoryDirectory
import com.gd.rain.access.support.SERVICE
import com.gd.rain.access.support.accessWebRunner
import com.gd.rain.access.support.mockMvcWithFilters
import com.gd.rain.access.support.mounted
import com.gd.rain.access.support.problemWriter
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

private val MAPPER: JsonMapper = JsonMapper.builder().build()

private const val PASSWORD = "correct horse battery"
private const val LOGIN = "/api/auth/agent/login"
private const val REFRESH = "/api/auth/refresh"
private const val DELIVERY = "Rain-Auth-Delivery"

private fun MockHttpServletResponse.json(): JsonNode = MAPPER.readTree(contentAsString)

private fun MockHttpServletResponse.setCookies(): List<String> = getHeaders("Set-Cookie")

private fun MockHttpServletResponse.cookie(name: String): String =
    setCookies().single { it.startsWith("$name=") }.substringAfter('=').substringBefore(';')

private fun MockMvc.send(request: MockHttpServletRequestBuilder): MockHttpServletResponse = perform(request).andReturn().response

private fun json(
    request: MockHttpServletRequestBuilder,
    body: String,
): MockHttpServletRequestBuilder = request.contentType("application/json").content(body)

/** An enrolled agent of the runner's directory, signed in through the route with [delivery] stated when one is given. */
private fun AssertableWebApplicationContext.signIn(
    mvc: MockMvc,
    identifier: String,
    delivery: String? = null,
): MockHttpServletResponse {
    val subject = getBean("agents", MemoryDirectory::class.java).add(identifier)
    getBean(AccessProvisioning::class.java).enrolPassword(subject, identifier, PASSWORD)
    val request = json(post(LOGIN), """{"identifier":"$identifier","password":"$PASSWORD"}""")
    delivery?.let { request.header(DELIVERY, it) }
    return mvc.send(request).also { assertThat(it.status).describedAs(it.contentAsString).isEqualTo(200) }
}

/** Each delivery reads the refresh credential only from the channel it opens, and writes cookies only where it deals in them. */
class RefreshReadsOnlyTheOpenChannelTest {
    @Test
    fun `with cookie delivery a refresh token sent in the body is not read, and the cookie rotates whatever the body says`() {
        accessWebRunner().withPropertyValues("rain.access.web.delivery=cookies").run { context ->
            val mvc = mockMvcWithFilters(context)
            val refresh = context.signIn(mvc, "cookies@example.test").cookie(CredentialCookies.REFRESH)

            val byBody = mvc.send(json(post(REFRESH), """{"refreshToken":"$refresh"}"""))
            val byCookie =
                mvc.send(
                    json(post(REFRESH), """{"refreshToken":"$refresh"}""").cookie(Cookie(CredentialCookies.REFRESH, refresh)),
                )

            assertThat(byBody.status).describedAs(byBody.contentAsString).isEqualTo(401)
            assertThat(byCookie.status).describedAs(byCookie.contentAsString).isEqualTo(200)
            assertThat(byCookie.cookie(CredentialCookies.REFRESH)).isNotEqualTo(refresh)
            assertThat(byCookie.json().has("refreshToken")).isFalse()
        }
    }

    @Test
    fun `with body delivery a refresh cookie is not read, and neither a refresh nor a sign-out writes a cookie`() {
        accessWebRunner().withPropertyValues("rain.access.web.delivery=body").run { context ->
            val mvc = mockMvcWithFilters(context)
            val answer = context.signIn(mvc, "body@example.test").json()
            val refresh = answer["refreshToken"].asString()

            val byCookie = mvc.send(post(REFRESH).cookie(Cookie(CredentialCookies.REFRESH, refresh)))
            val byBody = mvc.send(json(post(REFRESH), """{"refreshToken":"$refresh"}""").cookie(Cookie(CredentialCookies.REFRESH, refresh)))
            val signedOut =
                mvc.send(post("/api/auth/logout").header("Authorization", "Bearer ${byBody.json()["accessToken"].asString()}"))

            assertThat(byCookie.status).describedAs(byCookie.contentAsString).isEqualTo(401)
            assertThat(byBody.status).describedAs(byBody.contentAsString).isEqualTo(200)
            assertThat(byBody.setCookies()).isEmpty()
            assertThat(signedOut.status).isEqualTo(204)
            assertThat(signedOut.setCookies()).isEmpty()
        }
    }

    @Test
    fun `with both deliveries a refresh presenting two cookies, a cookie and a body, a token that is not a string or nothing is 401`() {
        accessWebRunner().run { context ->
            val mvc = mockMvcWithFilters(context)
            val refresh = context.signIn(mvc, "both@example.test", "cookies").cookie(CredentialCookies.REFRESH)

            val refusals =
                mapOf(
                    "more than one refresh cookie" to
                        post(REFRESH).cookie(Cookie(CredentialCookies.REFRESH, refresh), Cookie(CredentialCookies.REFRESH, refresh)),
                    "presents a refresh credential twice" to
                        json(post(REFRESH), """{"refreshToken":"$refresh"}""").cookie(Cookie(CredentialCookies.REFRESH, refresh)),
                    "presents no refresh credential" to json(post(REFRESH), """{"refreshToken":12}"""),
                    "presents no refresh credential " to post(REFRESH),
                ).mapValues { (_, request) -> mvc.send(request) }

            refusals.forEach { (reason, response) ->
                assertThat(response.status).describedAs(reason).isEqualTo(401)
                assertThat(response.json()["code"].asString()).isEqualTo("unauthenticated")
                assertThat(response.json()["detail"].asString()).describedAs(reason).contains(reason.trim())
            }
            val rotated = mvc.send(post(REFRESH).cookie(Cookie(CredentialCookies.REFRESH, refresh)))
            assertThat(rotated.status).describedAs("none of the refusals spent the credential").isEqualTo(200)
        }
    }

    @Test
    fun `signing out everywhere but here leaves this browser's cookies, and everywhere including here clears both`() {
        accessWebRunner().run { context ->
            val mvc = mockMvcWithFilters(context)
            val access = context.signIn(mvc, "everywhere@example.test", "cookies").cookie(CredentialCookies.ACCESS)

            val keeping =
                mvc.send(
                    json(post("/api/auth/logout-all"), """{"includingCurrent":false}""").cookie(Cookie(CredentialCookies.ACCESS, access)),
                )
            val including =
                mvc.send(
                    json(post("/api/auth/logout-all"), """{"includingCurrent":true}""").cookie(Cookie(CredentialCookies.ACCESS, access)),
                )

            assertThat(keeping.status to keeping.setCookies()).isEqualTo(200 to emptyList<String>())
            assertThat(including.status).isEqualTo(200)
            assertThat(including.setCookies()).hasSize(2).allMatch { it.contains("Max-Age=0") }
        }
    }
}

/**
 * A credential that no longer authenticates anybody stands in front of nothing that needs no caller: a browser still
 * holding a stale access cookie signs in again and refreshes, while every route that needs a caller refuses it for the
 * reason it failed.
 */
class StaleCredentialDoesNotBlockPublicRoutesTest {
    @Test
    fun `an expired access cookie neither refuses a sign-in nor a refresh, and a route that needs a caller refuses it`() {
        accessWebRunner().run { context ->
            val mvc = mockMvcWithFilters(context)
            val signedIn = context.signIn(mvc, "stale@example.test", "cookies")
            val refresh = signedIn.cookie(CredentialCookies.REFRESH)
            val now = context.getBean(Clock::class.java).instant()
            val subject = SubjectRef(AGENT, UUID.fromString(signedIn.json()["principal"]["subject"]["id"].asString()))
            val expired =
                context
                    .getBean(AccessTokenIssuer::class.java)
                    .mint(
                        subject,
                        UUID.randomUUID(),
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofHours(1)),
                        now.minus(Duration.ofHours(1)),
                    ).token
            val stale = Cookie(CredentialCookies.ACCESS, expired)

            val again =
                mvc.send(
                    json(
                        post(LOGIN),
                        """{"identifier":"stale@example.test","password":"$PASSWORD"}""",
                    ).header(DELIVERY, "cookies").cookie(stale),
                )
            val rotated = mvc.send(post(REFRESH).cookie(stale, Cookie(CredentialCookies.REFRESH, refresh)))
            val me = mvc.send(get("/api/auth/me").cookie(stale))

            assertThat(again.status).describedAs(again.contentAsString).isEqualTo(200)
            assertThat(rotated.status).describedAs(rotated.contentAsString).isEqualTo(200)
            assertThat(me.status).isEqualTo(401)
            assertThat(me.json()["detail"].asString()).isEqualTo("the access token is not valid")
        }
    }

    @Test
    fun `a request that disagrees with itself about its token is anonymous on a public route and refused where a caller is needed`() {
        accessWebRunner().run { context ->
            val mvc = mockMvcWithFilters(context)
            val token = context.signIn(mvc, "twice@example.test", "body").json()["accessToken"].asString()

            val signIn =
                mvc.send(
                    json(post(LOGIN), """{"identifier":"twice@example.test","password":"$PASSWORD"}""")
                        .header(DELIVERY, "body")
                        .header("Authorization", "Bearer $token", "Bearer $token"),
                )
            val me = mvc.send(get("/api/auth/me").header("Authorization", "Bearer $token", "Bearer $token"))

            assertThat(signIn.status).describedAs(signIn.contentAsString).isEqualTo(200)
            assertThat(me.status).isEqualTo(401)
            assertThat(me.json()["detail"].asString()).contains("more than one Authorization header")
        }
    }
}

/** The sign-up route exists once some type has a registrar, and answers only for a type that has one. */
class SignUpOnlyForTypesWithARegistrarTest {
    private val registrar =
        object : SubjectRegistrar {
            override val subjectType: SubjectType = AGENT

            override fun register(signUp: SignUp): UUID = UUID.randomUUID()
        }

    @Test
    fun `a served type without a registrar is 404 on the sign-up route another type answers`() {
        accessWebRunner()
            .withBean(SubjectRegistrar::class.java, { registrar })
            .withBean("servicesMounted", MountedSubject::class.java, { mounted(SERVICE) })
            .withBean("services", MemoryDirectory::class.java, { MemoryDirectory(SERVICE) })
            .run { context ->
                val mvc = mockMvcWithFilters(context)
                val body = """{"identifier":"new@example.test","password":"a long enough password"}"""

                val service = mvc.send(json(post("/api/auth/service/register"), body).header(DELIVERY, "body"))
                val agent = mvc.send(json(post("/api/auth/agent/register"), body).header(DELIVERY, "body"))
                val unknownField =
                    mvc.send(
                        json(post("/api/auth/agent/register"), """{"identifier":"x@example.test","password":"x","plan":"gold"}""")
                            .header(DELIVERY, "body"),
                    )

                assertThat(service.status).describedAs(service.contentAsString).isEqualTo(404)
                assertThat(agent.status).describedAs(agent.contentAsString).isEqualTo(201)
                assertThat(unknownField.status to unknownField.json()["errors"].single()["pointer"].asString()).isEqualTo(422 to "/plan")
            }
    }
}

/** Spring Security's own refusals, should anything in its chain make one, are problem documents like every other refusal. */
class SecurityChainRefusalsAreProblemsTest {
    @Test
    fun `the chain's own 401 and 403 carry a code and no browser prompt`() {
        val unauthenticated = MockHttpServletResponse()
        val forbidden = MockHttpServletResponse()

        ProblemEntryPoint(problemWriter()).commence(MockHttpServletRequest(), unauthenticated, BadCredentialsException("no"))
        ProblemAccessDenied(problemWriter()).handle(MockHttpServletRequest(), forbidden, AccessDeniedException("no"))

        assertThat(unauthenticated.status to unauthenticated.json()["code"].asString()).isEqualTo(401 to "unauthenticated")
        assertThat(forbidden.status to forbidden.json()["code"].asString()).isEqualTo(403 to "forbidden")
        assertThat(listOf(unauthenticated, forbidden)).allMatch { it.getHeader("WWW-Authenticate") == null }
    }
}
