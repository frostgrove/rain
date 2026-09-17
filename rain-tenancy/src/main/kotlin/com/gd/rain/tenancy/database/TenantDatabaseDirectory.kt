package com.gd.rain.tenancy.database

import com.gd.rain.tenancy.TenantAuthorityUnavailableException
import com.gd.rain.tenancy.TenantDigest
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.control.TenantReferenceDigest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Immutable non-secret placement data supplied by the control-plane directory. */
public class TenantDatabasePlacement(
    jdbcUrl: String,
    public val databaseId: String,
    public val secretRef: String,
    public val placementVersion: Long,
    public val credentialVersion: Long,
    catalogueFingerprint: ByteArray,
) {
    private val jdbcUrl: String = jdbcUrl
    private val catalogueFingerprint: ByteArray = catalogueFingerprint.copyOf()

    init {
        require(jdbcUrl.startsWith("jdbc:postgresql:")) { "tenant placement uses PostgreSQL JDBC" }
        require(DATABASE_ID.matches(databaseId)) { "tenant database id is not stable" }
        require(secretRef.isNotBlank() && secretRef.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_REF_BYTES) {
            "tenant secret reference is out of bounds"
        }
        require(placementVersion > 0 && credentialVersion > 0) { "tenant placement and credential versions are positive" }
        require(this.catalogueFingerprint.size == FINGERPRINT_BYTES) { "tenant catalogue fingerprint has 32 bytes" }
    }

    public fun catalogueFingerprint(): ByteArray = catalogueFingerprint.copyOf()

    override fun toString(): String = "tenant-database-placement[redacted]"

    internal fun jdbcUrl(): String = jdbcUrl

    private companion object {
        const val FINGERPRINT_BYTES: Int = 32
        const val MAX_SECRET_REF_BYTES: Int = 512
        val DATABASE_ID: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** Resolves a capability's non-secret placement. It is deliberately separate from credential lookup. */
public fun interface TenantPlacementDirectory {
    public fun locate(scope: TenantScope): TenantDatabasePlacement
}

/** Short-lived application credentials obtained from a secret reference, never from a public URL. */
public class TenantDatabaseCredential(
    username: String,
    password: CharArray,
) : AutoCloseable {
    private val username: String = username
    private val password: CharArray = password.copyOf()
    private var closed: Boolean = false

    init {
        require(username.isNotBlank() && username.toByteArray(Charsets.UTF_8).size <= MAX_USERNAME_BYTES) {
            "tenant database username is out of bounds"
        }
        require(this.password.isNotEmpty()) { "tenant database password is blank" }
    }

    internal fun username(): String = username

    internal fun password(): String {
        check(!closed) { "tenant database credential was closed" }
        return password.concatToString()
    }

    override fun close() {
        if (!closed) {
            password.fill('\u0000')
            closed = true
        }
    }

    override fun toString(): String = "tenant-database-credential[redacted]"

    private companion object {
        const val MAX_USERNAME_BYTES: Int = 256
    }
}

/** Resolves only the credential selected by a non-secret control-plane reference and version. */
public fun interface TenantSecretResolver {
    public fun resolve(
        secretRef: String,
        credentialVersion: Long,
    ): TenantDatabaseCredential
}

/** Verifies that an application connection really points at the tenant database it was admitted for. */
public fun interface TenantSourceFenceVerifier {
    public fun verify(
        connection: Connection,
        scope: TenantScope,
        placement: TenantDatabasePlacement,
    ): Unit
}

/** PostgreSQL implementation of the source fence stored in every tenant database. */
public class PostgresTenantSourceFenceVerifier(
    private val origin: String,
    private val referenceDigest: TenantReferenceDigest,
) : TenantSourceFenceVerifier {
    init {
        require(ORIGIN.matches(origin)) { "tenant source-fence origin is not stable" }
    }

    override fun verify(
        connection: Connection,
        scope: TenantScope,
        placement: TenantDatabasePlacement,
    ) {
        connection.prepareStatement(QUERY).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw TenantSourceMismatchException()
                val matches =
                    rows.getString("origin") == origin &&
                        MessageDigest.isEqual(rows.getBytes("tenant_ref_digest"), referenceDigest.digest(scopeRef(scope)).copy()) &&
                        rows.getLong("tenant_epoch") == scope.epoch.value &&
                        rows.getString("database_id") == placement.databaseId &&
                        MessageDigest.isEqual(rows.getBytes("catalogue_fingerprint"), placement.catalogueFingerprint())
                if (!matches || rows.next()) throw TenantSourceMismatchException()
            }
        }
    }

    private companion object {
        val ORIGIN: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
        const val QUERY: String =
            "SELECT origin, tenant_ref_digest, tenant_epoch, database_id, catalogue_fingerprint " +
                "FROM rain_tenancy_binding"
    }
}

