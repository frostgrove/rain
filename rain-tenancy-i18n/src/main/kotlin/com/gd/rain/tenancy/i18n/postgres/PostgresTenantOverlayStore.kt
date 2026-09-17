package com.gd.rain.tenancy.i18n.postgres

import com.gd.rain.i18n.CatalogOverlay
import com.gd.rain.i18n.CatalogOverlayCompilation
import com.gd.rain.i18n.CatalogOverlayCompiler
import com.gd.rain.i18n.CatalogOverlayRef
import com.gd.rain.i18n.CatalogOverlaySpec
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogSnapshot
import com.gd.rain.i18n.ContractDigest
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.MessageContractRef
import com.gd.rain.i18n.MessageKey
import com.gd.rain.i18n.OverlayLayer
import com.gd.rain.i18n.OverlayTranslationSpec
import com.gd.rain.i18n.ReviewDigest
import com.gd.rain.i18n.SourceDigest
import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionPlacement
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.i18n.ActivateTenantOverlayCommand
import com.gd.rain.tenancy.i18n.ReviewTenantOverlayCommand
import com.gd.rain.tenancy.i18n.RollbackTenantOverlayCommand
import com.gd.rain.tenancy.i18n.SetTenantOverlayCommand
import com.gd.rain.tenancy.i18n.TenantOverlayActor
import com.gd.rain.tenancy.i18n.TenantOverlayAudit
import com.gd.rain.tenancy.i18n.TenantOverlayAuditKind
import com.gd.rain.tenancy.i18n.TenantOverlayCatalogs
import com.gd.rain.tenancy.i18n.TenantOverlayChange
import com.gd.rain.tenancy.i18n.TenantOverlayChangePage
import com.gd.rain.tenancy.i18n.TenantOverlayCommand
import com.gd.rain.tenancy.i18n.TenantOverlayCurrent
import com.gd.rain.tenancy.i18n.TenantOverlayOperation
import com.gd.rain.tenancy.i18n.TenantOverlayOutcome
import com.gd.rain.tenancy.i18n.TenantOverlayProblem
import com.gd.rain.tenancy.i18n.TenantOverlayRevision
import com.gd.rain.tenancy.i18n.TenantOverlayRevisionState
import com.gd.rain.tenancy.i18n.TenantOverlayScope
import com.gd.rain.tenancy.i18n.TransactionalTenantOverlayStore
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.dao.DataAccessException
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * PostgreSQL tenant-overlay lifecycle store. Every call joins a caller-owned transaction; the
 * per-epoch advisory lock and persisted optimistic version fence a create/update race and ABA.
 * Tenant namespaces are opaque 32-byte authority-derived digests, never raw tenant references.
 */
