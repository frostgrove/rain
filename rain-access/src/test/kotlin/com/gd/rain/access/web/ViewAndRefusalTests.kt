package com.gd.rain.access.web

import com.gd.rain.access.CredentialDelivery
import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.PermissionDef
import com.gd.rain.access.Profile
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.store.StoredSession
import com.gd.rain.access.internal.token.AccessTokenIssuer
import com.gd.rain.access.internal.usecase.IssuedCredentials
import com.gd.rain.access.internal.web.AuthAnswer
import com.gd.rain.access.internal.web.CredentialSurface
import com.gd.rain.access.internal.web.PageView
import com.gd.rain.access.internal.web.SessionView
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.MemoryDirectory
import com.gd.rain.access.support.START
import com.gd.rain.access.support.TICKET_READ
import com.gd.rain.access.support.TicketController
import com.gd.rain.access.support.accessWebRunner
import com.gd.rain.access.support.mockMvcWithFilters
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

private val MAPPER: JsonMapper = JsonMapper.builder().build()

private fun written(value: Any): JsonNode = MAPPER.readTree(MAPPER.writeValueAsString(value))

private fun JsonNode.keys(): List<String> = properties().map { it.key }

/** What the auth routes put on the wire, key by key. */
class AuthViewsSerializationTest {
    private val subject = SubjectRef(AGENT, UUID.fromString("018f5b3a-1c2d-7e4f-8a9b-0c1d2e3f4a5b"))
    private val credentials =
        IssuedCredentials(
            subject,
            UUID.fromString("018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c"),
            START,
            "the-access-token",
            START.plusSeconds(300),
            "1.018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c.random",
            START.plus(Duration.ofDays(30)),
        )

    @Test
    fun `an answer delivered in cookies carries the principal and nothing else`() {
        val answer = written(AuthAnswer.of(credentials, Profile("Ada", "ada@example.test"), CredentialDelivery.COOKIES))

        assertThat(answer.keys()).containsExactly("principal")
        assertThat(answer["principal"]["subject"]["type"].asString()).isEqualTo("agent")
        assertThat(answer["principal"]["profile"]["identifier"].asString()).isEqualTo("ada@example.test")
    }

    @Test
    fun `an answer delivered in the body carries both credentials and their expiries, and no profile it does not have`() {
        val answer = written(AuthAnswer.of(credentials, null, CredentialDelivery.BODY))

        assertThat(
            answer.keys(),
        ).containsExactlyInAnyOrder("principal", "accessToken", "accessExpiresAt", "refreshToken", "refreshExpiresAt")
        assertThat(answer["principal"].has("profile")).isFalse()
        assertThat(answer["refreshToken"].asString()).isEqualTo(credentials.refresh)
    }

    @Test
    fun `a session that recorded no agent or address omits both, and a page states its next cursor even when there is none`() {
        val session =
            StoredSession(credentials.session, subject, "hash", null, 1, null, null, START, START, null, START.plusSeconds(60), null, null)

        val page = written(PageView(listOf(SessionView.of(session, session.id)), null))

        assertThat(page.has("next")).isTrue()
        assertThat(page["next"].isNull).isTrue()
        val item = page["items"].single()
        assertThat(item.has("userAgent") || item.has("address")).isFalse()
        assertThat(item["current"].asBoolean()).isTrue()
    }
}

/** The credential gate's scope: the auth routes under the base path, and nothing else. */
class CredentialSurfaceTest {
    @Test
    fun `the gate stands on the auth routes under the base path and on nothing else`() {
        val surface = CredentialSurface("/api/auth")

        assertThat(listOf("/api/auth", "/api/auth/agent/login", "/api/auth/refresh")).allMatch { surface.covers(it) }
        assertThat(
            listOf("/api/authority", "/api", "/api/roles", "/auth/agent/login", "/api/auth-x/login"),
        ).noneMatch { surface.covers(it) }
    }
}

/** A refusal from the chain or the enforcement is a problem document with a code, and never a password prompt. */
class SecurityRefusalTest {
    @Test
    fun `401 and 403 are problem documents a client reads a code from, and neither invites a browser to prompt`() {
        accessWebRunner()
            .withBean(TicketController::class.java)
            .withBean(
                "helpdeskGrants",
                ModuleGrants::class.java,
                { ModuleGrants("helpdesk", listOf(PermissionDef(TICKET_READ, "Read tickets"))) },
            ).run { context ->
                val mvc = mockMvcWithFilters(context)
                val now = context.getBean(Clock::class.java).instant()
                val stranger = context.getBean(MemoryDirectory::class.java).add("stranger@example.test")
                val token =
                    context
                        .getBean(
                            AccessTokenIssuer::class.java,
                        ).mint(stranger, UUID.randomUUID(), now, now.plusSeconds(3600), now)
                        .token

                val anonymous = mvc.perform(get("/tickets")).andReturn().response
                val forbidden = mvc.perform(get("/tickets").header("Authorization", "Bearer $token")).andReturn().response

                assertThat(anonymous.status to forbidden.status).isEqualTo(401 to 403)
                listOf(anonymous to "unauthenticated", forbidden to "forbidden").forEach { (response, code) ->
                    assertThat(response.contentType).startsWith("application/problem+json")
                    assertThat(MAPPER.readTree(response.contentAsString)["code"].asString()).isEqualTo(code)
                    assertThat(response.getHeader("WWW-Authenticate")).isNull()
                }
            }
    }
}
