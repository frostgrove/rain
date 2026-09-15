package com.gd.rain.crud.query

/**
 * Which fields (or relations) a resource lets a query use one way — filter, sort, select or include.
 *
 * There are exactly three shapes and no empty list: an `Only` that named nothing would be a grant
 * nobody can tell apart from "everything" or "nothing", so it is refused when it is constructed. Say
 * [None] to grant nothing and [All] to grant every declared field.
 */
public sealed interface FieldGrant {
    public fun grants(name: String): Boolean

    public data object None : FieldGrant {
        override fun grants(name: String): Boolean = false
    }

    public data object All : FieldGrant {
        override fun grants(name: String): Boolean = true
    }

    public class Only(
        names: Set<String>,
    ) : FieldGrant {
        public val names: Set<String> = names.toSet()

        init {
            require(this.names.isNotEmpty()) { "FieldGrant.Only names at least one field; grant nothing with FieldGrant.None" }
        }

        override fun grants(name: String): Boolean = name in names

        override fun equals(other: Any?): Boolean = other is Only && other.names == names

        override fun hashCode(): Int = names.hashCode()

        override fun toString(): String = "Only(${names.sorted().joinToString(", ")})"
    }

    public companion object {
        public fun only(vararg names: String): Only = Only(names.toSet())
    }
}