public class PostgresTenantOverlayStore(
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    private val catalogs: TenantOverlayCatalogs,
    private val clock: Clock = Clock.systemUTC(),
) : TransactionalTenantOverlayStore {
    override val backing: BackingIdentity = TransactionPlacement.inspect(dsl, transactionManager).backing
    private val active: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    override fun <T> inCallerTransaction(block: () -> T): T {
        val placement = TransactionPlacement.inspect(dsl, transactionManager)
        check(placement.backing == backing) { "tenant overlay store backing changed" }
        placement.requireAuthority()
        check(!active.get()) { "tenant overlay transaction callbacks do not nest" }
        active.set(true)
        return try {
            block()
        } finally {
            active.remove()
        }
    }

    override fun execute(command: TenantOverlayCommand): TenantOverlayOutcome {
        requireTransaction()
        lock(command.scope)
        receipt(command.scope, command.operation)?.let { stored ->
            return if (stored.fingerprint.contentEquals(fingerprint(command))) {
                stored.outcome
            } else {
                invalid("operation", "was already used for a different command")
            }
        }
        val head = head(command.scope)
        if (head?.version ?: 0 != command.expectedVersion) return TenantOverlayOutcome.Conflict(head?.version ?: 0)
        val outcome =
            when (command) {
                is SetTenantOverlayCommand -> stage(command)
                is ReviewTenantOverlayCommand -> review(command)
                is ActivateTenantOverlayCommand -> activate(command, TenantOverlayAuditKind.ACTIVATED)
                is RollbackTenantOverlayCommand -> rollback(command)
            }
        persistReceipt(command, outcome)
        return outcome
    }

    override fun current(
        scope: TenantOverlayScope,
        snapshot: CatalogSnapshot,
    ): TenantOverlayCurrent {
        requireTransaction()
        val head = head(scope) ?: return TenantOverlayCurrent(0, null)
        val revision = head.activeRevision ?: return TenantOverlayCurrent(head.version, null)
        val stored = revision(scope, revision) ?: return TenantOverlayCurrent(head.version, null)
        val expected = stored.reference ?: return TenantOverlayCurrent(head.version, null)
        if (expected != head.active || stored.spec.base != snapshot.reference) return TenantOverlayCurrent(head.version, null)
        val overlay = (CatalogOverlayCompiler.compile(snapshot, stored.spec) as? CatalogOverlayCompilation.Compiled)?.overlay
        return TenantOverlayCurrent(head.version, overlay?.takeIf { it.reference == expected })
    }

    override fun revisions(scope: TenantOverlayScope): List<TenantOverlayRevision> {
        requireTransaction()
        return dsl.fetch(REVISIONS, namespace(scope), scope.epoch.value).map { row ->
            TenantOverlayRevision(
                CatalogRef(row.string("base_revision"), Digest.parse(row.string("base_digest"))),
                row.stringOrNull("overlay_digest")?.let { digest ->
                    CatalogOverlayRef(OverlayLayer.TENANT, row.string("revision"), Digest.parse(digest))
                },
                TenantOverlayRevisionState.valueOf(row.string("state")),
            )
        }
    }

    override fun audits(scope: TenantOverlayScope): List<TenantOverlayAudit> {
        requireTransaction()
        return dsl.fetch(AUDITS, namespace(scope), scope.epoch.value).map { row ->
            TenantOverlayAudit(
                row.long("audit_id"),
                scope,
                TenantOverlayOperation(row.uuid("operation_id")),
                TenantOverlayActor(row.string("actor")),
                TenantOverlayAuditKind.valueOf(row.string("kind")),
                row.string("revision"),
                row.long("version"),
                row.instant("recorded_at"),
            )
        }
    }

    override fun readChanges(
        afterCursor: Long,
        limit: Int,
    ): TenantOverlayChangePage {
        requireTransaction()
        require(afterCursor >= 0) { "a tenant overlay change cursor is non-negative" }
        require(limit in 1..MAX_CHANGE_PAGE) { "a tenant overlay change page is 1..$MAX_CHANGE_PAGE" }
        val rows = dsl.fetch(CHANGES, afterCursor, limit + 1)
        val changes =
            rows.take(limit).map { row ->
                val revision = row.stringOrNull("active_revision")
                TenantOverlayChange(
                    row.long("cursor"),
                    TenantOverlayScope.persisted(row.bytes("tenant_namespace"), TenantEpoch(row.long("tenant_epoch"))),
                    TenantOverlayAuditKind.valueOf(row.string("kind")),
                    revision?.let { CatalogOverlayRef(OverlayLayer.TENANT, it, Digest.parse(row.string("active_digest"))) },
                    row.long("version"),
                    row.instant("recorded_at"),
                )
            }
        return TenantOverlayChangePage(changes, afterCursor, rows.size > limit)
    }

    private fun stage(command: SetTenantOverlayCommand): TenantOverlayOutcome {
        if (revision(command.scope, command.candidate.revision) != null) {
            return invalid("candidate.revision", "already identifies an immutable stored candidate")
        }
        try {
            dsl.execute(
                INSERT_REVISION,
                namespace(command.scope),
                command.scope.epoch.value,
                command.candidate.revision,
                command.candidate.base.revision,
                command.candidate.base.digest.hex,
                now(),
            )
            command.candidate.entries.forEachIndexed { index, entry -> insertEntry(command, index, entry) }
        } catch (failure: DataAccessException) {
            throw PostgresTenantOverlayStoreFailure(failure)
        }
        return changed(command, TenantOverlayAuditKind.STAGED, command.candidate.revision, null)
    }

    private fun review(command: ReviewTenantOverlayCommand): TenantOverlayOutcome {
        val stored = revision(command.scope, command.revision) ?: return TenantOverlayOutcome.Missing(command.revision)
        if (stored.state != TenantOverlayRevisionState.DRAFT) return invalid("revision", "was already reviewed")
        val snapshot = catalogs.snapshot(stored.spec.base) ?: return TenantOverlayOutcome.Missing(stored.spec.base.revision)
        return when (val result = CatalogOverlayCompiler.compile(snapshot, stored.spec)) {
            is CatalogOverlayCompilation.Compiled -> {
                try {
                    check(
                        dsl.execute(
                            REVIEW_REVISION,
                            result.overlay.reference.digest.hex,
                            now(),
                            namespace(command.scope),
                            command.scope.epoch.value,
                            command.revision,
                        ) == 1,
                    ) {
                        "tenant overlay review changed while its epoch lock was held"
                    }
                } catch (failure: DataAccessException) {
                    throw PostgresTenantOverlayStoreFailure(failure)
                }
                changed(command, TenantOverlayAuditKind.REVIEWED, command.revision, null)
            }

            is CatalogOverlayCompilation.Refused -> {
                TenantOverlayOutcome.Invalid(result.problems.map { TenantOverlayProblem(it.path, it.message) })
            }
        }
    }

    private fun activate(
        command: ActivateTenantOverlayCommand,
        kind: TenantOverlayAuditKind,
    ): TenantOverlayOutcome {
        val stored = revision(command.scope, command.revision) ?: return TenantOverlayOutcome.Missing(command.revision)
        val reference = stored.reference ?: return TenantOverlayOutcome.Missing(command.revision)
        val snapshot = catalogs.snapshot(stored.spec.base) ?: return TenantOverlayOutcome.Missing(stored.spec.base.revision)
        val compiled = CatalogOverlayCompiler.compile(snapshot, stored.spec)
        if (compiled !is CatalogOverlayCompilation.Compiled || compiled.overlay.reference != reference) {
            return invalid("revision", "is no longer valid for its exact base snapshot")
        }
        return changed(command, kind, command.revision, reference)
    }

    private fun rollback(command: RollbackTenantOverlayCommand): TenantOverlayOutcome =
        activate(
            ActivateTenantOverlayCommand(command.scope, command.expectedVersion, command.actor, command.operation, command.targetRevision),
            TenantOverlayAuditKind.ROLLED_BACK,
        )

    private fun changed(
        command: TenantOverlayCommand,
        kind: TenantOverlayAuditKind,
        revision: String,
        active: CatalogOverlayRef?,
    ): TenantOverlayOutcome.Changed {
        val prior = head(command.scope)
        val nextVersion = (prior?.version ?: 0) + 1
        val nextActive = active ?: prior?.active
        val now = now()
        try {
            val updated =
                if (prior == null) {
                    dsl.execute(
                        INSERT_HEAD,
                        namespace(command.scope),
                        command.scope.epoch.value,
                        nextVersion,
                        nextActive?.revision,
                        nextActive?.digest?.hex,
                        now,
                    ) == 1
                } else {
                    dsl.execute(
                        UPDATE_HEAD,
                        nextVersion,
                        nextActive?.revision,
                        nextActive?.digest?.hex,
                        now,
                        namespace(command.scope),
                        command.scope.epoch.value,
                        prior.version,
                    ) == 1
                }
            check(updated) { "tenant overlay head changed while its epoch lock was held" }
            dsl.execute(
                INSERT_AUDIT,
                namespace(command.scope),
                command.scope.epoch.value,
                command.operation.value,
                command.actor.value,
                kind.name,
                revision,
                nextVersion,
                now,
            )
            dsl.execute(
                INSERT_CHANGE,
                namespace(command.scope),
                command.scope.epoch.value,
                kind.name,
                nextActive?.revision,
                nextActive?.digest?.hex,
                nextVersion,
                now,
            )
        } catch (failure: DataAccessException) {
            throw PostgresTenantOverlayStoreFailure(failure)
        }
        return TenantOverlayOutcome.Changed(nextVersion, nextActive)
    }

    private fun revision(
        scope: TenantOverlayScope,
        value: String,
    ): StoredRevision? {
        val row = dsl.fetchOne(REVISION, namespace(scope), scope.epoch.value, value) ?: return null
        val entries =
            dsl.fetch(ENTRIES, namespace(scope), scope.epoch.value, value).map { entry ->
                OverlayTranslationSpec(
                    MessageKey.parse(entry.string("message_key")),
                    LocaleTag.parse(entry.string("locale")),
                    entry.string("template"),
                    MessageContractRef(
                        MessageKey.parse(entry.string("message_key")),
                        entry.int("contract_revision"),
                        ContractDigest(Digest.parse(entry.string("contract_digest"))),
                    ),
                    SourceDigest(Digest.parse(entry.string("source_digest"))),
                    ReviewDigest(Digest.parse(entry.string("review_digest"))),
                )
            }
        val spec =
            CatalogOverlaySpec(
                CatalogRef(row.string("base_revision"), Digest.parse(row.string("base_digest"))),
                OverlayLayer.TENANT,
                row.string("revision"),
                entries,
            )
        val digest = row.stringOrNull("overlay_digest")
        return StoredRevision(
            spec,
            TenantOverlayRevisionState.valueOf(row.string("state")),
            digest?.let { CatalogOverlayRef(OverlayLayer.TENANT, spec.revision, Digest.parse(it)) },
        )
    }

    private fun insertEntry(
        command: SetTenantOverlayCommand,
        index: Int,
        entry: OverlayTranslationSpec,
    ) {
        dsl.execute(
            INSERT_ENTRY,
            namespace(command.scope),
            command.scope.epoch.value,
            command.candidate.revision,
            index,
            entry.key.value,
            entry.locale.value,
            entry.text,
            entry.contract.revision,
            entry.contract.digest.value.hex,
            entry.sourceDigest.value.hex,
            entry.reviewDigest.value.hex,
        )
    }

    private fun head(scope: TenantOverlayScope): HeadRow? =
        dsl.fetchOne(HEAD, namespace(scope), scope.epoch.value)?.let { row ->
            val revision = row.stringOrNull("active_revision")
            HeadRow(
                row.long("version"),
                revision,
                revision?.let { CatalogOverlayRef(OverlayLayer.TENANT, it, Digest.parse(row.string("active_digest"))) },
            )
        }

    private fun receipt(
        scope: TenantOverlayScope,
        operation: TenantOverlayOperation,
    ): StoredReceipt? {
        val row = dsl.fetchOne(RECEIPT, namespace(scope), scope.epoch.value, operation.value) ?: return null
        val outcome =
            when (row.string("outcome_kind")) {
                "CHANGED" -> {
                    TenantOverlayOutcome.Changed(row.long("version"), active(row))
                }

                "CONFLICT" -> {
                    TenantOverlayOutcome.Conflict(row.long("version"))
                }

                "MISSING" -> {
                    TenantOverlayOutcome.Missing(row.string("revision"))
                }

                "INVALID" -> {
                    TenantOverlayOutcome.Invalid(
                        dsl.fetch(RECEIPT_PROBLEMS, namespace(scope), scope.epoch.value, operation.value).map { problem ->
                            TenantOverlayProblem(problem.string("path"), problem.string("message"))
                        },
                    )
                }

                else -> {
                    error("unknown tenant overlay receipt outcome")
                }
            }
        return StoredReceipt(row.bytes("fingerprint"), outcome)
    }

    private fun persistReceipt(
        command: TenantOverlayCommand,
        outcome: TenantOverlayOutcome,
    ) {
        val kind =
            when (outcome) {
                is TenantOverlayOutcome.Changed -> "CHANGED"
                is TenantOverlayOutcome.Conflict -> "CONFLICT"
                is TenantOverlayOutcome.Missing -> "MISSING"
                is TenantOverlayOutcome.Invalid -> "INVALID"
            }
        val version =
            when (outcome) {
                is TenantOverlayOutcome.Changed -> outcome.version
                is TenantOverlayOutcome.Conflict -> outcome.currentVersion
                else -> null
            }
        val missing = (outcome as? TenantOverlayOutcome.Missing)?.revision
        val active = (outcome as? TenantOverlayOutcome.Changed)?.active
        try {
            dsl.execute(
                INSERT_RECEIPT,
                namespace(command.scope),
                command.scope.epoch.value,
                command.operation.value,
                fingerprint(command),
                kind,
                version,
                missing,
                active?.revision,
                active?.digest?.hex,
                if (outcome is TenantOverlayOutcome.Invalid) "[]" else null,
                now(),
            )
            (outcome as? TenantOverlayOutcome.Invalid)?.problems?.forEachIndexed { index, problem ->
                dsl.execute(
                    INSERT_RECEIPT_PROBLEM,
                    namespace(command.scope),
                    command.scope.epoch.value,
                    command.operation.value,
                    index,
                    problem.path,
                    problem.message,
                )
            }
        } catch (failure: DataAccessException) {
            throw PostgresTenantOverlayStoreFailure(failure)
        }
    }

    private fun active(row: Record): CatalogOverlayRef? =
        row.stringOrNull("active_revision")?.let { CatalogOverlayRef(OverlayLayer.TENANT, it, Digest.parse(row.string("active_digest"))) }

    private fun lock(scope: TenantOverlayScope) {
        dsl.execute(LOCK_SCOPE, namespace(scope), scope.epoch.value)
    }

    private fun requireTransaction() {
        check(active.get()) { "tenant overlay store calls require inCallerTransaction" }
        TransactionPlacement.inspect(dsl, transactionManager).requireAuthority()
    }

    private fun fingerprint(command: TenantOverlayCommand): ByteArray =
        Digest
            .sha256(
                buildString {
                    append(command::class.qualifiedName).append('\u0000')
                    append(command.actor.value).append('\u0000').append(command.expectedVersion).append('\u0000')
                    when (command) {
                        is SetTenantOverlayCommand -> {
                            append(command.candidate.base.revision).append('\u0000')
                            append(command.candidate.base.digest.hex).append('\u0000')
                            append(command.candidate.revision).append('\u0000')
                            command.candidate.entries.sortedWith(compareBy({ it.key }, { it.locale })).forEach { entry ->
                                append(entry.key.value).append('\u0000')
                                append(entry.locale.value).append('\u0000')
                                append(entry.contract.revision).append('\u0000')
                                append(entry.contract.digest.value.hex).append('\u0000')
                                append(entry.sourceDigest.value.hex).append('\u0000')
                                append(entry.reviewDigest.value.hex).append('\u0000')
                                append(entry.text).append('\u0000')
                            }
                        }

                        is ReviewTenantOverlayCommand -> {
                            append(command.revision)
                        }

                        is ActivateTenantOverlayCommand -> {
                            append(command.revision)
                        }

                        is RollbackTenantOverlayCommand -> {
                            append(command.targetRevision)
                        }
                    }
                }.toByteArray(Charsets.UTF_8),
            ).hex
            .hexToBytes()

    private fun now(): Instant = clock.instant()

    private fun namespace(scope: TenantOverlayScope): ByteArray = scope.namespaceBytes()

    private fun invalid(
        path: String,
        message: String,
    ): TenantOverlayOutcome.Invalid = TenantOverlayOutcome.Invalid(listOf(TenantOverlayProblem(path, message)))

    private data class HeadRow(
        val version: Long,
        val activeRevision: String?,
        val active: CatalogOverlayRef?,
    )

    private data class StoredRevision(
        val spec: CatalogOverlaySpec,
        val state: TenantOverlayRevisionState,
        val reference: CatalogOverlayRef?,
    )

    private data class StoredReceipt(
        val fingerprint: ByteArray,
        val outcome: TenantOverlayOutcome,
    )

    private fun Record.string(name: String): String = checkNotNull(get(name, String::class.java))

    private fun Record.stringOrNull(name: String): String? = get(name, String::class.java)

    private fun Record.long(name: String): Long = checkNotNull(get(name, Long::class.java))

    private fun Record.int(name: String): Int = checkNotNull(get(name, Int::class.java))

    private fun Record.uuid(name: String): java.util.UUID = checkNotNull(get(name, java.util.UUID::class.java))

    private fun Record.bytes(name: String): ByteArray = checkNotNull(get(name, ByteArray::class.java))

    private fun Record.instant(name: String): Instant = checkNotNull(get(name, Instant::class.java))

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MAX_CHANGE_PAGE: Int = 16_384
        const val LOCK_SCOPE: String = "SELECT pg_advisory_xact_lock(hashtextextended(encode(?::bytea, 'hex') || ':' || ?::text, 0))"
        const val HEAD: String = """
            SELECT version, active_revision, active_digest
            FROM rain_tenancy_i18n.tenant_overlay_head
            WHERE tenant_namespace = ? AND tenant_epoch = ?
            """
        const val REVISION: String = """
            SELECT revision, base_revision, base_digest, state, overlay_digest
            FROM rain_tenancy_i18n.tenant_overlay_revision
            WHERE tenant_namespace = ? AND tenant_epoch = ? AND revision = ?
            """
        const val REVISIONS: String = """
            SELECT revision, base_revision, base_digest, state, overlay_digest
            FROM rain_tenancy_i18n.tenant_overlay_revision
            WHERE tenant_namespace = ? AND tenant_epoch = ?
            ORDER BY revision
            """
        const val ENTRIES: String = """
            SELECT message_key, locale, template, contract_revision, contract_digest, source_digest, review_digest
            FROM rain_tenancy_i18n.tenant_overlay_entry
            WHERE tenant_namespace = ? AND tenant_epoch = ? AND revision = ?
            ORDER BY entry_index
            """
        const val INSERT_REVISION: String = """
            INSERT INTO rain_tenancy_i18n.tenant_overlay_revision(
                tenant_namespace, tenant_epoch, revision, base_revision, base_digest, state, created_at
            ) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?::timestamptz)
            """
        const val INSERT_ENTRY: String = """
            INSERT INTO rain_tenancy_i18n.tenant_overlay_entry(
                tenant_namespace, tenant_epoch, revision, entry_index, message_key, locale, template,
                contract_revision, contract_digest, source_digest, review_digest
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
        const val REVIEW_REVISION: String = """
            UPDATE rain_tenancy_i18n.tenant_overlay_revision
            SET state = 'REVIEWED', overlay_digest = ?, reviewed_at = ?::timestamptz
            WHERE tenant_namespace = ? AND tenant_epoch = ? AND revision = ? AND state = 'DRAFT'
            """
        const val INSERT_HEAD: String = """
            INSERT INTO rain_tenancy_i18n.tenant_overlay_head(
                tenant_namespace, tenant_epoch, version, active_revision, active_digest, changed_at
            ) VALUES (?, ?, ?, ?, ?, ?::timestamptz)
            """
        const val UPDATE_HEAD: String = """
            UPDATE rain_tenancy_i18n.tenant_overlay_head
            SET version = ?, active_revision = ?, active_digest = ?, changed_at = ?::timestamptz
            WHERE tenant_namespace = ? AND tenant_epoch = ? AND version = ?
            """
        const val INSERT_AUDIT: String = """
            INSERT INTO rain_tenancy_i18n.tenant_overlay_audit(
                tenant_namespace, tenant_epoch, operation_id, actor, kind, revision, version, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
            """
        const val INSERT_CHANGE: String = """
            INSERT INTO rain_tenancy_i18n.tenant_overlay_change(
                tenant_namespace, tenant_epoch, kind, active_revision, active_digest, version, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz)
            """
        const val AUDITS: String = """
            SELECT audit_id, operation_id, actor, kind, revision, version, recorded_at
            FROM rain_tenancy_i18n.tenant_overlay_audit
            WHERE tenant_namespace = ? AND tenant_epoch = ?
            ORDER BY audit_id
            """
        const val CHANGES: String = """
            SELECT cursor, tenant_namespace, tenant_epoch, kind, active_revision, active_digest, version, recorded_at
            FROM rain_tenancy_i18n.tenant_overlay_change
            WHERE cursor > ?
            ORDER BY cursor
            LIMIT ?
            """
        const val RECEIPT: String = """
            SELECT fingerprint, outcome_kind, version, revision, active_revision, active_digest
            FROM rain_tenancy_i18n.tenant_overlay_receipt
            WHERE tenant_namespace = ? AND tenant_epoch = ? AND operation_id = ?
            """
        const val RECEIPT_PROBLEMS: String = """
            SELECT path, message
            FROM rain_tenancy_i18n.tenant_overlay_receipt_problem
            WHERE tenant_namespace = ? AND tenant_epoch = ? AND operation_id = ?
            ORDER BY ordinal
            """
        const val INSERT_RECEIPT: String = """
            INSERT INTO rain_tenancy_i18n.tenant_overlay_receipt(
                tenant_namespace, tenant_epoch, operation_id, fingerprint, outcome_kind, version, revision,
                active_revision, active_digest, problems, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::timestamptz)
            """
        const val INSERT_RECEIPT_PROBLEM: String = """
            INSERT INTO rain_tenancy_i18n.tenant_overlay_receipt_problem(
                tenant_namespace, tenant_epoch, operation_id, ordinal, path, message
            ) VALUES (?, ?, ?, ?, ?, ?)
            """
    }
}

/** A database failure leaves commit status unknown to the caller; never infer a safe retry from it. */
public class PostgresTenantOverlayStoreFailure(
    cause: DataAccessException,
) : RuntimeException("tenant overlay store operation failed", cause)