/** Closed failure: the pool was pointed at a database other than the one the scope admits. */
public class TenantSourceMismatchException : IllegalStateException("tenant database source fence does not match")

/** Closed failure: preserving existing borrowers requires refusing a new database pool. */
public class TenantDatabaseCapacityException : IllegalStateException("tenant database directory has no available capacity")

/** Closed failure: placement, secret resolution, or a pool open could not complete within its budget. */
public class TenantDatabaseUnavailableException(
    cause: Throwable? = null,
) : TenantAuthorityUnavailableException("tenant database is unavailable", cause)

/** Mandatory capacity and lifecycle bounds for a database-per-tenant directory. */
public data class TenantDatabaseDirectorySettings(
    public val maxOpenPools: Int,
    public val maxTotalConnections: Int,
    public val perTenantMaxPool: Int,
    public val idleTtl: Duration,
    public val openTimeout: Duration,
    public val shutdownDrainTimeout: Duration,
) {
    init {
        require(maxOpenPools > 0 && maxTotalConnections > 0 && perTenantMaxPool > 0) {
            "tenant database pool bounds are positive"
        }
        require(maxTotalConnections >= perTenantMaxPool) { "tenant connection budget cannot fit one pool" }
        require(!idleTtl.isNegative && !idleTtl.isZero) { "tenant pool idle TTL is positive" }
        require(!openTimeout.isNegative && !openTimeout.isZero) { "tenant pool open timeout is positive" }
        require(!shutdownDrainTimeout.isNegative) { "tenant pool shutdown drain timeout is not negative" }
        require(idleTtl.toMillis() > 0 && openTimeout.toMillis() > 0) { "tenant pool durations have millisecond precision" }
    }
}

/** A borrowed tenant backing. It has transaction services, but never exposes a datasource or credential. */
public interface TenantDatabaseLease : AutoCloseable {
    public val dsl: DSLContext
    public val transactionManager: PlatformTransactionManager

    override fun close(): Unit
}

/**
 * Bounded directory of tenant-specific Hikari pools.
 *
 * One asynchronous open is shared by concurrent first borrowers of the same fenced key. Capacity
 * is reserved before that open starts, while closing waits until the final borrower releases its
 * lease. A failed or mismatched fence never enters the lookup map.
 */
