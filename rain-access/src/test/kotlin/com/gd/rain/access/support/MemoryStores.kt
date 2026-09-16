package com.gd.rain.access.support

import com.gd.rain.access.GrantedPermission
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.internal.store.CatalogueStore
import com.gd.rain.access.internal.store.CredentialInsert
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.DeclaredPermission
import com.gd.rain.access.internal.store.GrantStore
import com.gd.rain.access.internal.store.HeldRole
import com.gd.rain.access.internal.store.NewSession
import com.gd.rain.access.internal.store.PermissionRow
import com.gd.rain.access.internal.store.RevokedBatch
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.RoleRow
import com.gd.rain.access.internal.store.SessionClosure
import com.gd.rain.access.internal.store.SessionCursor
import com.gd.rain.access.internal.store.SessionStore
import com.gd.rain.access.internal.store.StoredCredential
import com.gd.rain.access.internal.store.StoredSession
import com.gd.rain.access.internal.store.SubjectCutoff
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Password credentials in memory, with the unique rules of the table. */
open class MemoryCredentialStore : CredentialStore {
    private val lock = ReentrantLock()
    private val rows = LinkedHashMap<UUID, StoredCredential>()

    fun all(): List<StoredCredential> = lock.withLock { rows.values.toList() }

    override fun findByIdentifier(
        type: SubjectType,
        identifier: String,
    ): StoredCredential? = lock.withLock { rows.values.firstOrNull { it.subject.type == type && it.identifier == identifier } }

    override fun findBySubject(subject: SubjectRef): StoredCredential? = lock.withLock { rows.values.firstOrNull { it.subject == subject } }

    override fun lockById(id: UUID): StoredCredential? = lock.withLock { rows[id] }

    override fun lockBySubject(subject: SubjectRef): StoredCredential? = findBySubject(subject)

    override fun insert(
        id: UUID,
        subject: SubjectRef,
        identifier: String,
        hash: String,
        now: Instant,
    ): CredentialInsert =
        lock.withLock {
            when {
                rows.values.any { it.subject == subject } -> CredentialInsert.SUBJECT_ENROLLED
                rows.values.any { it.subject.type == subject.type && it.identifier == identifier } -> CredentialInsert.IDENTIFIER_TAKEN
                else -> CredentialInsert.INSERTED.also { rows[id] = StoredCredential(id, subject, identifier, hash, 0) }
            }
        }

    override fun replaceSecret(
        id: UUID,
        expectedVersion: Long,
        hash: String,
        now: Instant,
    ): Int =
        lock.withLock {
            val row = rows[id]?.takeIf { it.version == expectedVersion } ?: return 0
            rows[id] = row.copy(secretHash = hash, version = row.version + 1)
            1
        }

    override fun replaceIdentifierAndSecret(
        id: UUID,
        expectedVersion: Long,
        identifier: String,
        hash: String,
        now: Instant,
    ): Int =
        lock.withLock {
            val row = rows[id]?.takeIf { it.version == expectedVersion } ?: return 0
            rows[id] = row.copy(identifier = identifier, secretHash = hash, version = row.version + 1)
            1
        }

    override fun withPassword(
        type: SubjectType,
        ids: Collection<UUID>,
    ): Set<UUID> =
        lock.withLock {
            rows.values
                .filter { it.subject.type == type && it.subject.id in ids }
                .map { it.subject.id }
                .toSet()
        }
}

/** Sessions and cutoffs in memory, answering every statement the way the table does. */
open class MemorySessionStore : SessionStore {
    private val lock = ReentrantLock()
    private val rows = LinkedHashMap<UUID, StoredSession>()
    private val cutoffs = LinkedHashMap<SubjectRef, SubjectCutoff>()

    fun all(): List<StoredSession> = lock.withLock { rows.values.toList() }

    override fun insert(session: NewSession) {
        lock.withLock {
            rows[session.id] =
                StoredSession(
                    session.id,
                    session.subject,
                    session.tokenHash,
                    null,
                    1,
                    session.userAgent,
                    session.address,
                    session.createdAt,
                    session.createdAt,
                    null,
                    session.expiresAt,
                    null,
                    null,
                )
        }
    }

    override fun findByTokenHash(hash: String): StoredSession? = lock.withLock { rows.values.firstOrNull { it.tokenHash == hash } }

    override fun findByPreviousTokenHash(hash: String): StoredSession? =
        lock.withLock {
            rows.values.firstOrNull {
                it.previousTokenHash ==
                    hash
            }
        }

    override fun findById(id: UUID): StoredSession? = lock.withLock { rows[id] }

