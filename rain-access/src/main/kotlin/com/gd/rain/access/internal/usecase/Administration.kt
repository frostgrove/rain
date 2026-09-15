package com.gd.rain.access.internal.usecase

import com.gd.rain.access.GrantedPermission
import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.store.GrantStore
import com.gd.rain.access.internal.store.HeldRole
import com.gd.rain.access.internal.store.PermissionRow
import com.gd.rain.access.internal.store.RoleRow
import com.gd.rain.audit.AuditDetail
import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditOutcome
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.keyOf
import com.gd.rain.persistence.lock.AdvisoryLocks
import java.time.Clock
import java.util.UUID

/** A keyset page: [next] is where the following page starts, `null` after the last one. */
public data class Page<T, C>(
    public val items: List<T>,
    public val next: C?,
)

internal fun <T, C> pageOf(
    rows: List<T>,
    limit: Int,
    cursor: (T) -> C,
): Page<T, C> {
    val items = rows.take(limit)
    return Page(items, if (rows.size > limit) cursor(items.last()) else null)
}

/**
 * What a subject holds, and changing it. Granting requires the subject to be active and within `max-roles-per-subject`;
 * revoking does not. A change that changes nothing — a grant already held, a revoke of nothing — succeeds and records
 * nothing.
 */
public class GrantsAdministration(
    private val grants: GrantStore,
    private val locks: AdvisoryLocks,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val maxRolesPerSubject: Int,
    private val clock: Clock,
) {
    public fun rolesOf(
        subject: SubjectRef,
        after: UUID?,
        limit: Int,
    ): Page<HeldRole, UUID> = pageOf(grants.subjectRolesPage(subject, after, limit + 1), limit) { it.roleId }

    public fun directPermissionsOf(
        subject: SubjectRef,
        after: UUID?,
        limit: Int,
    ): Page<GrantedPermission, UUID> = pageOf(grants.subjectPermissionsPage(subject, after, limit + 1), limit) { it.permissionId }

    public fun grantRole(
        served: ServedSubject,
        subject: SubjectRef,
        slug: String,
    ) {
        transactions.inTransaction {
            locks.take(Exclusively(keyOf(LOCK_NAMESPACE, subject.type.name, subject.id.toString())))
            val role = grants.roleBySlug(slug) ?: throw AccessFaults.unknownRole("role")
            if (!served.directory.isActive(subject.id)) throw AccessFaults.unusableSubject("subjectId")
            if (grants.rolesHeldUpTo(subject, maxRolesPerSubject) >= maxRolesPerSubject && !grants.holdsRole(subject, role.id)) {
                throw AccessFaults.tooManyRoles(maxRolesPerSubject)
            }
            if (grants.grantRole(subject, role.id, clock.instant()) == 1) changed(subject, "granted", "role", slug)
        }
    }

    public fun revokeRole(
        subject: SubjectRef,
        slug: String,
    ) {
        transactions.inTransaction {
            val role = grants.roleBySlug(slug) ?: throw AccessFaults.unknownRole("slug")
            if (grants.revokeRole(subject, role.id) == 1) changed(subject, "revoked", "role", slug)
        }
    }

    public fun grantPermission(
        served: ServedSubject,
        subject: SubjectRef,
        code: String,
    ) {
        transactions.inTransaction {
            val permission = grants.permissionByCode(code) ?: throw AccessFaults.unknownPermission("permission")
            if (!served.directory.isActive(subject.id)) throw AccessFaults.unusableSubject("subjectId")
            if (grants.grantPermission(subject, permission.id, clock.instant()) == 1) changed(subject, "granted", "permission", code)
        }
    }

    public fun revokePermission(
        subject: SubjectRef,
        code: String,
    ) {
        transactions.inTransaction {
            val permission = grants.permissionByCode(code) ?: throw AccessFaults.unknownPermission("code")
            if (grants.revokePermission(subject, permission.id) == 1) changed(subject, "revoked", "permission", code)
        }
    }

    private fun changed(
        subject: SubjectRef,
        change: String,
        kind: String,
        grant: String,
    ) {
        audit.record(
            AuditEvent(
                AccessAuditTypes.GRANT_CHANGED,
                AuditOutcome.OK,
                subject.resourceId,
                AuditDetail.of("change" to change, "grant_kind" to kind, "grant" to grant),
            ),
        )
    }

    private companion object {
        const val LOCK_NAMESPACE = "rain-access.subject-grants"
    }
}

/**
 * Application roles through the API: created with an explicit slug, renamed, deleted — each role of a bulk delete
 * checked before any is deleted and each deletion recorded on its own — and their permissions attached and detached. A
 * system role refuses rename, delete and detach with `403 system_role`. Deleting a role removes its holders and its
 * permissions in bounded batches first, each in a transaction of its own.
 */
