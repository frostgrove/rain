package com.gd.rain.audit

import com.gd.rain.core.actor.Actor

/**
 * A kind of evidence a module records, and the only detail keys an event of that kind may carry.
 *
 * Declared once, as a bean, by the module that records it. Detail is checked against [detailKeys] —
 * a declared schema — rather than against a list of words that must not appear, so what reaches the
 * trail is exactly what the declaration allows.
 */
public data class AuditEventType(
    public val module: String,
    public val action: String,
    public val resourceKind: String,
    public val detailKeys: Set<String> = emptySet(),
) {
    init {
        require(NAME.matches(module)) { "an audit module matches ${NAME.pattern}, got \"$module\"" }
        require(NAME.matches(action)) { "an audit action matches ${NAME.pattern}, got \"$action\"" }
        require(NAME.matches(resourceKind)) { "an audit resource kind matches ${NAME.pattern}, got \"$resourceKind\"" }
        detailKeys.forEach { require(AuditDetail.KEY.matches(it)) { "detail key \"$it\" does not match ${AuditDetail.KEY.pattern}" } }
    }

    public val id: String get() = "$module.$action"

    public companion object {
        private val NAME = Regex("^[a-z][a-z0-9_-]{0,63}$")
    }
}

public enum class AuditOutcome(
    public val wire: String,
) {
    OK("ok"),
    REFUSED("refused"),
    FAILED("failed"),
}

/**
 * One piece of evidence.
 *
 * [actor] overrides the actor of the current operation when the event is about someone else, e.g. a
 * sign-in attempt that has no authenticated actor yet.
 */
public data class AuditEvent(
    public val type: AuditEventType,
    public val outcome: AuditOutcome,
    public val resourceId: String? = null,
    public val detail: AuditDetail = AuditDetail.EMPTY,
    public val actor: Actor? = null,
) {
    init {
        val undeclared = detail.keys - type.detailKeys
        require(undeclared.isEmpty()) {
            "audit event ${type.id} carries detail ${undeclared.sorted()} its type does not declare"
        }
        require(resourceId == null || resourceId.length in 1..MAX_RESOURCE_ID) { "a resource id is 1..$MAX_RESOURCE_ID characters" }
    }

    public companion object {
        public const val MAX_RESOURCE_ID: Int = 256
    }
}
