package com.gd.rain.access.internal.store

import com.gd.rain.access.GrantedPermission
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.jooq.Tables.PERMISSIONS
import com.gd.rain.access.jooq.Tables.ROLES
import com.gd.rain.access.jooq.Tables.ROLE_PERMISSIONS
import com.gd.rain.access.jooq.Tables.SUBJECT_DEFAULT_ROLES
import com.gd.rain.access.jooq.Tables.SUBJECT_PERMISSIONS
import com.gd.rain.access.jooq.Tables.SUBJECT_ROLES
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/** Roles, the catalogue as the API reads it, and what subjects hold. Every list is a keyset page; every removal a batch. */
public interface GrantStore {
    /** The subset of [codes] the subject holds, in one statement over exactly those codes. */
    public fun heldCodes(
        subject: SubjectRef,
        codes: Set<String>,
    ): Set<String>

    public fun roleBySlug(slug: String): RoleRow?

    public fun roleById(id: UUID): RoleRow?

    /** Creates an application role; answers whether this call created it. */
    public fun createRole(
        id: UUID,
        slug: String,
        name: String,
        now: Instant,
    ): Boolean

    /** Renames an application role; a system role is never changed. */
    public fun renameRole(
        id: UUID,
        name: String,
    ): Int

    public fun rolesPage(
        afterSlug: String?,
        limit: Int,
    ): List<RoleRow>

    public fun permissionByCode(code: String): PermissionRow?

    public fun permissionsPage(
        afterCode: String?,
        limit: Int,
    ): List<PermissionRow>

    public fun rolePermissionsPage(
        role: UUID,
        afterPermission: UUID?,
        limit: Int,
    ): List<PermissionRow>

    public fun attach(
        role: UUID,
        permission: UUID,
        now: Instant,
    ): Int

    public fun detach(
        role: UUID,
        permission: UUID,
    ): Int

    /** How many roles the subject holds, counted up to [bound] and no further. */
    public fun rolesHeldUpTo(
        subject: SubjectRef,
        bound: Int,
    ): Int

    public fun grantRole(
        subject: SubjectRef,
        role: UUID,
        now: Instant,
    ): Int

    public fun holdsRole(
        subject: SubjectRef,
        role: UUID,
    ): Boolean

    public fun revokeRole(
        subject: SubjectRef,
        role: UUID,
    ): Int

    public fun grantPermission(
        subject: SubjectRef,
        permission: UUID,
        now: Instant,
    ): Int

    public fun revokePermission(
        subject: SubjectRef,
        permission: UUID,
    ): Int

    public fun subjectRolesPage(
        subject: SubjectRef,
        afterRole: UUID?,
        limit: Int,
    ): List<HeldRole>

    public fun subjectPermissionsPage(
        subject: SubjectRef,
        afterPermission: UUID?,
        limit: Int,
    ): List<GrantedPermission>

    public fun holdersPage(
        role: UUID,
        after: SubjectRef?,
        limit: Int,
    ): List<SubjectRef>

    /** Removes up to [batch] holders of an application role; answers how many. */
    public fun revokeHoldersBatch(
        role: UUID,
        batch: Int,
    ): Int

    /** Detaches up to [batch] permissions of an application role; answers how many. */
    public fun detachPermissionsBatch(
        role: UUID,
        batch: Int,
    ): Int

    /** Deletes an application role that nobody holds and that holds nothing; a system role is never deleted. */
    public fun deleteRole(id: UUID): Int

    public fun defaultRoleOf(type: SubjectType): RoleRow?

    /** Makes [role] the default of [type]; answers whether this call changed it — false when it already was. */
    public fun bindDefaultRole(
        type: SubjectType,
        role: UUID,
        now: Instant,
    ): Boolean
}

