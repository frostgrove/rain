package com.gd.rain.core.error

/** The codes one owner declares. rain modules ship one each; an application ships its own. */
public interface ErrorCodeCatalog {
    /** Who declares these codes; named in a refusal when two catalogs collide. */
    public val owner: String

    public val codes: List<ErrorCode>
}

/**
 * Every code that may reach a client, and who declared it.
 *
 * Built once from all catalogs. A value declared twice — by two catalogs, or twice by one — is a
 * refusal naming every declaring owner, never a first-wins merge.
 */
public class ErrorCodeRegistry private constructor(
    private val owners: Map<String, String>,
) {
    public operator fun contains(code: ErrorCode): Boolean = owners.containsKey(code.value)

    public fun ownerOf(code: ErrorCode): String? = owners[code.value]

    public val size: Int get() = owners.size

    public companion object {
        public fun of(catalogs: List<ErrorCodeCatalog>): ErrorCodeRegistration {
            val declaredBy = linkedMapOf<String, MutableList<String>>()
            catalogs.forEach { catalog ->
                catalog.codes.forEach { code -> declaredBy.getOrPut(code.value) { mutableListOf() } += catalog.owner }
            }
            val duplicates =
                declaredBy
                    .filterValues { it.size > 1 }
                    .toSortedMap()
                    .map { (value, owners) -> "error code \"$value\" is declared by ${owners.joinToString(", ")}" }
            if (duplicates.isNotEmpty()) return ErrorCodeRegistration.Refused(duplicates)
            return ErrorCodeRegistration.Registered(ErrorCodeRegistry(declaredBy.mapValues { it.value.single() }))
        }
    }
}

public sealed interface ErrorCodeRegistration {
    public data class Registered(
        public val registry: ErrorCodeRegistry,
    ) : ErrorCodeRegistration

    public data class Refused(
        public val problems: List<String>,
    ) : ErrorCodeRegistration
}
