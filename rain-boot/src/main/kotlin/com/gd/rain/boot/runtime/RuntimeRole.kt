package com.gd.rain.boot.runtime

/**
 * What a running process does. The set is closed: a contribution is activated by exactly the roles
 * it names, and a role nobody declared cannot be switched on by a typo.
 */
public enum class RuntimeRole(
    public val wire: String,
) {
    /** Serves requests: routes, filters, listeners that feed responses. */
    API("api"),

    /** Consumes background work: job schedulers and the recurring work they own. */
    WORKER("worker"),

    /** Runs the seeders. */
    SEEDER("seeder"),
    ;

    public companion object {
        public val wireNames: List<String> = entries.map(RuntimeRole::wire)

        public fun fromWire(value: String): RuntimeRole? = entries.firstOrNull { it.wire == value }
    }
}