public class JooqGrantStore(
    private val dsl: DSLContext,
) : GrantStore {
    override fun heldCodes(
        subject: SubjectRef,
        codes: Set<String>,
    ): Set<String> {
        if (codes.isEmpty()) return emptySet()
        return dsl.fetch(heldCodesQuery(subject, codes)).map { it.value1() }.toSet()
    }

    /**
     * Three branches, each driven by the asked codes through `uq_permissions_code` and answered by an `EXISTS` probe: a
     * direct grant (the whole primary key of `subject_permissions`), a grant through a held role (the subject's roles by
     * the primary key of `subject_roles`, each probed on the whole primary key of `role_permissions`), and a held role
     * that grants every permission (`ix_roles_every_permission`). The work is the asked codes times the subject's roles,
     * which `max-roles-per-subject` bounds, and never the size of any table.
     */
    public fun heldCodesQuery(
        subject: SubjectRef,
        codes: Set<String>,
    ): Select<Record1<String>> {
        require(codes.isNotEmpty()) { "a permission question names at least one code" }
        val direct =
            dsl
                .select(PERMISSIONS.CODE)
                .from(PERMISSIONS)
                .where(PERMISSIONS.CODE.`in`(codes))
                .andExists(
                    dsl
                        .selectOne()
                        .from(SUBJECT_PERMISSIONS)
                        .where(SUBJECT_PERMISSIONS.SUBJECT_TYPE.eq(subject.type.name))
                        .and(SUBJECT_PERMISSIONS.SUBJECT_ID.eq(subject.id))
                        .and(SUBJECT_PERMISSIONS.PERMISSION_ID.eq(PERMISSIONS.ID)),
                )
        val throughRole =
            dsl
                .select(PERMISSIONS.CODE)
                .from(PERMISSIONS)
                .where(PERMISSIONS.CODE.`in`(codes))
                .andExists(
                    dsl
                        .selectOne()
                        .from(SUBJECT_ROLES)
                        .join(ROLE_PERMISSIONS)
                        .on(ROLE_PERMISSIONS.ROLE_ID.eq(SUBJECT_ROLES.ROLE_ID))
                        .and(ROLE_PERMISSIONS.PERMISSION_ID.eq(PERMISSIONS.ID))
                        .where(SUBJECT_ROLES.SUBJECT_TYPE.eq(subject.type.name))
                        .and(SUBJECT_ROLES.SUBJECT_ID.eq(subject.id)),
                )
        val everything =
            dsl
                .select(PERMISSIONS.CODE)
                .from(PERMISSIONS)
                .where(PERMISSIONS.CODE.`in`(codes))
                .andExists(
                    dsl
                        .selectOne()
                        .from(SUBJECT_ROLES)
                        .join(ROLES)
                        .on(ROLES.ID.eq(SUBJECT_ROLES.ROLE_ID))
                        .and(ROLES.GRANTS_EVERY_PERMISSION.isTrue)
                        .where(SUBJECT_ROLES.SUBJECT_TYPE.eq(subject.type.name))
                        .and(SUBJECT_ROLES.SUBJECT_ID.eq(subject.id)),
                )
        return direct.union(throughRole).union(everything)
    }

    override fun roleBySlug(slug: String): RoleRow? = dsl.selectFrom(ROLES).where(ROLES.SLUG.eq(slug)).fetchOne(::roleOf)

    override fun roleById(id: UUID): RoleRow? = dsl.selectFrom(ROLES).where(ROLES.ID.eq(id)).fetchOne(::roleOf)

    override fun createRole(
        id: UUID,
        slug: String,
        name: String,
        now: Instant,
    ): Boolean =
        dsl
            .insertInto(ROLES)
            .set(ROLES.ID, id)
            .set(ROLES.SLUG, slug)
            .set(ROLES.NAME, name)
            .set(ROLES.IS_SYSTEM, false)
            .set(ROLES.GRANTS_EVERY_PERMISSION, false)
            .set(ROLES.CREATED_AT, now.utc())
            .onConflictDoNothing()
            .execute() == 1

    override fun renameRole(
        id: UUID,
        name: String,
    ): Int =
        dsl
            .update(ROLES)
            .set(ROLES.NAME, name)
            .where(ROLES.ID.eq(id))
            .and(ROLES.IS_SYSTEM.isFalse)
            .execute()

    override fun rolesPage(
        afterSlug: String?,
        limit: Int,
    ): List<RoleRow> = dsl.fetch(rolesPageQuery(afterSlug, limit)).map { roleOf(it) }

    public fun rolesPageQuery(
        afterSlug: String?,
        limit: Int,
    ): Select<*> =
        dsl
            .select(ROLES.fields().toList())
            .from(ROLES)
            .where(afterSlug?.let(ROLES.SLUG::gt) ?: DSL.noCondition())
            .orderBy(ROLES.SLUG)
            .limit(bounded(limit))

    override fun permissionByCode(code: String): PermissionRow? =
        dsl.selectFrom(PERMISSIONS).where(PERMISSIONS.CODE.eq(code)).fetchOne(::permissionOf)

    override fun permissionsPage(
        afterCode: String?,
        limit: Int,
    ): List<PermissionRow> = dsl.fetch(permissionsPageQuery(afterCode, limit)).map { permissionOf(it) }

    public fun permissionsPageQuery(
        afterCode: String?,
        limit: Int,
    ): Select<*> =
        dsl
            .select(PERMISSIONS.fields().toList())
            .from(PERMISSIONS)
            .where(afterCode?.let(PERMISSIONS.CODE::gt) ?: DSL.noCondition())
            .orderBy(PERMISSIONS.CODE)
            .limit(bounded(limit))

    override fun rolePermissionsPage(
        role: UUID,
        afterPermission: UUID?,
        limit: Int,
    ): List<PermissionRow> = dsl.fetch(rolePermissionsPageQuery(role, afterPermission, limit)).map { permissionOf(it) }

    public fun rolePermissionsPageQuery(
        role: UUID,
        afterPermission: UUID?,
        limit: Int,
    ): Select<*> =
        dsl
            .select(PERMISSIONS.fields().toList())
            .from(ROLE_PERMISSIONS)
            .join(PERMISSIONS)
            .on(PERMISSIONS.ID.eq(ROLE_PERMISSIONS.PERMISSION_ID))
            .where(ROLE_PERMISSIONS.ROLE_ID.eq(role))
            .and(afterPermission?.let(ROLE_PERMISSIONS.PERMISSION_ID::gt) ?: DSL.noCondition())
            .orderBy(ROLE_PERMISSIONS.PERMISSION_ID)
            .limit(bounded(limit))

    override fun attach(
        role: UUID,
        permission: UUID,
        now: Instant,
    ): Int =
        dsl
            .insertInto(ROLE_PERMISSIONS)
            .set(ROLE_PERMISSIONS.ROLE_ID, role)
            .set(ROLE_PERMISSIONS.PERMISSION_ID, permission)
            .set(ROLE_PERMISSIONS.ATTACHED_AT, now.utc())
            .onConflictDoNothing()
            .execute()

    override fun detach(
        role: UUID,
        permission: UUID,
    ): Int =
        dsl
            .deleteFrom(ROLE_PERMISSIONS)
            .where(ROLE_PERMISSIONS.ROLE_ID.eq(role))
            .and(ROLE_PERMISSIONS.PERMISSION_ID.eq(permission))
            .execute()

    override fun rolesHeldUpTo(
        subject: SubjectRef,
        bound: Int,
    ): Int {
        val held =
            dsl
                .selectOne()
                .from(SUBJECT_ROLES)
                .where(SUBJECT_ROLES.SUBJECT_TYPE.eq(subject.type.name))
                .and(SUBJECT_ROLES.SUBJECT_ID.eq(subject.id))
                .limit(bounded(bound))
        return dsl.fetchCount(held)
    }

    override fun grantRole(
        subject: SubjectRef,
        role: UUID,
        now: Instant,
    ): Int =
        dsl
            .insertInto(SUBJECT_ROLES)
            .set(SUBJECT_ROLES.SUBJECT_TYPE, subject.type.name)
            .set(SUBJECT_ROLES.SUBJECT_ID, subject.id)
            .set(SUBJECT_ROLES.ROLE_ID, role)
            .set(SUBJECT_ROLES.GRANTED_AT, now.utc())
            .onConflictDoNothing()
            .execute()

    override fun holdsRole(
        subject: SubjectRef,
        role: UUID,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(SUBJECT_ROLES)
                .where(SUBJECT_ROLES.SUBJECT_TYPE.eq(subject.type.name))
                .and(SUBJECT_ROLES.SUBJECT_ID.eq(subject.id))
                .and(SUBJECT_ROLES.ROLE_ID.eq(role)),
        )

    override fun revokeRole(
        subject: SubjectRef,
        role: UUID,
    ): Int =
        dsl
            .deleteFrom(SUBJECT_ROLES)
            .where(SUBJECT_ROLES.SUBJECT_TYPE.eq(subject.type.name))
            .and(SUBJECT_ROLES.SUBJECT_ID.eq(subject.id))
            .and(SUBJECT_ROLES.ROLE_ID.eq(role))
            .execute()

    override fun grantPermission(
        subject: SubjectRef,
        permission: UUID,
        now: Instant,
    ): Int =
        dsl
            .insertInto(SUBJECT_PERMISSIONS)
            .set(SUBJECT_PERMISSIONS.SUBJECT_TYPE, subject.type.name)
            .set(SUBJECT_PERMISSIONS.SUBJECT_ID, subject.id)
            .set(SUBJECT_PERMISSIONS.PERMISSION_ID, permission)
            .set(SUBJECT_PERMISSIONS.GRANTED_AT, now.utc())
            .onConflictDoNothing()
            .execute()

    override fun revokePermission(
        subject: SubjectRef,
        permission: UUID,
    ): Int =
        dsl
            .deleteFrom(SUBJECT_PERMISSIONS)
            .where(SUBJECT_PERMISSIONS.SUBJECT_TYPE.eq(subject.type.name))
            .and(SUBJECT_PERMISSIONS.SUBJECT_ID.eq(subject.id))
            .and(SUBJECT_PERMISSIONS.PERMISSION_ID.eq(permission))
            .execute()

    override fun subjectRolesPage(
        subject: SubjectRef,
        afterRole: UUID?,
        limit: Int,
    ): List<HeldRole> =
        dsl.fetch(subjectRolesPageQuery(subject, afterRole, limit)).map {
            HeldRole(it[SUBJECT_ROLES.ROLE_ID], it[ROLES.SLUG], it[SUBJECT_ROLES.GRANTED_AT].toInstant())
        }

    public fun subjectRolesPageQuery(
        subject: SubjectRef,
        afterRole: UUID?,
        limit: Int,
    ): Select<*> =
        dsl
            .select(SUBJECT_ROLES.ROLE_ID, ROLES.SLUG, SUBJECT_ROLES.GRANTED_AT)
            .from(SUBJECT_ROLES)
            .join(ROLES)
            .on(ROLES.ID.eq(SUBJECT_ROLES.ROLE_ID))
            .where(SUBJECT_ROLES.SUBJECT_TYPE.eq(subject.type.name))
            .and(SUBJECT_ROLES.SUBJECT_ID.eq(subject.id))
            .and(afterRole?.let(SUBJECT_ROLES.ROLE_ID::gt) ?: DSL.noCondition())
            .orderBy(SUBJECT_ROLES.ROLE_ID)
            .limit(bounded(limit))

    override fun subjectPermissionsPage(
        subject: SubjectRef,
        afterPermission: UUID?,
        limit: Int,
    ): List<GrantedPermission> =
        dsl.fetch(subjectPermissionsPageQuery(subject, afterPermission, limit)).map {
            GrantedPermission(it[SUBJECT_PERMISSIONS.PERMISSION_ID], it[PERMISSIONS.CODE], it[SUBJECT_PERMISSIONS.GRANTED_AT].toInstant())
        }

    public fun subjectPermissionsPageQuery(
        subject: SubjectRef,
        afterPermission: UUID?,
        limit: Int,
    ): Select<*> =
        dsl
            .select(SUBJECT_PERMISSIONS.PERMISSION_ID, PERMISSIONS.CODE, SUBJECT_PERMISSIONS.GRANTED_AT)
            .from(SUBJECT_PERMISSIONS)
            .join(PERMISSIONS)
            .on(PERMISSIONS.ID.eq(SUBJECT_PERMISSIONS.PERMISSION_ID))
            .where(SUBJECT_PERMISSIONS.SUBJECT_TYPE.eq(subject.type.name))
            .and(SUBJECT_PERMISSIONS.SUBJECT_ID.eq(subject.id))
            .and(afterPermission?.let(SUBJECT_PERMISSIONS.PERMISSION_ID::gt) ?: DSL.noCondition())
            .orderBy(SUBJECT_PERMISSIONS.PERMISSION_ID)
            .limit(bounded(limit))

    override fun holdersPage(
        role: UUID,
        after: SubjectRef?,
        limit: Int,
    ): List<SubjectRef> =
        dsl.fetch(holdersPageQuery(role, after, limit)).map {
            SubjectRef(SubjectType(it[SUBJECT_ROLES.SUBJECT_TYPE]), it[SUBJECT_ROLES.SUBJECT_ID])
        }

    public fun holdersPageQuery(
        role: UUID,
        after: SubjectRef?,
        limit: Int,
    ): Select<*> =
        dsl
            .select(SUBJECT_ROLES.SUBJECT_TYPE, SUBJECT_ROLES.SUBJECT_ID)
            .from(SUBJECT_ROLES)
            .where(SUBJECT_ROLES.ROLE_ID.eq(role))
            .and(
                after?.let { DSL.row(SUBJECT_ROLES.SUBJECT_TYPE, SUBJECT_ROLES.SUBJECT_ID).gt(it.type.name, it.id) }
                    ?: DSL.noCondition(),
            ).orderBy(SUBJECT_ROLES.SUBJECT_TYPE, SUBJECT_ROLES.SUBJECT_ID)
            .limit(bounded(limit))

    override fun revokeHoldersBatch(
        role: UUID,
        batch: Int,
    ): Int {
        val chosen =
            dsl
                .select(SUBJECT_ROLES.SUBJECT_TYPE, SUBJECT_ROLES.SUBJECT_ID)
                .from(SUBJECT_ROLES)
                .where(SUBJECT_ROLES.ROLE_ID.eq(role))
                .orderBy(SUBJECT_ROLES.SUBJECT_TYPE, SUBJECT_ROLES.SUBJECT_ID)
                .limit(bounded(batch))
        return dsl
            .deleteFrom(SUBJECT_ROLES)
            .where(SUBJECT_ROLES.ROLE_ID.eq(role))
            .and(DSL.row(SUBJECT_ROLES.SUBJECT_TYPE, SUBJECT_ROLES.SUBJECT_ID).`in`(chosen))
            .execute()
    }

    override fun detachPermissionsBatch(
        role: UUID,
        batch: Int,
    ): Int {
        val chosen =
            dsl
                .select(ROLE_PERMISSIONS.PERMISSION_ID)
                .from(ROLE_PERMISSIONS)
                .where(ROLE_PERMISSIONS.ROLE_ID.eq(role))
                .orderBy(ROLE_PERMISSIONS.PERMISSION_ID)
                .limit(bounded(batch))
        return dsl
            .deleteFrom(ROLE_PERMISSIONS)
            .where(ROLE_PERMISSIONS.ROLE_ID.eq(role))
            .and(ROLE_PERMISSIONS.PERMISSION_ID.`in`(chosen))
            .execute()
    }

    override fun deleteRole(id: UUID): Int =
        dsl
            .deleteFrom(ROLES)
            .where(ROLES.ID.eq(id))
            .and(ROLES.IS_SYSTEM.isFalse)
            .execute()

    override fun defaultRoleOf(type: SubjectType): RoleRow? =
        dsl
            .select(ROLES.fields().toList())
            .from(SUBJECT_DEFAULT_ROLES)
            .join(ROLES)
            .on(ROLES.ID.eq(SUBJECT_DEFAULT_ROLES.ROLE_ID))
            .where(SUBJECT_DEFAULT_ROLES.SUBJECT_TYPE.eq(type.name))
            .fetchOne(::roleOf)

    override fun bindDefaultRole(
        type: SubjectType,
        role: UUID,
        now: Instant,
    ): Boolean =
        dsl
            .insertInto(SUBJECT_DEFAULT_ROLES)
            .set(SUBJECT_DEFAULT_ROLES.SUBJECT_TYPE, type.name)
            .set(SUBJECT_DEFAULT_ROLES.ROLE_ID, role)
            .set(SUBJECT_DEFAULT_ROLES.UPDATED_AT, now.utc())
            .onConflict(SUBJECT_DEFAULT_ROLES.SUBJECT_TYPE)
            .doUpdate()
            .set(SUBJECT_DEFAULT_ROLES.ROLE_ID, role)
            .set(SUBJECT_DEFAULT_ROLES.UPDATED_AT, now.utc())
            .where(SUBJECT_DEFAULT_ROLES.ROLE_ID.ne(role))
            .execute() == 1

    private fun bounded(limit: Int): Int {
        require(limit >= 1) { "a page or batch holds at least one row, got $limit" }
        return limit
    }

    private fun roleOf(record: Record): RoleRow =
        RoleRow(
            id = record[ROLES.ID],
            slug = record[ROLES.SLUG],
            name = record[ROLES.NAME],
            isSystem = record[ROLES.IS_SYSTEM],
            grantsEveryPermission = record[ROLES.GRANTS_EVERY_PERMISSION],
            createdAt = record[ROLES.CREATED_AT].toInstant(),
        )

    private fun permissionOf(record: Record): PermissionRow =
        PermissionRow(
            id = record[PERMISSIONS.ID],
            code = record[PERMISSIONS.CODE],
            name = record[PERMISSIONS.NAME],
            module = record[PERMISSIONS.MODULE],
            createdAt = record[PERMISSIONS.CREATED_AT].toInstant(),
        )
}