    override fun swap(
        id: UUID,
        expectedGeneration: Long,
        currentHash: String,
        nextHash: String,
        now: Instant,
    ): Int =
        lock.withLock {
            val row =
                rows[id]?.takeIf { it.generation == expectedGeneration && it.tokenHash == currentHash && it.revokedAt == null } ?: return 0
            rows[id] =
                row.copy(
                    tokenHash = nextHash,
                    previousTokenHash = currentHash,
                    generation = expectedGeneration + 1,
                    rotatedAt = now,
                    lastUsedAt = now,
                )
            1
        }

    override fun close(
        id: UUID,
        now: Instant,
        reason: String,
    ): Int =
        lock.withLock {
            val row = rows[id]?.takeIf { it.revokedAt == null } ?: return 0
            rows[id] = row.copy(revokedAt = now, revokedReason = reason)
            1
        }

    override fun revokeOne(
        subject: SubjectRef,
        session: UUID,
        now: Instant,
        reason: String,
    ): SessionClosure? =
        lock.withLock {
            val row = rows[session]?.takeIf { it.subject == subject } ?: return null
            val closing = row.revokedAt == null
            if (closing) rows[session] = row.copy(revokedAt = now, revokedReason = reason)
            SessionClosure(requireNotNull(rows.getValue(session).revokedAt), closing)
        }

    override fun revokeBatch(
        subject: SubjectRef,
        cutoffAt: Instant,
        kept: UUID?,
        after: SessionCursor?,
        now: Instant,
        reason: String,
        batch: Int,
    ): RevokedBatch =
        lock.withLock {
            val locked =
                rows.values
                    .filter { it.subject == subject && it.revokedAt == null && !it.createdAt.isAfter(cutoffAt) }
                    .filter { row ->
                        after == null || row.createdAt > after.createdAt ||
                            (row.createdAt == after.createdAt && row.id > after.id)
                    }.sortedWith(compareBy<StoredSession> { it.createdAt }.thenBy { it.id })
                    .take(batch)
            val closing = locked.filter { it.id != kept }
            closing.forEach { rows[it.id] = it.copy(revokedAt = now, revokedReason = reason) }
            RevokedBatch(closing.size, locked.takeIf { it.size == batch }?.last()?.let { SessionCursor(it.createdAt, it.id) })
        }

    override fun livePage(
        subject: SubjectRef,
        after: SessionCursor?,
        limit: Int,
    ): List<StoredSession> =
        lock.withLock {
            rows.values
                .filter { it.subject == subject && it.revokedAt == null }
                .sortedWith(compareByDescending<StoredSession> { it.createdAt }.thenByDescending { it.id })
                .filter { row ->
                    after == null || row.createdAt < after.createdAt || (row.createdAt == after.createdAt && row.id < after.id)
                }.take(limit)
        }

    override fun revokedPage(
        since: Instant,
        until: Instant,
        after: RevokedSession?,
        limit: Int,
    ): List<RevokedSession> =
        lock.withLock {
            rows.values
                .mapNotNull { row -> row.revokedAt?.let { RevokedSession(row.id, it) } }
                .filter { it.revokedAt.isAfter(since) && !it.revokedAt.isAfter(until) }
                .sortedWith(compareBy<RevokedSession> { it.revokedAt }.thenBy { it.id })
                .filter { row ->
                    after == null || row.revokedAt > after.revokedAt || (row.revokedAt == after.revokedAt && row.id > after.id)
                }.take(limit)
        }

    override fun deleteExpired(
        before: Instant,
        batch: Int,
    ): Int =
        lock.withLock {
            remove(
                rows.values
                    .filter { it.expiresAt.isBefore(before) }
                    .sortedBy { it.expiresAt }
                    .take(batch),
            )
        }

    override fun deleteRevoked(
        before: Instant,
        batch: Int,
    ): Int =
        lock.withLock {
            remove(
                rows.values
                    .filter { it.revokedAt?.isBefore(before) == true }
                    .sortedBy { it.revokedAt }
                    .take(batch),
            )
        }

    override fun upsertCutoff(cutoff: SubjectCutoff) {
        lock.withLock {
            val current = cutoffs[cutoff.subject]
            if (current == null || !current.cutoffAt.isAfter(cutoff.cutoffAt)) cutoffs[cutoff.subject] = cutoff
        }
    }

    override fun cutoffOf(subject: SubjectRef): SubjectCutoff? = lock.withLock { cutoffs[subject] }

    override fun cutoffPage(
        since: Instant,
        until: Instant,
        after: SubjectCutoff?,
        limit: Int,
    ): List<SubjectCutoff> =
        lock.withLock {
            cutoffs.values
                .filter { it.cutoffAt.isAfter(since) && !it.cutoffAt.isAfter(until) }
                .sortedWith(compareBy<SubjectCutoff> { it.cutoffAt }.thenBy { it.subject.type.name }.thenBy { it.subject.id })
                .dropWhile { after != null && it != after && positionOf(it) <= positionOf(after) }
                .filter { it != after }
                .take(limit)
        }

