package com.gd.rain.crud.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.crud.CappedCount
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.Page
import com.gd.rain.crud.PageWindow
import jakarta.servlet.http.HttpServletRequest
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper

/**
 * A list page on the wire.
 *
 * Cursor mode: `{"items":[…],"page":{"limit":25,"next":"…","prev":"…"}}` — `next`/`prev` absent when there is
 * no page that way. Offset mode: `"page":{"limit":25,"offset":50,"hasNext":true}`. `count` is present only
 * when the query asked for a count: `"count":{"value":…,"exact":…}`.
 */
@JsonPropertyOrder("items", "page", "count")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageBody<T>(
    public val items: List<T>,
    public val page: PageInfoBody,
    public val count: CountValueBody?,
) {
    public companion object {
        public fun <T> of(page: Page<T>): PageBody<T> =
            PageBody(
                page.items,
                when (val window = page.window) {
                    is PageWindow.Cursor -> CursorPageBody(window.limit, window.next, window.prev)
                    is PageWindow.Offset -> OffsetPageBody(window.limit, window.offset, window.hasNext)
                },
                page.count?.let(CountValueBody::of),
            )
    }
}

public sealed interface PageInfoBody

@JsonPropertyOrder("limit", "next", "prev")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CursorPageBody(
    public val limit: Int,
    public val next: String?,
    public val prev: String?,
) : PageInfoBody

@JsonPropertyOrder("limit", "offset", "hasNext")
public class OffsetPageBody(
    public val limit: Int,
    public val offset: Long,
    public val hasNext: Boolean,
) : PageInfoBody

@JsonPropertyOrder("value", "exact")
public class CountValueBody(
    public val value: Long,
    public val exact: Boolean,
) {
    public companion object {
        public fun of(count: CappedCount): CountValueBody = CountValueBody(count.value, count.exact)
    }
}

/** What `GET /count` answers: `{"count":{"value":…,"exact":…}}`. */
public class CountBody(
    public val count: CountValueBody,
)

/** What a delete answers: `{"deleted":n}`. */
public class DeletedBody(
    public val deleted: Long,
)

/**
 * The generic half of a controller that mounts a [CrudResource], so each handler method is one line:
 *
 * ```kotlin
 * @GetMapping fun list(request: HttpServletRequest) = CrudMvc.list(books, request)
 * ```
 *
 * Refusals are [Fault]s and render as problem+json through rain-web's exception handler.
 */
public object CrudMvc {
    private val STRICT: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    /** The request's parameters, each with every value it was given, as the dialect reads them. */
    public fun parameters(request: HttpServletRequest): Map<String, List<String>> =
        request.parameterMap.entries.associate { (name, values) -> name to values.toList() }

    public fun <T> list(
        resource: CrudResource<T>,
        request: HttpServletRequest,
    ): PageBody<T> = PageBody.of(resource.list(parameters(request)))

    public fun <T> count(
        resource: CrudResource<T>,
        request: HttpServletRequest,
    ): CountBody = CountBody(CountValueBody.of(resource.count(parameters(request))))

    public fun <T> get(
        resource: CrudResource<T>,
        id: String,
        request: HttpServletRequest,
    ): T = resource.get(id, parameters(request))

    public fun <T> delete(
        resource: CrudResource<T>,
        id: String,
    ): DeletedBody {
        resource.delete(id)
        return DeletedBody(1)
    }

    /** `POST …/bulk-delete` with the body `{"ids":["…",…]}` and nothing else. */
    public fun <T> bulkDelete(
        resource: CrudResource<T>,
        body: String?,
    ): DeletedBody = DeletedBody(resource.bulkDelete(bulkIds(body)))

    private fun bulkIds(body: String?): List<String> {
        val root =
            try {
                STRICT.readTree(body ?: throw malformed())
            } catch (_: JacksonException) {
                throw malformed()
            }
        if (!root.isObject || root.propertyNames().toSet() != setOf(BULK_IDS)) throw malformed()
        val ids = root.required(BULK_IDS)
        if (!ids.isArray || !ids.values().all { it.isString }) throw malformed()
        return ids.values().map { it.stringValue() }
    }

    private fun malformed(): Fault =
        Fault(FaultKind.BAD_REQUEST, RainErrorCodes.MALFORMED_BODY, "the body is {\"ids\":[…]} with string ids")

    private const val BULK_IDS = "ids"
}