public class TenantDatabaseDirectory(
    private val placements: TenantPlacementDirectory,
    private val secrets: TenantSecretResolver,
    private val sourceFence: TenantSourceFenceVerifier,
    private val referenceDigest: TenantReferenceDigest,
    private val settings: TenantDatabaseDirectorySettings,
    private val clock: Clock,
    private val opener: ExecutorService = Executors.newFixedThreadPool(settings.maxOpenPools.coerceAtMost(MAX_OPEN_THREADS)),
    private val reaper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) : AutoCloseable {
    private val lock: Any = Any()
    private val entries: MutableMap<DirectoryKey, Entry> = linkedMapOf()
    private val openings: MutableMap<DirectoryKey, CompletableFuture<Entry>> = linkedMapOf()
    private var reservedConnections: Int = 0
    private var accepting: Boolean = true

    init {
        reaper.scheduleWithFixedDelay(
            ::reapIdle,
            settings.idleTtl.toMillis(),
            settings.idleTtl.toMillis().coerceAtMost(MAX_REAP_INTERVAL_MILLIS),
            TimeUnit.MILLISECONDS,
        )
    }

    /** Borrows an already-fenced backing for [DatabaseTenantDataPlane], never as a public escape hatch. */
    internal fun borrow(scope: TenantScope): TenantDatabaseLease {
        val placement = locate(scope)
        val key = DirectoryKey(referenceDigest.digest(scopeRef(scope)), scope, placement)
        val stale = mutableListOf<Entry>()
        val opening: CompletableFuture<Entry>
        val startsOpening: Boolean
        var direct: Entry? = null
        var capacityRefused: Boolean = false
        synchronized(lock) {
            check(accepting) { "tenant database directory is closed" }
            stale += evictIdle(clock.instant())
            entries[key]?.let { entry ->
                entry.borrowers += 1
                direct = entry
            }
            if (direct == null) {
                val existing = openings[key]
                if (existing != null) {
                    opening = existing
                    startsOpening = false
                } else if (
                    entries.size + openings.size >= settings.maxOpenPools ||
                    reservedConnections + settings.perTenantMaxPool > settings.maxTotalConnections
                ) {
                    opening = CompletableFuture()
                    startsOpening = false
                    capacityRefused = true
                } else {
                    reservedConnections += settings.perTenantMaxPool
                    opening = CompletableFuture()
                    openings[key] = opening
                    startsOpening = true
                }
            } else {
                opening = CompletableFuture()
                startsOpening = false
            }
        }
        stale.forEach(Entry::close)
        direct?.let { return Lease(key, it) }
        if (capacityRefused) throw TenantDatabaseCapacityException()
        if (startsOpening) opener.execute { completeOpen(key, scope, placement, opening) }
        return acquire(key, opening)
    }

    /** Stops new borrows, drains existing leases within the configured budget, then closes pools. */
    override fun close() {
        val closing: MutableList<Entry> = mutableListOf()
        val pending: List<CompletableFuture<Entry>>
        synchronized(lock) {
            if (!accepting) return
            accepting = false
            pending = openings.values.toList()
            pending.forEach { it.completeExceptionally(TenantDatabaseUnavailableException()) }
            closing += evictIdle(clock.instant())
            val deadline = System.nanoTime() + settings.shutdownDrainTimeout.toNanos()
            while (entries.values.any { it.borrowers > 0 }) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            closing += entries.values
            entries.clear()
        }
        closing.forEach(Entry::close)
        opener.shutdown()
        reaper.shutdownNow()
    }

    private fun locate(scope: TenantScope): TenantDatabasePlacement {
        val placement =
            try {
                placements.locate(scope)
            } catch (failure: TenantDatabaseCapacityException) {
                throw failure
            } catch (failure: TenantSourceMismatchException) {
                throw failure
            } catch (failure: Throwable) {
                throw TenantDatabaseUnavailableException(failure)
            }
        require(placement.placementVersion == scope.placementVersion) { "tenant placement version is stale" }
        return placement
    }

    private fun acquire(
        key: DirectoryKey,
        opening: CompletableFuture<Entry>,
    ): TenantDatabaseLease {
        val entry =
            try {
                opening.get(settings.openTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (failure: java.util.concurrent.TimeoutException) {
                throw TenantDatabaseUnavailableException(failure)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw TenantDatabaseUnavailableException(failure)
            } catch (failure: ExecutionException) {
                val cause = failure.cause
                if (cause is TenantSourceMismatchException) throw cause
                if (cause is TenantDatabaseCapacityException) throw cause
                throw TenantDatabaseUnavailableException(cause)
            }
        synchronized(lock) {
            check(accepting && entries[key] === entry) { "tenant database directory closed while opening a pool" }
            entry.borrowers += 1
            return Lease(key, entry)
        }
    }

    private fun completeOpen(
        key: DirectoryKey,
        scope: TenantScope,
        placement: TenantDatabasePlacement,
        opening: CompletableFuture<Entry>,
    ) {
        val entry =
            try {
                open(scope, placement, key)
            } catch (failure: Throwable) {
                failOpen(key, opening, failure)
                return
            }
        var discard: Boolean = false
        synchronized(lock) {
            if (!accepting || openings[key] !== opening || !opening.complete(entry)) {
                discard = true
                reservedConnections -= settings.perTenantMaxPool
            } else {
                openings.remove(key)
                entries[key] = entry
            }
        }
        if (discard) entry.close()
    }

    private fun failOpen(
        key: DirectoryKey,
        opening: CompletableFuture<Entry>,
        failure: Throwable,
    ) {
        synchronized(lock) {
            if (openings.remove(key) === opening) reservedConnections -= settings.perTenantMaxPool
            opening.completeExceptionally(failure)
        }
    }

    private fun open(
        scope: TenantScope,
        placement: TenantDatabasePlacement,
        key: DirectoryKey,
    ): Entry {
        val credential = secrets.resolve(placement.secretRef, placement.credentialVersion)
        val dataSource =
            try {
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = placement.jdbcUrl()
                        username = credential.username()
                        password = credential.password()
                        maximumPoolSize = settings.perTenantMaxPool
                        minimumIdle = 0
                        connectionTimeout = settings.openTimeout.toMillis()
                        validationTimeout = settings.openTimeout.toMillis()
                        initializationFailTimeout = settings.openTimeout.toMillis()
                        poolName = "rain-tenant-${key.diagnosticDigest}"
                    },
                )
            } finally {
                credential.close()
            }
        try {
            dataSource.connection.use { sourceFence.verify(it, scope, placement) }
            val manager = DataSourceTransactionManager(dataSource)
            return Entry(
                dataSource,
                DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
                manager,
                clock.instant(),
            )
        } catch (failure: Throwable) {
            dataSource.close()
            throw failure
        }
    }

    private fun release(
        key: DirectoryKey,
        entry: Entry,
    ) {
        synchronized(lock) {
            if (entries[key] !== entry) {
                check(!accepting) { "tenant database lease belongs to an evicted pool" }
            } else {
                check(entry.borrowers > 0) { "tenant database lease was already released" }
                entry.borrowers -= 1
                if (entry.borrowers == 0) entry.lastReleasedAt = clock.instant()
                lock.signalAll()
            }
        }
    }

    private fun evictIdle(now: Instant): List<Entry> {
        val expired =
            entries
                .filterValues { entry ->
                    entry.borrowers == 0 && !entry.lastReleasedAt.plus(settings.idleTtl).isAfter(now)
                }.keys
                .toList()
        return expired.map { key ->
            reservedConnections -= settings.perTenantMaxPool
            checkNotNull(entries.remove(key))
        }
    }

    private fun reapIdle() {
        val stale =
            synchronized(lock) {
                if (accepting) evictIdle(clock.instant()) else emptyList()
            }
        stale.forEach(Entry::close)
    }

    private inner class Lease(
        private val key: DirectoryKey,
        private val entry: Entry,
    ) : TenantDatabaseLease {
        private var closed: Boolean = false

        override val dsl: DSLContext get() = entry.dsl
        override val transactionManager: PlatformTransactionManager get() = entry.transactions

        override fun close() {
            check(!closed) { "tenant database lease closes once" }
            closed = true
            release(key, entry)
        }
    }

    private class Entry(
        private val dataSource: HikariDataSource,
        val dsl: DSLContext,
        val transactions: DataSourceTransactionManager,
        var lastReleasedAt: Instant,
        var borrowers: Int = 0,
    ) {
        fun close() {
            dataSource.close()
        }
    }

    private class DirectoryKey(
        digest: TenantDigest,
        scope: TenantScope,
        placement: TenantDatabasePlacement,
    ) {
        private val encodedDigest: String = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copy())
        private val epoch: Long = scope.epoch.value
        private val placementVersion: Long = placement.placementVersion
        private val credentialVersion: Long = placement.credentialVersion

        val diagnosticDigest: String = encodedDigest.take(DIAGNOSTIC_DIGEST_CHARS)

        override fun equals(other: Any?): Boolean =
            other is DirectoryKey &&
                encodedDigest == other.encodedDigest &&
                epoch == other.epoch &&
                placementVersion == other.placementVersion &&
                credentialVersion == other.credentialVersion

        override fun hashCode(): Int =
            listOf(encodedDigest, epoch, placementVersion, credentialVersion).fold(1) { result, value -> 31 * result + value.hashCode() }
    }

    private companion object {
        const val MAX_OPEN_THREADS: Int = 4
        const val DIAGNOSTIC_DIGEST_CHARS: Int = 12
        const val MAX_REAP_INTERVAL_MILLIS: Long = 60_000
    }
}

private fun scopeRef(scope: TenantScope) = scope.ref

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun Any.signalAll() {
    (this as java.lang.Object).notifyAll()
}
