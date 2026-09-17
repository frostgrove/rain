package com.gd.rain.i18n.persistence.postgres

import com.gd.rain.i18n.ArtifactEnvelope
import com.gd.rain.i18n.ArtifactSignatureAlgorithm
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogTrustPolicy
import com.gd.rain.i18n.CatalogTrustRefusal
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.TrustedCatalogLoad
import com.gd.rain.i18n.TrustedCatalogLoader
import com.gd.rain.i18n.persistence.CatalogChange
import com.gd.rain.i18n.persistence.CatalogChangeKind
import com.gd.rain.i18n.persistence.CatalogChangePage
import com.gd.rain.i18n.persistence.CatalogPersistenceLimitReason
import com.gd.rain.i18n.persistence.CatalogPersistenceLimits
import com.gd.rain.i18n.persistence.CatalogPinCommand
import com.gd.rain.i18n.persistence.CatalogPinReleaseCommand
import com.gd.rain.i18n.persistence.CatalogPinResult
import com.gd.rain.i18n.persistence.CatalogPinSweepCommand
import com.gd.rain.i18n.persistence.CatalogReleaseActor
import com.gd.rain.i18n.persistence.CatalogReleaseCommand
import com.gd.rain.i18n.persistence.CatalogReleaseOperation
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.CatalogReleaseStoreSupport
import com.gd.rain.i18n.persistence.CatalogReleaseTransaction
import com.gd.rain.i18n.persistence.CatalogRollbackCommand
import com.gd.rain.i18n.persistence.DurableCatalogCurrent
import com.gd.rain.i18n.persistence.DurableCatalogCurrentLoad
import com.gd.rain.i18n.persistence.DurableCatalogHead
import com.gd.rain.i18n.persistence.DurableCatalogLoad
import com.gd.rain.i18n.persistence.DurableCatalogPin
import com.gd.rain.i18n.persistence.DurableCatalogTransition
import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionPlacement
import org.jooq.DSLContext
import org.springframework.dao.DataAccessException
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL implementation of the low-level durable catalog lifecycle SDK.
 *
 * Each mutator takes the scope advisory lock inside a caller-owned transaction. That makes a
 * first head creation and every existing-head CAS serializable without an ambient process lock;
 * the persisted version remains the opaque ABA-resistant condition exposed to callers.
 */
