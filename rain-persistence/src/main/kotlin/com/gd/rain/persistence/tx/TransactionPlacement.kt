package com.gd.rain.persistence.tx

import org.jooq.DSLContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.ResourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * A non-secret, process-local identity of the database plane a transaction uses.
 *
 * The value deliberately has no public constructor and its string representation contains only a
 * digest. A datasource URL, JDBC credentials, and a tenant identifier are never suitable backing
 * identities: they are either secret, mutable, or both. Adapters obtain one by inspecting their
 * transaction manager, while tests and non-Spring implementations can mint a named identity.
 */
public class BackingIdentity private constructor(
    private val bytes: ByteArray,
) {
    /** A stable diagnostic form that is safe for structured logs, but not a metric label. */
    public val diagnosticDigest: String = bytes.take(8).joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean = other is BackingIdentity && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "backing:$diagnosticDigest"

    public companion object {
        /**
         * Mints an identity for a stable, non-secret adapter descriptor.
         *
         * [namespace] prevents two independently named systems from treating their local names as
         * interchangeable. This is intended for deterministic memory stores and explicit tenant
         * data planes, never as a replacement for [TransactionPlacement.inspect] in Spring JDBC.
         */
        public fun named(
            namespace: String,
            name: String,
        ): BackingIdentity {
            require(IDENTIFIER.matches(namespace)) { "backing namespace is not a stable identifier" }
            require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) {
                "backing name is blank or exceeds $MAX_NAME_BYTES UTF-8 bytes"
            }
            return BackingIdentity(digest("rain.backing.v1", namespace, name))
        }

        internal fun resource(resource: Any): BackingIdentity = BackingIdentity(Registry.backing(resource))

        private const val MAX_NAME_BYTES: Int = 512
        private val IDENTIFIER: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/**
 * Opaque proof that work is attached to one currently active transaction on a particular backing.
 *
 * An authority is intentionally not serializable and cannot be recreated from a [BackingIdentity].
 * Equality is useful only to prove a same-unit composition; it never grants access to a connection.
 */
public class TransactionAuthority internal constructor(
    public val backing: BackingIdentity,
    private val token: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TransactionAuthority &&
            backing == other.backing && token.contentEquals(other.token)

    override fun hashCode(): Int = 31 * backing.hashCode() + token.contentHashCode()

    override fun toString(): String = "transaction:${backing.diagnosticDigest}"

    internal companion object {
        fun of(
            backing: BackingIdentity,
            holder: Any,
        ): TransactionAuthority = TransactionAuthority(backing, Registry.authority(holder))
    }
}

/**
 * The placement discovered without checking out another connection.
 *
 * [authority] is null when no Spring transaction is active. The backing is still present, which
 * lets a caller reject a foreign token before it tries to start an operation on the wrong plane.
 */
public data class TransactionPlacement(
    public val backing: BackingIdentity,
    public val authority: TransactionAuthority?,
) {
    /** Returns the active proof or refuses before any statement can run. */
    public fun requireAuthority(): TransactionAuthority = checkNotNull(authority) { "a caller-owned transaction is required" }

    public companion object {
        /**
         * Inspects Spring's already-bound resource for [transactions]; it never calls the
         * [DSLContext]'s connection provider. Passing a DSL attached to another datasource is a
         * composition error: adapters must construct their DSL and transaction manager together.
         */
        public fun inspect(
            dsl: DSLContext,
            transactions: PlatformTransactionManager,
        ): TransactionPlacement {
            requireNotNull(dsl.configuration()) { "DSLContext has no configuration" }
            val resource =
                (transactions as? ResourceTransactionManager)?.resourceFactory
                    ?: throw IllegalArgumentException("transaction manager does not expose a resource factory")
            val backing = BackingIdentity.resource(resource)
            val holder =
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    TransactionSynchronizationManager.getResource(resource)
                } else {
                    null
                }
            return TransactionPlacement(backing, holder?.let { TransactionAuthority.of(backing, it) })
        }
    }
}

/** Rejects a composition unless both sides are in one active transaction on one backing. */
public object SameTransactionAuthority {
    public fun require(
        left: TransactionPlacement,
        right: TransactionPlacement,
    ): TransactionAuthority {
        check(left.backing == right.backing) { "transaction backings differ" }
        val authority = left.requireAuthority()
        check(authority == right.requireAuthority()) { "transaction authorities differ" }
        return authority
    }

    public fun require(
        left: TransactionAuthority,
        right: TransactionAuthority,
    ): TransactionAuthority {
        check(left == right) { "transaction authorities differ" }
        return left
    }
}

private object Registry {
    private val backings: IdentityHashMap<Any, ByteArray> = IdentityHashMap()
    private val authorities: IdentityHashMap<Any, ByteArray> = IdentityHashMap()

    fun backing(resource: Any): ByteArray =
        synchronized(backings) {
            backings.getOrPut(resource) { digest("rain.backing.resource.v1", resource.javaClass.name, randomMarker()) }.copyOf()
        }

    fun authority(holder: Any): ByteArray =
        synchronized(authorities) {
            authorities.getOrPut(holder) { digest("rain.transaction.authority.v1", holder.javaClass.name, randomMarker()) }.copyOf()
        }

    private fun randomMarker(): String =
        java.util.UUID
            .randomUUID()
            .toString()
}

private fun digest(vararg parts: String): ByteArray =
    MessageDigest
        .getInstance("SHA-256")
        .apply {
            parts.forEach {
                update(it.toByteArray(Charsets.UTF_8))
                update(0)
            }
        }.digest()
