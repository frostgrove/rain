package com.gd.rain.access.internal.usecase

import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.access.internal.store.CatalogueStore
import com.gd.rain.access.internal.store.DeclaredPermission
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.RecurringWork
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Clock
import java.time.Duration

public data class CatalogueReport(
    public val permissions: Int,
    public val systemRoles: Int,
)

/**
 * Writes the declared catalogue at start-up, before anything serves: every declared permission a row, every declared
 * system role a row, every code a module gives a system role attached. Declared codes are merged in sorted chunks of
 * `catalogue.chunk-size` — an insert that keeps existing rows, then an indexed read of exactly the chunk's ids — so the
 * work is proportional to the declarations and never to what the tables already hold. Nothing is ever detached or
 * deleted. Declarations that contradict each other are refused by [GrantDeclarationsCheck] before this runs, and are
 * not written.
 */
public class CatalogueSynchronizer(
    private val store: CatalogueStore,
    private val transactions: AccessTransactions,
    private val modules: List<ModuleGrants>,
    private val systemRoles: List<SystemRoleDeclaration>,
    private val ids: IdGenerator,
    private val chunkSize: Int,
    private val clock: Clock,
) : SmartLifecycle {
    @Volatile
    private var running = false

    public fun synchronize(): CatalogueReport {
        check(GrantDeclarationsCheck.problemsOf(modules, systemRoles).isEmpty()) {
            "the grant declarations contradict each other; the start-up check reports how"
        }
        val declared =
            modules
                .flatMap { module -> module.permissions.map { DeclaredPermission(ids.next(), it.code, it.name, module.module) } }
                .sortedBy { it.code }
        declared.chunked(chunkSize).forEach { chunk -> transactions.inTransaction { store.declarePermissions(chunk, clock.instant()) } }
        systemRoles.sortedBy { it.slug }.forEach { role ->
            val codes = modules.flatMap { it.roles[role.slug].orEmpty() }.distinct().sorted()
            transactions.inTransaction {
                val now = clock.instant()
                val id = store.declareSystemRole(ids.next(), role.slug, role.name, role.grantsEveryPermission, now)
                codes.chunked(chunkSize).forEach { chunk ->
                    val found = store.permissionIds(chunk)
                    store.attach(id, chunk.map { code -> checkNotNull(found[code]) { "declared permission $code has no row" } }, now)
                }
            }
        }
        log
            .atInfo()
            .setMessage("access catalogue synchronised")
            .addKeyValue("permissions", declared.size)
            .addKeyValue("system_roles", systemRoles.size)
            .log()
        return CatalogueReport(declared.size, systemRoles.size)
    }

    override fun start() {
        synchronize()
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    /** Before every other lifecycle, the web server's included, so no request is served against an unwritten catalogue. */
    override fun getPhase(): Int = PHASE

    public companion object {
        public const val PHASE: Int = Int.MIN_VALUE + 1000

        private val log = LoggerFactory.getLogger(CatalogueSynchronizer::class.java)
    }
}

public data class RetentionReport(
    public val expiredDeleted: Int,
    public val revokedDeleted: Int,
    public val cutoffsDeleted: Int,
    public val caughtUp: Boolean,
)

/**
 * Deletes sessions that expired or were closed more than `retention.keep-for` ago, and cutoffs older than a whole
 * session lifetime (every session they close has expired), in batches of `retention.batch`, at most
 * `retention.batches-per-run` batches of each per run, each batch one statement over its own index.
 */
public class SessionRetentionTask(
    private val sessions: SessionStore,
    private val keepFor: Duration,
    private val sessionTtl: Duration,
    override val interval: Duration,
    private val batch: Int,
    private val batchesPerRun: Int,
    private val clock: Clock,
) : RecurringWork {
    override val name: String = NAME

    override fun run() {
        val report = retainOnce()
        if (!report.caughtUp) {
            log
                .atWarn()
                .setMessage("session retention stopped at its batch budget before deleting everything due")
                .addKeyValue("expired_deleted", report.expiredDeleted)
                .addKeyValue("revoked_deleted", report.revokedDeleted)
                .addKeyValue("cutoffs_deleted", report.cutoffsDeleted)
                .log()
        }
    }

    public fun retainOnce(): RetentionReport {
        val now = clock.instant()
        val before = now.minus(keepFor)
        val (expired, expiredDone) = drain { sessions.deleteExpired(before, batch) }
        val (revoked, revokedDone) = drain { sessions.deleteRevoked(before, batch) }
        val (cutoffs, cutoffsDone) = drain { sessions.deleteCutoffs(now.minus(sessionTtl), batch) }
        return RetentionReport(expired, revoked, cutoffs, expiredDone && revokedDone && cutoffsDone)
    }

    private fun drain(step: () -> Int): Pair<Int, Boolean> {
        var deleted = 0
        repeat(batchesPerRun) {
            val removed = step()
            deleted += removed
            if (removed < batch) return deleted to true
        }
        return deleted to false
    }

    public companion object {
        public const val NAME: String = "access.session-retention"

        private val log = LoggerFactory.getLogger(SessionRetentionTask::class.java)
    }
}
