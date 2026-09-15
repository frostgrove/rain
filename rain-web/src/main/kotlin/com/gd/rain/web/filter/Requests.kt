package com.gd.rain.web.filter

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.web.util.ServletRequestPathUtils

/**
 * GET, HEAD and OPTIONS — the methods the transport treats as safe. HTTP method names are case
 * sensitive (RFC 9110 §9.1), so `get` is not `GET`.
 */
public fun isSafeMethod(method: String): Boolean =
    method == HttpMethod.GET.name() || method == HttpMethod.HEAD.name() || method == HttpMethod.OPTIONS.name()

/**
 * The path as handler mappings match it: percent-decoded, without path parameters, and without the
 * context path — derived from the same `PathContainer` a `PathPattern` matches on, so if a handler
 * mapping matches a route, a filter asking about this path sees that route too. `requestURI` is none
 * of these: it is undecoded and keeps matrix parameters, so a filter matching it could be walked
 * around by an encoded spelling of the same route.
 *
 * Computed once per request and cached on it; filters run on the request dispatch, where the URI does
 * not change.
 */
public fun HttpServletRequest.mountedPath(): String {
    (getAttribute(MOUNTED_PATH_ATTRIBUTE) as? String)?.let { return it }
    val mounted = ServletRequestPathUtils.parseAndCache(this).pathWithinApplication().asMounted()
    setAttribute(MOUNTED_PATH_ATTRIBUTE, mounted)
    return mounted
}

private fun PathContainer.asMounted(): String =
    elements().joinToString(separator = "") { element ->
        when (element) {
            is PathContainer.PathSegment -> element.valueToMatch()
            else -> element.value()
        }
    }

private const val MOUNTED_PATH_ATTRIBUTE = "com.gd.rain.web.filter.mountedPath"
