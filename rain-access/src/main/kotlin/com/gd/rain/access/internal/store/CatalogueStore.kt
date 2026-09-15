package com.gd.rain.access.internal.store

import com.gd.rain.access.jooq.Tables.PERMISSIONS
import com.gd.rain.access.jooq.Tables.ROLES
import com.gd.rain.access.jooq.Tables.ROLE_PERMISSIONS
import org.jooq.DSLContext
import org.jooq.Select
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/** The statements the start-up catalogue synchronisation issues; each idempotent, so replicas starting together agree. */
public interface CatalogueStore {
    /** Inserts the permissions no row has yet; an existing code keeps its row, name and module. */
    public fun declarePermissions(
        permissions: List<DeclaredPermission>,
        now: Instant,
    )

    /** The ids of [codes] that exist, by an indexed probe per code. */
    public fun permissionIds(codes: Collection<String>): Map<String, UUID>

    /** Creates or claims the system role [slug] and answers its id. */
    public fun declareSystemRole(
        id: UUID,
        slug: String,
        name: String,
        grantsEveryPermission: Boolean,
        now: Instant,
    ): UUID

    /** Attaches the permissions the role does not hold yet; never detaches. */
    public fun attach(
        role: UUID,
        permissions: Collection<UUID>,
        now: Instant,
    ): Int
}

public class JooqCatalogueStore(
    private val dsl: DSLContext,
) : CatalogueStore {
    override fun declarePermissions(
        permissions: List<DeclaredPermission>,
        now: Instant,
    ) {
        if (permissions.isEmpty()) return
        val first = permissions.first()
        val insert =
            permissions.drop(1).fold(
                dsl
                    .insertInto(PERMISSIONS, PERMISSIONS.ID, PERMISSIONS.CODE, PERMISSIONS.NAME, PERMISSIONS.MODULE, PERMISSIONS.CREATED_AT)
                    .values(first.id, first.code, first.name, first.module, now.utc()),
            ) { statement, permission -> statement.values(permission.id, permission.code, permission.name, permission.module, now.utc()) }
        insert.onConflictDoNothing().execute()
    }

    override fun permissionIds(codes: Collection<String>): Map<String, UUID> {
        if (codes.isEmpty()) return emptyMap()
        return dsl.fetch(permissionIdsQuery(codes)).associate { it[PERMISSIONS.CODE] to it[PERMISSIONS.ID] }
    }

    /** The statement [permissionIds] runs: a lookup of `uq_permissions_code` by exactly the asked codes. */
    public fun permissionIdsQuery(codes: Collection<String>): Select<*> {
        require(codes.isNotEmpty()) { "a lookup of permission ids names at least one code" }
        return dsl
            .select(PERMISSIONS.CODE, PERMISSIONS.ID)
            .from(PERMISSIONS)
            .where(PERMISSIONS.CODE.`in`(codes))
    }

    override fun declareSystemRole(
        id: UUID,
        slug: String,
        name: String,
        grantsEveryPermission: Boolean,
        now: Instant,
    ): UUID =
        requireNotNull(
            dsl
                .insertInto(ROLES)
                .set(ROLES.ID, id)
                .set(ROLES.SLUG, slug)
                .set(ROLES.NAME, name)
                .set(ROLES.IS_SYSTEM, true)
                .set(ROLES.GRANTS_EVERY_PERMISSION, grantsEveryPermission)
                .set(ROLES.CREATED_AT, now.utc())
                .onConflict(ROLES.SLUG)
                .doUpdate()
                .set(ROLES.IS_SYSTEM, true)
                .set(ROLES.GRANTS_EVERY_PERMISSION, DSL.excluded(ROLES.GRANTS_EVERY_PERMISSION))
                .returning(ROLES.ID)
                .fetchSingle(ROLES.ID),
        ) { "declaring system role $slug returned no id" }

    override fun attach(
        role: UUID,
        permissions: Collection<UUID>,
        now: Instant,
    ): Int {
        if (permissions.isEmpty()) return 0
        val ids = permissions.toList()
        val insert =
            ids.drop(1).fold(
                dsl
                    .insertInto(ROLE_PERMISSIONS, ROLE_PERMISSIONS.ROLE_ID, ROLE_PERMISSIONS.PERMISSION_ID, ROLE_PERMISSIONS.ATTACHED_AT)
                    .values(role, ids.first(), now.utc()),
            ) { statement, permission -> statement.values(role, permission, now.utc()) }
        return insert.onConflictDoNothing().execute()
    }
}
