package com.gd.rain.access.web

import com.gd.rain.access.AccessErrorCodes
import com.gd.rain.access.AccessProperties
import com.gd.rain.access.internal.store.SessionCursor
import com.gd.rain.access.internal.web.JsonBody
import com.gd.rain.access.internal.web.PageRequest
import com.gd.rain.access.support.START
import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.web.problem.ProblemFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.temporal.ChronoUnit
import java.util.UUID

private val JSON: JsonMapper = JsonMapper.builder().build()

private fun body(text: String): JsonNode = JSON.readTree(text)

private fun refusalOf(block: () -> Any?): Fault =
    requireNotNull(runCatching(block).exceptionOrNull() as? Fault) { "the call raised no fault" }

private fun Fault.pointed(): List<Pair<String, ErrorCode>> = violations.map { it.pointer to it.code }

private const val ID = "018f5b3a-2c2d-7e4f-8a9b-0c1d2e3f4a5c"

/** A body is an object naming exactly the fields its route reads, each of the type the route reads it as. */
class JsonBodyIsStrictTest {
    @Test
    fun `a field the route does not read is 422 unknown_field at its pointer`() {
        val refusal = refusalOf { JsonBody.of(body("""{"slug":"lead","name":"Lead","system":true}"""), "slug", "name") }

        assertThat(refusal.kind).isEqualTo(FaultKind.VALIDATION)
        assertThat(refusal.pointed()).containsExactly("/system" to RainErrorCodes.UNKNOWN_FIELD)
        assertThat(refusal.partial).isFalse()
    }

