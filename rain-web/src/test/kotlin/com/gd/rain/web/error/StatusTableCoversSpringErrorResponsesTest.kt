package com.gd.rain.web.error

import com.gd.rain.core.error.FaultKind
import com.gd.rain.web.mockMvcOf
import com.gd.rain.web.problem
import com.gd.rain.web.problem.StatusTable
import com.gd.rain.web.webRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Gap 31: every refusal Spring MVC makes on its own renders with Spring's status through the declarative status table. */
class StatusTableCoversSpringErrorResponsesTest {
    @RestController
    class SpringRefusals {
        @GetMapping("/things")
        fun things(): String = "ok"

        @PostMapping("/json", consumes = [MediaType.APPLICATION_JSON_VALUE])
        fun json(
            @RequestBody body: Map<String, Any>,
        ): String = body.keys.joinToString()

        @GetMapping("/xml", produces = [MediaType.APPLICATION_XML_VALUE])
        fun xml(): String = "<ok/>"

        @GetMapping("/param")
        fun param(
            @RequestParam("q") q: String,
        ): String = q

        @GetMapping("/number")
        fun number(
            @RequestParam("n") n: Int,
        ): String = n.toString()

        @PostMapping("/upload")
        fun upload(): String = throw MaxUploadSizeExceededException(1024)

        @GetMapping("/async-timeout")
        fun asyncTimeout(): String = throw AsyncRequestTimeoutException()

        @GetMapping("/teapot")
        fun teapot(): String = throw ResponseStatusException(HttpStatusCode.valueOf(TEAPOT))

        @GetMapping("/conflict")
        fun conflict(): String = throw ResponseStatusException(HttpStatus.CONFLICT, "a detail that never reaches the client")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `a Spring refusal renders with its own status and the kind's code`(
        exception: Class<out Exception>,
        request: RequestBuilder,
        status: Int,
        code: String,
    ) {
        webRunner().withUserConfiguration(SpringRefusals::class.java).run { context ->
            val result = mockMvcOf(context).perform(request).andReturn()
            val body = result.response.problem()

            assertThat(result.resolvedException).isInstanceOf(exception)
            assertThat(result.response.status).isEqualTo(status)
            assertThat(body["status"].asInt()).isEqualTo(status)
            assertThat(body["code"].asString()).isEqualTo(code)
        }
    }

    @Test
    fun `a method refusal keeps the Allow header Spring computed`() {
        webRunner().withUserConfiguration(SpringRefusals::class.java).run { context ->
            val response = mockMvcOf(context).perform(delete("/things")).andReturn().response

            assertThat(response.status).isEqualTo(405)
            assertThat(response.getHeader("Allow")).isEqualTo("GET")
        }
    }

    @Test
    fun `a status outside the table is 500 unmapped_status and Spring's detail never reaches the client`() {
        webRunner().withUserConfiguration(SpringRefusals::class.java).run { context ->
            val mvc = mockMvcOf(context)
            val teapot = mvc.perform(get("/teapot")).andReturn().response
            val conflict = mvc.perform(get("/conflict")).andReturn().response

            assertThat(teapot.status).isEqualTo(500)
            assertThat(teapot.problem()["code"].asString()).isEqualTo("unmapped_status")
            assertThat(conflict.status).isEqualTo(409)
            assertThat(conflict.problem()["detail"].asString()).isEqualTo("the request conflicts with the current state")
        }
    }

    @Test
    fun `the table maps exactly the declared statuses, each to the kind rendered with that status`() {
        assertThat(StatusTable.V1.statuses).containsExactlyInAnyOrder(400, 401, 403, 404, 405, 406, 409, 413, 415, 422, 429, 500, 503)
        StatusTable.V1.statuses.forEach { status -> assertThat(StatusTable.V1.kindOf(status)?.status).isEqualTo(status) }
        assertThat(StatusTable.V1.faultFor(422, null).violations).hasSize(1)
        assertThat(StatusTable.V1.faultFor(418, null).kind).isEqualTo(FaultKind.INTERNAL)
    }

    companion object {
        /** A status no table row names; its enum constant is deprecated in Spring Framework 7. */
        const val TEAPOT = 418

        @JvmStatic
        fun refusals(): List<Arguments> =
            listOf(
                Arguments.of(AsyncRequestTimeoutException::class.java, get("/async-timeout"), 503, "unavailable"),
                Arguments.of(HttpRequestMethodNotSupportedException::class.java, delete("/things"), 405, "method_not_allowed"),
                Arguments.of(
                    HttpMediaTypeNotAcceptableException::class.java,
                    get("/xml").accept(MediaType.APPLICATION_JSON),
                    406,
                    "not_acceptable",
                ),
                Arguments.of(
                    HttpMediaTypeNotSupportedException::class.java,
                    post("/json").contentType(MediaType.TEXT_PLAIN).content("hello"),
                    415,
                    "unsupported_media_type",
                ),
                Arguments.of(NoResourceFoundException::class.java, get("/nothing-here"), 404, "not_found"),
                Arguments.of(MaxUploadSizeExceededException::class.java, post("/upload"), 413, "too_large"),
                Arguments.of(MissingServletRequestParameterException::class.java, get("/param"), 400, "bad_request"),
                Arguments.of(MethodArgumentTypeMismatchException::class.java, get("/number").param("n", "seven"), 400, "bad_request"),
                Arguments.of(
                    HttpMessageNotReadableException::class.java,
                    post("/json").contentType(MediaType.APPLICATION_JSON).content("{not json"),
                    400,
                    "bad_request",
                ),
            )
    }
}
