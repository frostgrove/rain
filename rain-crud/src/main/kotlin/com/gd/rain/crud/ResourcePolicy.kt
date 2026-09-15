package com.gd.rain.crud

import com.gd.rain.core.actor.Actor
import com.gd.rain.crud.query.FieldGrant
import com.gd.rain.crud.query.Predicate

/** What a resource may be asked to do. */
public enum class Action(
    public val wire: String,
) {
    READ("read"),
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
}

/** What a caller needs for one action: every one of a set of permissions, or only to be authenticated (with the reason). */
public sealed interface ActionAccess {
    public class Permissions(
        permissions: Set<String>,
    ) : ActionAccess {
        public val permissions: Set<String> = permissions.toSortedSet()

        init {
            require(this.permissions.isNotEmpty()) { "an action that needs permissions names at least one" }
            require(
                this.permissions.none { it.isEmpty() || it.any(Char::isWhitespace) },
            ) { "a permission is non-empty and has no whitespace" }
        }

        override fun toString(): String = "Permissions(${permissions.joinToString(", ")})"
    }

    public class Authenticated(
        public val why: String,
    ) : ActionAccess {
        init {
            require(why.isNotBlank()) { "an action open to every authenticated caller says why" }
        }

        override fun toString(): String = "Authenticated($why)"
    }

    public companion object {
        public fun permissions(vararg permissions: String): Permissions = Permissions(permissions.toSet())
    }
}

/** Who is calling, as rain-crud needs to know it. */
public sealed interface Caller {
    public data object Anonymous : Caller

    public interface Authenticated : Caller {
        public val actor: Actor

        /**
         * Whether this caller holds every one of [permissions]. It is asked about the permissions an
         * operation needs, never for everything a caller holds, so an implementation can answer with one
         * bounded lookup.
         */
        public fun holdsAll(permissions: Set<String>): Boolean
    }
}

/**
 * Resolves the caller of the operation running on the current thread. The module that authenticates
 * requests provides it; rain-crud never reads a security context itself.
 */
public fun interface CallerLookup {
    public fun current(): Caller
}

/** Which rows of a resource a caller has at all, whatever the permissions. */
public sealed interface ScopeRule {
    /** Every row. */
    public data object Unrestricted : ScopeRule

    /**
     * The rows matching the predicate for this caller. Every read and every write is confined to them, and a write that
     * leaves a row behind — a create, an update, a replacement, a bulk update — leaves it matching the predicate, or it
     * writes nothing.
     */
    public fun interface Rows : ScopeRule {
        public fun of(caller: Caller.Authenticated): Predicate
    }
}

/**
 * Who may do what to a resource: the [access] per action (an action without an entry is refused to
 * everybody), the [scope] of rows every operation is confined to, and the fields a write may name.
 */
public class ResourcePolicy(
    access: Map<Action, ActionAccess>,
    public val scope: ScopeRule,
    public val writable: FieldGrant,
) {
    public val access: Map<Action, ActionAccess> = access.toMap()
}