    @Test
    fun `a body of more unknown fields than a problem names is refused naming as many as a problem holds, marked partial`() {
        val many = (0..ProblemFormat.MAX_ERRORS + 50).joinToString(",", "{", "}") { "\"field$it\":1" }

        val refusal = refusalOf { JsonBody.of(body(many), "slug") }

        assertThat(refusal.violations).hasSize(ProblemFormat.MAX_ERRORS).allMatch { it.code == RainErrorCodes.UNKNOWN_FIELD }
        assertThat(refusal.partial).isTrue()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["[]", "[{\"name\":\"Lead\"}]", "\"Lead\"", "42", "true", "null"])
    fun `a body that is not an object is 400 malformed_body`(text: String) {
        val refusal = refusalOf { JsonBody.of(body(text), "name") }

        assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.BAD_REQUEST to RainErrorCodes.MALFORMED_BODY)
    }

    @Test
    fun `no body reads as an object with no fields, so a required field is 422 required`() {
        val form = JsonBody.of(null, "name", "includingCurrent", "ids")

        assertThat(refusalOf { form.requiredText("name") }.pointed()).containsExactly("/name" to RainErrorCodes.REQUIRED)
        assertThat(refusalOf { form.requiredBoolean("includingCurrent") }.pointed())
            .containsExactly("/includingCurrent" to RainErrorCodes.REQUIRED)
        assertThat(refusalOf { form.canonicalIds("ids", 10) }.pointed()).containsExactly("/ids" to RainErrorCodes.REQUIRED)
    }

    @Test
    fun `a text field is a string, and null is no value`() {
        val form = JsonBody.of(body("""{"slug":7,"name":null}"""), "slug", "name")

        assertThat(refusalOf { form.requiredText("slug") }.pointed()).containsExactly("/slug" to RainErrorCodes.INVALID_FORMAT)
        assertThat(refusalOf { form.requiredText("name") }.pointed()).containsExactly("/name" to RainErrorCodes.REQUIRED)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["\"true\"", "1", "{}"])
    fun `a boolean field is true or false and nothing that looks like it`(written: String) {
        val form = JsonBody.of(body("""{"includingCurrent":$written}"""), "includingCurrent")

        assertThat(refusalOf { form.requiredBoolean("includingCurrent") }.pointed())
            .containsExactly("/includingCurrent" to RainErrorCodes.INVALID_FORMAT)
        assertThat(JsonBody.of(body("""{"includingCurrent":false}"""), "includingCurrent").requiredBoolean("includingCurrent")).isFalse()
    }

    @Test
    fun `a profile is an object of strings, and an absent or null profile is empty`() {
        assertThat(JsonBody.of(body("""{"profile":{"team":"night"}}"""), "profile").textMap("profile")).containsExactly(
            java.util.Map.entry("team", "night"),
        )
        assertThat(JsonBody.of(body("""{"profile":null}"""), "profile").textMap("profile")).isEmpty()
        assertThat(JsonBody.of(body("{}"), "profile").textMap("profile")).isEmpty()
        assertThat(refusalOf { JsonBody.of(body("""{"profile":["night"]}"""), "profile").textMap("profile") }.pointed())
            .containsExactly("/profile" to RainErrorCodes.INVALID_FORMAT)
        assertThat(refusalOf { JsonBody.of(body("""{"profile":{"team":3}}"""), "profile").textMap("profile") }.pointed())
            .containsExactly("/profile" to RainErrorCodes.INVALID_FORMAT)
    }

    @Test
    fun `ids are a non-empty array of canonical ids, and each refusal points at what was wrong`() {
        fun ids(written: String) = JsonBody.of(body("""{"ids":$written}"""), "ids").canonicalIds("ids", 3)

        assertThat(ids("""["$ID","$ID"]""")).containsExactly(UUID.fromString(ID), UUID.fromString(ID))
        assertThat(refusalOf { ids("\"$ID\"") }.pointed()).containsExactly("/ids" to RainErrorCodes.INVALID_FORMAT)
        assertThat(refusalOf { ids("[]") }.pointed()).containsExactly("/ids" to RainErrorCodes.INVALID_FORMAT)
        assertThat(refusalOf { ids("""["$ID",5]""") }.pointed()).containsExactly("/ids/1" to RainErrorCodes.INVALID_ID)
        assertThat(refusalOf { ids("""["$ID","1-1-1-1-1"]""") }.pointed()).containsExactly("/ids/1" to RainErrorCodes.INVALID_ID)
    }

    @Test
    fun `a bulk request naming more ids than its bound is 400 before any id is read`() {
        val refusal = refusalOf { JsonBody.of(body("""{"ids":["$ID","$ID","$ID","not an id"]}"""), "ids").canonicalIds("ids", 3) }

        assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.BAD_REQUEST to RainErrorCodes.BAD_REQUEST)
        assertThat(refusal.pointed()).containsExactly("/ids" to RainErrorCodes.OUT_OF_RANGE)
    }

    @Test
    fun `reading a field the route did not declare is a programming error, not a refusal`() {
        assertThatThrownBy { JsonBody.of(body("{}"), "name").requiredText("slug") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

/** A keyset page's parameters: a limit within the declared page, stated once, and a cursor the route can read. */
class PageRequestRefusesWhatItCannotReadTest {
    private val pages = PageRequest(AccessProperties.Page(defaultSize = 50, maxSize = 200))

    private fun request(vararg parameters: Pair<String, String>): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/api/roles").apply { parameters.forEach { (name, value) -> addParameter(name, value) } }

    @Test
    fun `no limit is the declared default size, and no cursor starts at the beginning`() {
        assertThat(pages.limit(request())).isEqualTo(50)
        assertThat(pages.after(request(), PageRequest::slug)).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["limit", "after"])
    fun `a parameter stated twice is 400 bad_query`(name: String) {
        val twice = request(name to "5", name to "6")

        val refusal = refusalOf { if (name == "limit") pages.limit(twice) else pages.after(twice, PageRequest::slug) }

        assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.BAD_REQUEST to RainErrorCodes.BAD_QUERY)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["+5", "1000000000", "5.0", " 5"])
    fun `a limit that is not a plain whole number within the page is 400 bad_query`(written: String) {
        assertThat(refusalOf { pages.limit(request("limit" to written)) }.code).isEqualTo(RainErrorCodes.BAD_QUERY)
    }

    @Test
    fun `a cursor the route cannot read is 400 invalid_cursor`() {
        listOf<(MockHttpServletRequest) -> Any?>(
            { pages.after(it, PageRequest::slug) },
            { pages.after(it, PageRequest::code) },
            { pages.after(it, PageRequest::sessionCursor) },
        ).forEach { read ->
            val refusal = refusalOf { read(request("after" to "Not A Cursor")) }
            assertThat(refusal.kind to refusal.code).isEqualTo(FaultKind.BAD_REQUEST to AccessErrorCodes.INVALID_CURSOR)
        }
    }

    @Test
    fun `a session cursor is the session's creation in epoch microseconds and its canonical id, and reads back as written`() {
        val cursor = SessionCursor(START.plus(123_456, ChronoUnit.MICROS), UUID.fromString(ID))

        val written = PageRequest.sessionCursorOf(cursor)

        assertThat(written).isEqualTo("${ChronoUnit.MICROS.between(java.time.Instant.EPOCH, cursor.createdAt)}_$ID")
        assertThat(PageRequest.sessionCursor(written)).isEqualTo(cursor)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "1789466400000000",
            "1789466400000000_",
            "_$ID",
            "12345678901234567890_$ID",
            "9223372036854775808_$ID",
            "1789466400000000_018f5b3a2c2d7e4f8a9b0c1d2e3f4a5c1234",
            "-1_$ID",
        ],
    )
    fun `a session cursor in any other shape is not a cursor`(written: String) {
        assertThat(PageRequest.sessionCursor(written)).isNull()
    }

    @Test
    fun `a route that reads no parameter says so when one is sent`() {
        val refusal = refusalOf { pages.only(request("limit" to "5")) }

        assertThat(refusal.code).isEqualTo(RainErrorCodes.UNKNOWN_PARAMETER)
        assertThat(refusal.message).contains("reads no parameter").contains("limit")
    }
}