public class RoleAdministration(
    private val grants: GrantStore,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val ids: IdGenerator,
    private val batch: Int,
    private val clock: Clock,
) {
    public fun page(
        after: String?,
        limit: Int,
    ): Page<RoleRow, String> = pageOf(grants.rolesPage(after, limit + 1), limit) { it.slug }

    public fun get(id: UUID): RoleRow = grants.roleById(id) ?: throw AccessFaults.notFound("role")

    public fun create(
        slug: String,
        name: String,
    ): RoleRow {
        if (!SystemRoleDeclaration.isWellFormedSlug(slug)) {
            throw AccessFaults.invalid("slug", RainErrorCodes.INVALID_FORMAT, "a slug matches ${SystemRoleDeclaration.SLUG_PATTERN}")
        }
        checkName(name)
        return transactions.inTransaction {
            val id = ids.next()
            if (!grants.createRole(id, slug, name, clock.instant())) throw AccessFaults.invalid("slug", RainErrorCodes.UNIQUE)
            roleChanged(id, "created", slug)
            grants.roleById(id) ?: error("the role $slug was created and cannot be read back")
        }
    }

    public fun rename(
        id: UUID,
        name: String,
    ): RoleRow {
        checkName(name)
        return transactions.inTransaction {
            val role = get(id)
            if (role.isSystem) throw AccessFaults.systemRole(role.slug)
            grants.renameRole(id, name)
            roleChanged(id, "renamed", role.slug)
            get(id)
        }
    }

    public fun delete(id: UUID) {
        val role = get(id)
        if (role.isSystem) throw AccessFaults.systemRole(role.slug)
        drain { grants.revokeHoldersBatch(id, batch) }
        drain { grants.detachPermissionsBatch(id, batch) }
        transactions.inTransaction {
            if (grants.deleteRole(id) == 1) roleChanged(id, "deleted", role.slug)
        }
    }

    public fun deleteAll(ids: List<UUID>) {
        val roles = ids.distinct().map(::get)
        roles.firstOrNull { it.isSystem }?.let { throw AccessFaults.systemRole(it.slug) }
        roles.forEach { delete(it.id) }
    }

    public fun permissionsOf(
        role: UUID,
        after: UUID?,
        limit: Int,
    ): Page<PermissionRow, UUID> {
        get(role)
        return pageOf(grants.rolePermissionsPage(role, after, limit + 1), limit) { it.id }
    }

    public fun permissionsPage(
        after: String?,
        limit: Int,
    ): Page<PermissionRow, String> = pageOf(grants.permissionsPage(after, limit + 1), limit) { it.code }

    public fun attach(
        roleId: UUID,
        code: String,
    ) {
        transactions.inTransaction {
            val role = get(roleId)
            val permission = grants.permissionByCode(code) ?: throw AccessFaults.unknownPermission("permission")
            if (grants.attach(role.id, permission.id, clock.instant()) == 1) roleChanged(role.id, "attached", role.slug, code)
        }
    }

    public fun detach(
        roleId: UUID,
        code: String,
    ) {
        transactions.inTransaction {
            val role = get(roleId)
            if (role.isSystem) throw AccessFaults.systemRole(role.slug)
            val permission = grants.permissionByCode(code) ?: throw AccessFaults.unknownPermission("code")
            if (grants.detach(role.id, permission.id) == 1) roleChanged(role.id, "detached", role.slug, code)
        }
    }

    private fun drain(step: () -> Int) {
        do {
            val removed = transactions.inTransaction(step)
        } while (removed == batch)
    }

    private fun checkName(name: String) {
        val code =
            when {
                name.isBlank() -> RainErrorCodes.REQUIRED
                name.length > SystemRoleDeclaration.MAX_NAME -> RainErrorCodes.TOO_LONG
                else -> return
            }
        throw AccessFaults.invalid("name", code, "a role has a name of 1..${SystemRoleDeclaration.MAX_NAME} characters")
    }

    private fun roleChanged(
        role: UUID,
        change: String,
        slug: String,
        permission: String? = null,
    ) {
        val detail = mutableListOf<Pair<String, Any>>("change" to change, "slug" to slug)
        permission?.let { detail += "permission" to it }
        audit.record(AuditEvent(AccessAuditTypes.ROLE_CHANGED, AuditOutcome.OK, role.toString(), AuditDetail.of(*detail.toTypedArray())))
    }
}

/**
 * The grant declarations agree with each other: a permission code and a module are declared once, a system role slug is
 * declared once, and every role a module's `roles` names is a declared system role holding codes the module declares.
 */
public class GrantDeclarationsCheck(
    private val modules: List<ModuleGrants>,
    private val systemRoles: List<SystemRoleDeclaration>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> = problemsOf(modules, systemRoles)

    public companion object {
        public fun problemsOf(
            modules: List<ModuleGrants>,
            systemRoles: List<SystemRoleDeclaration>,
        ): List<ConfigurationProblem> {
            val found = mutableListOf<ConfigurationProblem>()
            modules.groupBy { it.module }.filterValues { it.size > 1 }.keys.sorted().forEach {
                found += ConfigurationProblem("access.grants:$it", ProblemCode.CONTRADICTS, "module $it declares its grants more than once")
            }
            modules
                .flatMap { module -> module.permissions.map { it.code to module.module } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }
                .toSortedMap()
                .forEach { (code, owners) ->
                    found +=
                        ConfigurationProblem(
                            "access.permission:$code",
                            ProblemCode.CONTRADICTS,
                            "permission $code is declared by ${owners.joinToString(", ")}",
                        )
                }
            systemRoles.groupBy { it.slug }.filterValues { it.size > 1 }.keys.sorted().forEach {
                found +=
                    ConfigurationProblem("access.system-role:$it", ProblemCode.CONTRADICTS, "system role $it is declared more than once")
            }
            val declaredRoles = systemRoles.map { it.slug }.toSet()
            modules.sortedBy { it.module }.forEach { module ->
                val codes = module.permissions.map { it.code }.toSet()
                module.roles.toSortedMap().forEach { (slug, held) ->
                    if (slug !in declaredRoles) {
                        found +=
                            ConfigurationProblem(
                                "access.system-role:$slug",
                                ProblemCode.REQUIRED,
                                "module ${module.module} gives codes to role $slug, which no SystemRoleDeclaration declares",
                            )
                    }
                    (held - codes).sorted().forEach { code ->
                        found +=
                            ConfigurationProblem(
                                "access.grants:${module.module}",
                                ProblemCode.INVALID,
                                "gives role $slug the code $code, which module ${module.module} does not declare",
                            )
                    }
                }
            }
            return found
        }
    }
}
