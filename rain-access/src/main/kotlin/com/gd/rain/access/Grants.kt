package com.gd.rain.access

/**
 * One declared capability. [code] is at least two dot-separated lower-case segments (`ticket.read`); it is what an
 * `@Access(permissions = …)` names and what a grant stores.
 */
public data class PermissionDef(
    public val code: String,
    public val name: String,
) {
    init {
        require(isWellFormed(code)) { "a permission code matches $CODE_PATTERN, got \"$code\"" }
        require(name.isNotBlank() && name.length <= MAX_NAME) { "permission $code has a name of 1..$MAX_NAME characters" }
    }

    public companion object {
        public const val CODE_PATTERN: String = "^[a-z][a-z0-9_-]{0,31}(\\.[a-z][a-z0-9_-]{0,31}){1,3}$"
        public const val MAX_NAME: Int = 256

        private val FORMAT = Regex(CODE_PATTERN)

        public fun isWellFormed(code: String): Boolean = FORMAT.matches(code)
    }
}

/**
 * What one module contributes to the catalogue, as a bean: its permissions, and which declared system role holds
 * which of its codes. A role named in [roles] must be declared by a [SystemRoleDeclaration]; a code named there must
 * be one of [permissions].
 */
public data class ModuleGrants(
    public val module: String,
    public val permissions: List<PermissionDef>,
    public val roles: Map<String, Set<String>> = emptyMap(),
) {
    init {
        require(MODULE.matches(module)) { "a grants module matches ${MODULE.pattern}, got \"$module\"" }
    }

    private companion object {
        val MODULE = Regex("^[a-z][a-z0-9_-]{0,63}$")
    }
}

/**
 * A role the application owns, declared as a bean and written by the catalogue synchronisation. A system role refuses
 * renames, deletes and detaches through the API. With [grantsEveryPermission] it holds every permission there is,
 * including ones declared after it, without a grant row per permission.
 */
public data class SystemRoleDeclaration(
    public val slug: String,
    public val name: String,
    public val grantsEveryPermission: Boolean,
) {
    init {
        require(isWellFormedSlug(slug)) { "a role slug matches $SLUG_PATTERN, got \"$slug\"" }
        require(name.isNotBlank() && name.length <= MAX_NAME) { "role $slug has a name of 1..$MAX_NAME characters" }
    }

    public companion object {
        public const val SLUG_PATTERN: String = "^[a-z][a-z0-9-]{0,63}$"
        public const val MAX_NAME: Int = 256

        private val SLUG = Regex(SLUG_PATTERN)

        public fun isWellFormedSlug(slug: String): Boolean = SLUG.matches(slug)
    }
}

/**
 * Exempts request mappings whose handler bean is a [handlerType] from the start-up surface verification and from
 * `@Access` enforcement, with the reason written where the application can be read. Nothing else exempts a handler:
 * not its package, not its library.
 */
public data class SurfaceExemption(
    public val handlerType: Class<*>,
    public val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "the exemption of ${handlerType.name} says nothing about why" }
    }
}