public class PostgresCatalogReleaseStore(
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    private val loader: TrustedCatalogLoader,
    override val limits: CatalogPersistenceLimits = CatalogPersistenceLimits(),
    private val clock: Clock = Clock.systemUTC(),
) : CatalogReleaseStoreSupport() {
    override val backing: BackingIdentity = TransactionPlacement.inspect(dsl, transactionManager).backing
    private val active: ThreadLocal<CatalogReleaseTransaction?> = ThreadLocal.withInitial { null }
    private val clockLock: Any = Any()
    private var lastNow: Instant = Instant.MIN

    override fun <T> inCallerTransaction(block: (CatalogReleaseTransaction) -> T): T {
        val placement = TransactionPlacement.inspect(dsl, transactionManager)
        check(placement.backing == backing) { "catalog release store backing changed" }
        placement.requireAuthority()
        check(active.get() == null) { "catalog release transaction callbacks do not nest" }
        val transaction = newTransaction()
        active.set(transaction)
        return try {
            block(transaction)
        } finally {
            active.remove()
        }
    }

    override fun current(
        transaction: CatalogReleaseTransaction,
        scope: CatalogReleaseScope,
    ): DurableCatalogCurrentLoad {
        requireActive(transaction)
        val head = head(scope, lock = false) ?: return DurableCatalogCurrentLoad.Missing
        return when (val loaded = load(transaction, scope, head.reference)) {
            is DurableCatalogLoad.Loaded -> DurableCatalogCurrentLoad.Loaded(DurableCatalogCurrent(head.toPublic(scope), loaded.snapshot))
            is DurableCatalogLoad.Missing -> DurableCatalogCurrentLoad.Refused(CatalogTrustRefusal.ARTIFACT_INVALID)
            is DurableCatalogLoad.Refused -> DurableCatalogCurrentLoad.Refused(loaded.reason)
        }
    }

    override fun load(
        transaction: CatalogReleaseTransaction,
        scope: CatalogReleaseScope,
        reference: CatalogRef,
    ): DurableCatalogLoad {
        requireActive(transaction)
        val row =
            dsl.fetchOne(
                LOAD_RELEASE,
                scope.value,
                reference.revision,
                reference.digest.hex,
            ) ?: return DurableCatalogLoad.Missing(reference)
        return try {
            val artifact = checkNotNull(row.get("artifact", ByteArray::class.java))
            val artifactDigest = checkNotNull(row.get("artifact_digest", String::class.java))
            val policy = CatalogTrustPolicy.valueOf(checkNotNull(row.get("trust_policy", String::class.java)))
            val envelope = envelope(row, artifactDigest, policy)
            when (val verified = loader.load(artifact, policy, envelope)) {
                is TrustedCatalogLoad.Loaded -> {
                    if (
                        verified.artifactDigest.hex != artifactDigest ||
                        verified.snapshot.reference != reference ||
                        verified.snapshot.identity.revision != row.string("identity_revision") ||
                        verified.snapshot.identity.profile != row.string("profile") ||
                        verified.snapshot.identity.engine != row.string("engine") ||
                        verified.snapshot.identity.icuClDrTzdbIdentity != row.string("icu_cldr_tzdb_identity")
                    ) {
                        DurableCatalogLoad.Refused(CatalogTrustRefusal.ARTIFACT_INVALID)
                    } else {
                        DurableCatalogLoad.Loaded(verified.snapshot)
                    }
                }

                is TrustedCatalogLoad.Refused -> {
                    DurableCatalogLoad.Refused(verified.reason)
                }
            }
        } catch (_: IllegalArgumentException) {
            DurableCatalogLoad.Refused(CatalogTrustRefusal.ARTIFACT_INVALID)
        }
    }

    override fun publishAndActivate(
        transaction: CatalogReleaseTransaction,
        command: CatalogReleaseCommand,
    ): DurableCatalogTransition {
        requireActive(transaction)
        val verified = loader.load(command.artifact.artifactBytes(), command.artifact.trustPolicy, command.artifact.envelope)
        if (verified is TrustedCatalogLoad.Refused) return DurableCatalogTransition.Refused(verified.reason)
        verified as TrustedCatalogLoad.Loaded
        lockScope(command.scope)
        val current = head(command.scope, lock = true)
        if (!matches(command.expected, current)) return DurableCatalogTransition.Conflict(current?.toPublic(command.scope))
        if (current?.reference == verified.snapshot.reference) return DurableCatalogTransition.Updated(current.toPublic(command.scope))
        if (revisionConflicts(command.scope, verified.snapshot.reference)) {
            return DurableCatalogTransition.Conflict(current?.toPublic(command.scope))
        }

        return try {
            val releaseInserted = ensureArtifactAndRelease(command, verified)
            activateLocked(
                scope = command.scope,
                current = current,
                target = verified.snapshot.reference,
                actor = command.actor,
                operation = command.operation,
                publishAudit = releaseInserted,
                activationKind = "ACTIVATED",
            )
        } catch (failure: DataAccessException) {
            throw PostgresCatalogReleaseStoreFailure(failure)
        }
    }

    override fun rollback(
        transaction: CatalogReleaseTransaction,
        command: CatalogRollbackCommand,
    ): DurableCatalogTransition {
        requireActive(transaction)
        lockScope(command.scope)
        val current = head(command.scope, lock = true)
        if (!matches(command.expected, current)) return DurableCatalogTransition.Conflict(current?.toPublic(command.scope))
        checkNotNull(current) { "a non-null expected head cannot match an absent durable head" }
        if (current.reference == command.target) return DurableCatalogTransition.Updated(current.toPublic(command.scope))
        if (!isRetained(command.scope, command.target)) return DurableCatalogTransition.Missing(command.target)
        when (val loaded = load(transaction, command.scope, command.target)) {
            is DurableCatalogLoad.Loaded -> Unit
            is DurableCatalogLoad.Missing -> return DurableCatalogTransition.Missing(command.target)
            is DurableCatalogLoad.Refused -> return DurableCatalogTransition.Refused(loaded.reason)
        }
        return try {
            activateLocked(
                scope = command.scope,
                current = current,
                target = command.target,
                actor = command.actor,
                operation = command.operation,
                publishAudit = false,
                activationKind = "ROLLED_BACK",
            )
        } catch (failure: DataAccessException) {
            throw PostgresCatalogReleaseStoreFailure(failure)
        }
    }

    override fun pin(
        transaction: CatalogReleaseTransaction,
        command: CatalogPinCommand,
    ): CatalogPinResult {
        requireActive(transaction)
        if (command.lifetime <= Duration.ZERO || command.lifetime > limits.maxPinLifetime) {
            return CatalogPinResult.Limit(CatalogPersistenceLimitReason.PIN_LIFETIME)
        }
        lockScope(command.scope)
        val now = now()
        expirePins(command.scope, now)
        if (!isCurrentOrRetained(command.scope, command.reference)) return CatalogPinResult.Missing(command.reference)
        val count = checkNotNull(dsl.fetchOne(PIN_COUNT, command.scope.value)?.get(0, Int::class.java))
        if (count >= limits.maxPins) return CatalogPinResult.Limit(CatalogPersistenceLimitReason.PIN_COUNT)
        val pin = DurableCatalogPin(UUID.randomUUID(), command.scope, command.reference, command.owner, now.plus(command.lifetime))
        try {
            dsl.execute(
                INSERT_PIN,
                pin.id,
                pin.scope.value,
                pin.reference.revision,
                pin.reference.digest.hex,
                pin.owner,
                pin.expiresAt.databaseValue(),
                now.databaseValue(),
            )
            audit(command.scope, command.operation, "PINNED", command.actor, pin.reference, null, now)
            return CatalogPinResult.Pinned(pin)
        } catch (failure: DataAccessException) {
            throw PostgresCatalogReleaseStoreFailure(failure)
        }
    }

    override fun release(
        transaction: CatalogReleaseTransaction,
        command: CatalogPinReleaseCommand,
    ): Boolean {
        requireActive(transaction)
        val pin = command.pin
        lockScope(pin.scope)
        val now = now()
        expirePins(pin.scope, now)
        return try {
            val deleted =
                dsl.execute(
                    DELETE_PIN,
                    pin.id,
                    pin.scope.value,
                    pin.reference.revision,
                    pin.reference.digest.hex,
                    pin.owner,
                ) == 1
            if (deleted) audit(pin.scope, command.operation, "PIN_RELEASED", command.actor, pin.reference, null, now)
            deleted
        } catch (failure: DataAccessException) {
            throw PostgresCatalogReleaseStoreFailure(failure)
        }
    }

    override fun sweepExpiredPins(
        transaction: CatalogReleaseTransaction,
        command: CatalogPinSweepCommand,
    ): Int {
        requireActive(transaction)
        lockScope(command.scope)
        val now = now()
        val cutoff = minOf(command.expiredAtOrBefore, now)
        val expired =
            dsl.fetch(EXPIRED_PINS, command.scope.value, cutoff.databaseValue(), command.limit).map { row ->
                DurableCatalogPin(
                    checkNotNull(row.get("pin_id", UUID::class.java)),
                    command.scope,
                    CatalogRef(row.string("revision"), Digest.parse(row.string("snapshot_digest"))),
                    row.string("owner"),
                    checkNotNull(row.get("expires_at", java.time.OffsetDateTime::class.java)).toInstant(),
                )
            }
        expired.forEach { pin ->
            if (dsl.execute(DELETE_PIN, pin.id, pin.scope.value, pin.reference.revision, pin.reference.digest.hex, pin.owner) == 1) {
                audit(command.scope, command.operation, "PIN_EXPIRED", command.actor, pin.reference, null, now)
            }
        }
        return expired.size
    }

    override fun prune(
        transaction: CatalogReleaseTransaction,
        scope: CatalogReleaseScope,
        actor: CatalogReleaseActor,
        operation: CatalogReleaseOperation,
    ): List<CatalogRef> {
        requireActive(transaction)
        lockScope(scope)
        val now = now()
        expirePins(scope, now)
        val removable = removableRetained(scope, now)
        if (removable.isEmpty()) return emptyList()
        try {
            removable.forEach { reference -> removeRetained(scope, reference, now) }
            audit(scope, operation, "PRUNED", actor, null, null, now)
            return removable
        } catch (failure: DataAccessException) {
            throw PostgresCatalogReleaseStoreFailure(failure)
        }
    }

    override fun readChanges(
        transaction: CatalogReleaseTransaction,
        afterCursor: Long,
        limit: Int,
    ): CatalogChangePage {
        requireActive(transaction)
        require(afterCursor >= 0) { "a catalog change cursor is not negative" }
        require(limit in 1..limits.maxChangePageSize) { "a catalog change page exceeds the configured bound" }
        return try {
            val rows = dsl.fetch(READ_CHANGES, afterCursor, limit + 1)
            val changes =
                rows.take(limit).map { row ->
                    CatalogChange(
                        cursor = checkNotNull(row.get("cursor", Long::class.java)),
                        scope = CatalogReleaseScope(row.string("scope")),
                        kind = CatalogChangeKind.valueOf(row.string("kind")),
                        reference = CatalogRef(row.string("revision"), Digest.parse(row.string("snapshot_digest"))),
                        headVersion = (row.get("head_version") as? Number)?.toLong(),
                        recordedAt = checkNotNull(row.get("recorded_at", Instant::class.java)),
                    )
                }
            CatalogChangePage(changes, afterCursor, rows.size > limit)
        } catch (failure: DataAccessException) {
            throw PostgresCatalogReleaseStoreFailure(failure)
        }
    }

    private fun activateLocked(
        scope: CatalogReleaseScope,
        current: HeadRow?,
        target: CatalogRef,
        actor: CatalogReleaseActor,
        operation: CatalogReleaseOperation,
        publishAudit: Boolean,
        activationKind: String,
    ): DurableCatalogTransition {
        val now = now()
        val discarded =
            makeRetentionRoom(scope, current, target, now) ?: return DurableCatalogTransition.Limit(
                CatalogPersistenceLimitReason.RETENTION_HELD_BY_PINS,
            )
        discarded.forEach { reference -> removeRetained(scope, reference, now) }
        if (current != null) {
            dsl.execute(
                INSERT_RETAINED,
                scope.value,
                current.reference.revision,
                current.reference.digest.hex,
                current.version,
                now.databaseValue(),
            )
        }
        dsl.execute(DELETE_RETAINED, scope.value, target.revision, target.digest.hex)
        val updated =
            if (current == null) {
                dsl.execute(INSERT_HEAD, scope.value, target.revision, target.digest.hex, now.databaseValue()) == 1
            } else {
                dsl.execute(
                    UPDATE_HEAD,
                    target.revision,
                    target.digest.hex,
                    now.databaseValue(),
                    scope.value,
                    current.reference.revision,
                    current.reference.digest.hex,
                    current.version,
                ) == 1
            }
        check(updated) { "catalog head changed while its scope advisory lock was held" }
        val next = HeadRow(target, if (current == null) 1 else current.version + 1)
        if (publishAudit) audit(scope, operation, "PUBLISHED", actor, target, null, now)
        audit(scope, operation, activationKind, actor, target, next.version, now)
        change(scope, CatalogChangeKind.HEAD_CHANGED, target, next.version, now)
        return DurableCatalogTransition.Updated(next.toPublic(scope))
    }

    /** Returns evictable releases or null when every candidate that must leave is pinned. */
    private fun makeRetentionRoom(
        scope: CatalogReleaseScope,
        current: HeadRow?,
        target: CatalogRef,
        now: Instant,
    ): List<CatalogRef>? {
        if (current == null) return emptyList()
        val retained = retained(scope).filterNot { it.reference == target }
        val overage = retained.size + 1 - limits.maxRetained
        if (overage <= 0) return emptyList()
        val evictable = retained.filter { retained -> !hasLivePin(scope, retained.reference, now) }
        if (evictable.size < overage) return null
        return evictable.take(overage).map(RetainedRow::reference)
    }

    private fun ensureArtifactAndRelease(
        command: CatalogReleaseCommand,
        verified: TrustedCatalogLoad.Loaded,
    ): Boolean {
        val artifact = command.artifact.artifactBytes()
        val envelope = command.artifact.envelope
        val insertedArtifact =
            dsl.execute(
                INSERT_ARTIFACT,
                verified.artifactDigest.hex,
                artifact,
                verified.snapshot.identity.revision,
                verified.snapshot.identity.profile,
                verified.snapshot.identity.engine,
                verified.snapshot.identity.icuClDrTzdbIdentity,
                command.artifact.trustPolicy.name,
                envelope?.origin,
                envelope?.keyId,
                envelope?.algorithm?.name,
                envelope?.signatureBytes(),
                verified.verifiedKeyId,
                now().databaseValue(),
            )
        if (insertedArtifact == 0) verifyStoredArtifact(verified, command)
        val insertedRelease =
            dsl.execute(
                INSERT_RELEASE,
                command.scope.value,
                verified.snapshot.reference.revision,
                verified.snapshot.reference.digest.hex,
                verified.artifactDigest.hex,
                command.actor.value,
                now().databaseValue(),
            ) == 1
        if (!insertedRelease) {
            val storedDigest =
                dsl
                    .fetchOne(
                        RELEASE_ARTIFACT,
                        command.scope.value,
                        verified.snapshot.reference.revision,
                        verified.snapshot.reference.digest.hex,
                    )?.string("artifact_digest")
            check(storedDigest == verified.artifactDigest.hex) { "a catalog reference points at another immutable artifact" }
        }
        return insertedRelease
    }

    private fun verifyStoredArtifact(
        verified: TrustedCatalogLoad.Loaded,
        command: CatalogReleaseCommand,
    ) {
        val row = dsl.fetchOne(ARTIFACT_IDENTITY, verified.artifactDigest.hex) ?: error("stored catalog artifact disappeared")
        check(row.string("revision") == verified.snapshot.identity.revision) { "stored artifact revision differs" }
        check(row.string("profile") == verified.snapshot.identity.profile) { "stored artifact profile differs" }
        check(row.string("engine") == verified.snapshot.identity.engine) { "stored artifact engine differs" }
        check(row.string("icu_cldr_tzdb_identity") == verified.snapshot.identity.icuClDrTzdbIdentity) {
            "stored artifact data identity differs"
        }
        check(row.string("trust_policy") == command.artifact.trustPolicy.name) { "stored artifact trust policy differs" }
    }

    private fun envelope(
        row: org.jooq.Record,
        artifactDigest: String,
        policy: CatalogTrustPolicy,
    ): ArtifactEnvelope? {
        if (policy == CatalogTrustPolicy.LOCAL_BUILD) return null
        return ArtifactEnvelope(
            origin = row.string("envelope_origin"),
            artifactDigest = Digest.parse(artifactDigest),
            keyId = row.string("envelope_key_id"),
            algorithm = ArtifactSignatureAlgorithm.valueOf(row.string("envelope_algorithm")),
            signature = checkNotNull(row.get("envelope_signature", ByteArray::class.java)),
        )
    }

    private fun matches(
        expected: DurableCatalogHead?,
        actual: HeadRow?,
    ): Boolean =
        when {
            expected == null -> actual == null
            actual == null -> false
            else -> expected.reference == actual.reference && expected.version == actual.version
        }

    private fun head(
        scope: CatalogReleaseScope,
        lock: Boolean,
    ): HeadRow? {
        val row = dsl.fetchOne(if (lock) HEAD_FOR_UPDATE else HEAD, scope.value) ?: return null
        return HeadRow(
            CatalogRef(row.string("revision"), Digest.parse(row.string("snapshot_digest"))),
            checkNotNull(row.get("head_version", Long::class.java)),
        )
    }

    private fun isRetained(
        scope: CatalogReleaseScope,
        reference: CatalogRef,
    ): Boolean = dsl.fetchOne(RETAINED_EXISTS, scope.value, reference.revision, reference.digest.hex) != null

    private fun revisionConflicts(
        scope: CatalogReleaseScope,
        reference: CatalogRef,
    ): Boolean = dsl.fetchOne(REVISION_CONFLICT, scope.value, reference.revision, reference.digest.hex) != null

    private fun isCurrentOrRetained(
        scope: CatalogReleaseScope,
        reference: CatalogRef,
    ): Boolean =
        dsl.fetchOne(
            CURRENT_OR_RETAINED,
            scope.value,
            reference.revision,
            reference.digest.hex,
            scope.value,
            reference.revision,
            reference.digest.hex,
        ) != null

    private fun retained(scope: CatalogReleaseScope): List<RetainedRow> =
        dsl.fetch(RETAINED, scope.value).map { row ->
            RetainedRow(
                CatalogRef(row.string("revision"), Digest.parse(row.string("snapshot_digest"))),
                checkNotNull(row.get("retained_version", Long::class.java)),
            )
        }

    private fun removableRetained(
        scope: CatalogReleaseScope,
        now: Instant,
    ): List<CatalogRef> =
        dsl.fetch(REMOVABLE_RETAINED, scope.value, now.databaseValue()).map { row ->
            CatalogRef(row.string("revision"), Digest.parse(row.string("snapshot_digest")))
        }

    private fun hasLivePin(
        scope: CatalogReleaseScope,
        reference: CatalogRef,
        now: Instant,
    ): Boolean = dsl.fetchOne(HAS_LIVE_PIN, scope.value, reference.revision, reference.digest.hex, now.databaseValue()) != null

    private fun expirePins(
        scope: CatalogReleaseScope,
        now: Instant,
    ) {
        dsl.execute(EXPIRE_PINS, scope.value, now.databaseValue())
    }

    private fun removeRetained(
        scope: CatalogReleaseScope,
        reference: CatalogRef,
        now: Instant,
    ) {
        if (dsl.execute(DELETE_RETAINED, scope.value, reference.revision, reference.digest.hex) == 1) {
            change(scope, CatalogChangeKind.RELEASE_PRUNED, reference, null, now)
        }
    }

    private fun audit(
        scope: CatalogReleaseScope,
        operation: CatalogReleaseOperation,
        kind: String,
        actor: CatalogReleaseActor,
        reference: CatalogRef?,
        headVersion: Long?,
        now: Instant,
    ) {
        dsl.execute(
            INSERT_AUDIT,
            scope.value,
            operation.value,
            kind,
            actor.value,
            reference?.revision,
            reference?.digest?.hex,
            headVersion,
            now.databaseValue(),
        )
    }

    private fun change(
        scope: CatalogReleaseScope,
        kind: CatalogChangeKind,
        reference: CatalogRef,
        headVersion: Long?,
        now: Instant,
    ) {
        dsl.execute(
            INSERT_CHANGE,
            scope.value,
            kind.name,
            reference.revision,
            reference.digest.hex,
            headVersion,
            now.databaseValue(),
        )
    }

    private fun lockScope(scope: CatalogReleaseScope) {
        dsl.execute(LOCK_SCOPE, scope.value)
    }

    private fun requireActive(transaction: CatalogReleaseTransaction) {
        requireTransaction(transaction)
        check(isSameTransaction(transaction, checkNotNull(active.get()))) { "catalog release transaction is stale" }
        TransactionPlacement.inspect(dsl, transactionManager).requireAuthority()
    }

    private fun now(): Instant =
        synchronized(clockLock) {
            val observed = clock.instant()
            if (observed > lastNow) lastNow = observed
            lastNow
        }

    private fun Instant.databaseValue(): java.time.OffsetDateTime = atOffset(ZoneOffset.UTC)

    private data class HeadRow(
        val reference: CatalogRef,
        val version: Long,
    ) {
        fun toPublic(scope: CatalogReleaseScope): DurableCatalogHead = DurableCatalogHead(scope, reference, version)
    }

    private data class RetainedRow(
        val reference: CatalogRef,
        val version: Long,
    )

    private fun org.jooq.Record.string(field: String): String = checkNotNull(get(field, String::class.java))

    private companion object {
        const val LOCK_SCOPE: String = "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))"

        const val HEAD: String = """
            SELECT revision, snapshot_digest, head_version
            FROM rain_i18n.i18n_head
            WHERE scope = ?
            """
        const val HEAD_FOR_UPDATE: String = "$HEAD FOR UPDATE"
        const val LOAD_RELEASE: String = """
            SELECT artifact.artifact,
                   artifact.artifact_digest,
                   artifact.revision AS identity_revision,
                   artifact.profile,
                   artifact.engine,
                   artifact.icu_cldr_tzdb_identity,
                   artifact.trust_policy,
                   artifact.envelope_origin,
                   artifact.envelope_key_id,
                   artifact.envelope_algorithm,
                   artifact.envelope_signature
            FROM rain_i18n.i18n_release release
            JOIN rain_i18n.i18n_artifact artifact ON artifact.artifact_digest = release.artifact_digest
            WHERE release.scope = ? AND release.revision = ? AND release.snapshot_digest = ?
            """
        const val INSERT_ARTIFACT: String = """
            INSERT INTO rain_i18n.i18n_artifact(
                artifact_digest, artifact, revision, profile, engine, icu_cldr_tzdb_identity,
                trust_policy, envelope_origin, envelope_key_id, envelope_algorithm,
                envelope_signature, verified_key_id, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
            ON CONFLICT (artifact_digest) DO NOTHING
            """
        const val ARTIFACT_IDENTITY: String = """
            SELECT revision, profile, engine, icu_cldr_tzdb_identity, trust_policy
            FROM rain_i18n.i18n_artifact
            WHERE artifact_digest = ?
            """
        const val INSERT_RELEASE: String = """
            INSERT INTO rain_i18n.i18n_release(scope, revision, snapshot_digest, artifact_digest, actor, published_at)
            VALUES (?, ?, ?, ?, ?, ?::timestamptz)
            ON CONFLICT (scope, revision, snapshot_digest) DO NOTHING
            """
        const val RELEASE_ARTIFACT: String = """
            SELECT artifact_digest
            FROM rain_i18n.i18n_release
            WHERE scope = ? AND revision = ? AND snapshot_digest = ?
            """
        const val INSERT_HEAD: String = """
            INSERT INTO rain_i18n.i18n_head(scope, revision, snapshot_digest, head_version, changed_at)
            VALUES (?, ?, ?, 1, ?::timestamptz)
            """
        const val UPDATE_HEAD: String = """
            UPDATE rain_i18n.i18n_head
            SET revision = ?, snapshot_digest = ?, head_version = head_version + 1, changed_at = ?::timestamptz
            WHERE scope = ? AND revision = ? AND snapshot_digest = ? AND head_version = ?
            """
        const val INSERT_RETAINED: String = """
            INSERT INTO rain_i18n.i18n_retained_release(
                scope, revision, snapshot_digest, retained_version, reason, retained_at
            ) VALUES (?, ?, ?, ?, 'HEAD_REPLACED', ?::timestamptz)
            ON CONFLICT (scope, revision, snapshot_digest) DO NOTHING
            """
        const val DELETE_RETAINED: String = """
            DELETE FROM rain_i18n.i18n_retained_release
            WHERE scope = ? AND revision = ? AND snapshot_digest = ?
            """
        const val RETAINED_EXISTS: String = """
            SELECT 1
            FROM rain_i18n.i18n_retained_release
            WHERE scope = ? AND revision = ? AND snapshot_digest = ?
            """
        const val REVISION_CONFLICT: String = """
            SELECT 1
            FROM rain_i18n.i18n_release
            WHERE scope = ? AND revision = ? AND snapshot_digest <> ?
            LIMIT 1
            """
        const val CURRENT_OR_RETAINED: String = """
            SELECT 1
            FROM rain_i18n.i18n_head
            WHERE scope = ? AND revision = ? AND snapshot_digest = ?
            UNION ALL
            SELECT 1
            FROM rain_i18n.i18n_retained_release
            WHERE scope = ? AND revision = ? AND snapshot_digest = ?
            LIMIT 1
            """
        const val RETAINED: String = """
            SELECT revision, snapshot_digest, retained_version
            FROM rain_i18n.i18n_retained_release
            WHERE scope = ?
            ORDER BY retained_version, revision, snapshot_digest
            """
        const val REMOVABLE_RETAINED: String = """
            SELECT retained.revision, retained.snapshot_digest
            FROM rain_i18n.i18n_retained_release retained
            WHERE retained.scope = ?
              AND NOT EXISTS (
                  SELECT 1 FROM rain_i18n.i18n_pin pin
                  WHERE pin.scope = retained.scope
                    AND pin.revision = retained.revision
                    AND pin.snapshot_digest = retained.snapshot_digest
                    AND pin.expires_at > ?::timestamptz
              )
            ORDER BY retained.retained_version, retained.revision, retained.snapshot_digest
            """
        const val HAS_LIVE_PIN: String = """
            SELECT 1
            FROM rain_i18n.i18n_pin
            WHERE scope = ? AND revision = ? AND snapshot_digest = ? AND expires_at > ?::timestamptz
            LIMIT 1
            """
        const val PIN_COUNT: String = "SELECT count(*)::integer FROM rain_i18n.i18n_pin WHERE scope = ?"
        const val EXPIRED_PINS: String = """
            SELECT pin_id, revision, snapshot_digest, owner, expires_at
            FROM rain_i18n.i18n_pin
            WHERE scope = ? AND expires_at <= ?::timestamptz
            ORDER BY expires_at, pin_id
            LIMIT ?
            """
        const val EXPIRE_PINS: String = "DELETE FROM rain_i18n.i18n_pin WHERE scope = ? AND expires_at <= ?::timestamptz"
        const val INSERT_PIN: String = """
            INSERT INTO rain_i18n.i18n_pin(pin_id, scope, revision, snapshot_digest, owner, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
            """
        const val DELETE_PIN: String = """
            DELETE FROM rain_i18n.i18n_pin
            WHERE pin_id = ? AND scope = ? AND revision = ? AND snapshot_digest = ? AND owner = ?
            """
        const val INSERT_AUDIT: String = """
            INSERT INTO rain_i18n.i18n_audit(
                scope, operation, kind, actor, revision, snapshot_digest, head_version, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
            """
        const val INSERT_CHANGE: String = """
            INSERT INTO rain_i18n.i18n_change(scope, kind, revision, snapshot_digest, head_version, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?::timestamptz)
            """
        const val READ_CHANGES: String = """
            SELECT cursor, scope, kind, revision, snapshot_digest, head_version, recorded_at
            FROM rain_i18n.i18n_change
            WHERE cursor > ?
            ORDER BY cursor
            LIMIT ?
            """
    }
}

/** Backend failure after a database call; callers must not infer that a durable mutation did not commit. */
public class PostgresCatalogReleaseStoreFailure(
    cause: DataAccessException,
) : RuntimeException("catalog release store operation failed", cause)