    override fun deleteCutoffs(
        before: Instant,
        batch: Int,
    ): Int =
        lock.withLock {
            val chosen =
                cutoffs.values
                    .filter { it.cutoffAt.isBefore(before) }
                    .sortedBy { it.cutoffAt }
                    .take(batch)
            chosen.forEach { cutoffs.remove(it.subject) }
            chosen.size
        }

    private fun positionOf(cutoff: SubjectCutoff): String = "${cutoff.cutoffAt}|${cutoff.subject.type.name}|${cutoff.subject.id}"

    private fun remove(chosen: List<StoredSession>): Int {
        chosen.forEach { rows.remove(it.id) }
        return chosen.size
    }
}

/** Roles, permissions and grants in memory; also the catalogue's store over the same rows. */
class MemoryGrants :
    GrantStore,
    CatalogueStore {
    private val lock = ReentrantLock()
    private val roles = LinkedHashMap<UUID, RoleRow>()
    private val permissions = LinkedHashMap<UUID, PermissionRow>()
    private val rolePermissions = LinkedHashSet<Pair<UUID, UUID>>()
    private val subjectRoles = LinkedHashMap<Pair<SubjectRef, UUID>, Instant>()
    private val subjectPermissions = LinkedHashMap<Pair<SubjectRef, UUID>, Instant>()
    private val defaults = LinkedHashMap<SubjectType, UUID>()

    fun permissionCodesOf(role: UUID): Set<String> =
        lock.withLock { rolePermissions.filter { it.first == role }.map { permissions.getValue(it.second).code }.toSet() }

    fun holders(role: UUID): Int = lock.withLock { subjectRoles.keys.count { it.second == role } }

    /** The batch size of every removal of a role's holders or permissions, in the order they ran. */
    val removalBatches: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()

    override fun heldCodes(
        subject: SubjectRef,
        codes: Set<String>,
    ): Set<String> =
        lock.withLock {
            val held = subjectRoles.keys.filter { it.first == subject }.map { it.second }
            val everything = held.any { roles[it]?.grantsEveryPermission == true }
            permissions.values
                .filter { it.code in codes }
                .filter { permission ->
                    everything || (subject to permission.id) in subjectPermissions || held.any { (it to permission.id) in rolePermissions }
                }.map { it.code }
                .toSet()
        }

    override fun roleBySlug(slug: String): RoleRow? = lock.withLock { roles.values.firstOrNull { it.slug == slug } }

    override fun roleById(id: UUID): RoleRow? = lock.withLock { roles[id] }

    override fun createRole(
        id: UUID,
        slug: String,
        name: String,
        now: Instant,
    ): Boolean =
        lock.withLock {
            if (roles.values.any { it.slug == slug }) return false
            roles[id] = RoleRow(id, slug, name, false, false, now)
            true
        }

    override fun renameRole(
        id: UUID,
        name: String,
    ): Int =
        lock.withLock {
            val role = roles[id]?.takeIf { !it.isSystem } ?: return 0
            roles[id] = role.copy(name = name)
            1
        }

    override fun rolesPage(
        afterSlug: String?,
        limit: Int,
    ): List<RoleRow> =
        lock.withLock {
            roles.values
                .sortedBy { it.slug }
                .filter { afterSlug == null || it.slug > afterSlug }
                .take(limit)
        }

    override fun permissionByCode(code: String): PermissionRow? = lock.withLock { permissions.values.firstOrNull { it.code == code } }

    override fun permissionsPage(
        afterCode: String?,
        limit: Int,
    ): List<PermissionRow> =
        lock.withLock {
            permissions.values
                .sortedBy { it.code }
                .filter { afterCode == null || it.code > afterCode }
                .take(limit)
        }

    override fun rolePermissionsPage(
        role: UUID,
        afterPermission: UUID?,
        limit: Int,
    ): List<PermissionRow> =
        lock.withLock {
            rolePermissions
                .filter { it.first == role }
                .map { it.second }
                .sorted()
                .filter { afterPermission == null || it > afterPermission }
                .take(limit)
                .map(permissions::getValue)
        }

    override fun attach(
        role: UUID,
        permission: UUID,
        now: Instant,
    ): Int = lock.withLock { if (rolePermissions.add(role to permission)) 1 else 0 }

    override fun detach(
        role: UUID,
        permission: UUID,
    ): Int = lock.withLock { if (rolePermissions.remove(role to permission)) 1 else 0 }

    override fun rolesHeldUpTo(
        subject: SubjectRef,
        bound: Int,
    ): Int = lock.withLock { minOf(bound, subjectRoles.keys.count { it.first == subject }) }

    override fun grantRole(
        subject: SubjectRef,
        role: UUID,
        now: Instant,
    ): Int =
        lock.withLock {
            if (subject to role in subjectRoles) return 0
            subjectRoles[subject to role] = now
            1
        }

    override fun holdsRole(
        subject: SubjectRef,
        role: UUID,
    ): Boolean = lock.withLock { subject to role in subjectRoles }

    override fun revokeRole(
        subject: SubjectRef,
        role: UUID,
    ): Int = lock.withLock { if (subjectRoles.remove(subject to role) != null) 1 else 0 }

    override fun grantPermission(
        subject: SubjectRef,
        permission: UUID,
        now: Instant,
    ): Int =
        lock.withLock {
            if (subject to permission in subjectPermissions) return 0
            subjectPermissions[subject to permission] = now
            1
        }

    override fun revokePermission(
        subject: SubjectRef,
        permission: UUID,
    ): Int = lock.withLock { if (subjectPermissions.remove(subject to permission) != null) 1 else 0 }

    override fun subjectRolesPage(
        subject: SubjectRef,
        afterRole: UUID?,
        limit: Int,
    ): List<HeldRole> =
        lock.withLock {
            subjectRoles.entries
                .filter { it.key.first == subject }
                .sortedBy { it.key.second }
                .filter { afterRole == null || it.key.second > afterRole }
                .take(limit)
                .map { HeldRole(it.key.second, roles.getValue(it.key.second).slug, it.value) }
        }

    override fun subjectPermissionsPage(
        subject: SubjectRef,
        afterPermission: UUID?,
        limit: Int,
    ): List<GrantedPermission> =
        lock.withLock {
            subjectPermissions.entries
                .filter { it.key.first == subject }
                .sortedBy { it.key.second }
                .filter { afterPermission == null || it.key.second > afterPermission }
                .take(limit)
                .map { GrantedPermission(it.key.second, permissions.getValue(it.key.second).code, it.value) }
        }

    override fun holdersPage(
        role: UUID,
        after: SubjectRef?,
        limit: Int,
    ): List<SubjectRef> =
        lock.withLock {
            subjectRoles.keys
                .filter { it.second == role }
                .map { it.first }
                .sortedWith(compareBy<SubjectRef> { it.type.name }.thenBy { it.id })
                .filter { after == null || it.type.name > after.type.name || (it.type == after.type && it.id > after.id) }
                .take(limit)
        }

    override fun revokeHoldersBatch(
        role: UUID,
        batch: Int,
    ): Int =
        lock.withLock {
            removalBatches += batch
            val chosen = subjectRoles.keys.filter { it.second == role }.take(batch)
            chosen.forEach(subjectRoles::remove)
            chosen.size
        }

    override fun detachPermissionsBatch(
        role: UUID,
        batch: Int,
    ): Int =
        lock.withLock {
            removalBatches += batch
            val chosen = rolePermissions.filter { it.first == role }.take(batch)
            rolePermissions.removeAll(chosen.toSet())
            chosen.size
        }

    override fun deleteRole(id: UUID): Int =
        lock.withLock {
            val role = roles[id]?.takeIf { !it.isSystem } ?: return 0
            check(
                subjectRoles.keys.none { it.second == id } && rolePermissions.none { it.first == id },
            ) { "role ${role.slug} is still referred to" }
            roles.remove(id)
            1
        }

    override fun defaultRoleOf(type: SubjectType): RoleRow? = lock.withLock { defaults[type]?.let(roles::get) }

    override fun bindDefaultRole(
        type: SubjectType,
        role: UUID,
        now: Instant,
    ): Boolean = lock.withLock { defaults.put(type, role) != role }

    override fun declarePermissions(
        permissions: List<DeclaredPermission>,
        now: Instant,
    ) {
        lock.withLock {
            permissions.forEach { declared ->
                if (this.permissions.values.none { it.code == declared.code }) {
                    this.permissions[declared.id] = PermissionRow(declared.id, declared.code, declared.name, declared.module, now)
                }
            }
        }
    }

    override fun permissionIds(codes: Collection<String>): Map<String, UUID> =
        lock.withLock { permissions.values.filter { it.code in codes }.associate { it.code to it.id } }

    override fun declareSystemRole(
        id: UUID,
        slug: String,
        name: String,
        grantsEveryPermission: Boolean,
        now: Instant,
    ): UUID =
        lock.withLock {
            val existing = roles.values.firstOrNull { it.slug == slug }
            if (existing != null) {
                roles[existing.id] = existing.copy(isSystem = true, grantsEveryPermission = grantsEveryPermission)
                existing.id
            } else {
                roles[id] = RoleRow(id, slug, name, true, grantsEveryPermission, now)
                id
            }
        }

    override fun attach(
        role: UUID,
        permissions: Collection<UUID>,
        now: Instant,
    ): Int = lock.withLock { permissions.count { rolePermissions.add(role to it) } }
}
