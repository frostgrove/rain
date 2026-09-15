package com.gd.rain.access.internal.web

import com.gd.rain.access.GrantedPermission
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.store.HeldRole
import com.gd.rain.access.internal.usecase.GrantsAdministration
import com.gd.rain.access.internal.usecase.RoleAdministration
import com.gd.rain.access.internal.usecase.SetSubjectPasswordUseCase
import com.gd.rain.access.internal.usecase.SubjectRegistry
import com.gd.rain.web.route.Access
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * `<base>/roles`: application and system roles, a keyset page by slug at a time. A list names no relation to load
 * alongside; a role's permissions are their own page.
 */
@RestController
@RequestMapping("\${rain.access.web.base-path}/roles")
public class RoleController(
    private val roles: RoleAdministration,
    private val pages: PageRequest,
) {
    @Access(permissions = [AccessPermissionCodes.ROLE_READ])
    @GetMapping
    public fun list(request: HttpServletRequest): PageView<RoleView> {
        pages.only(request, PageRequest.AFTER, PageRequest.LIMIT)
        val page = roles.page(pages.after(request, PageRequest::slug), pages.limit(request))
        return PageView(page.items.map(RoleView::of), page.next)
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_READ])
    @GetMapping("/{roleId}")
    public fun get(
        @PathVariable roleId: String,
        request: HttpServletRequest,
    ): RoleView {
        pages.only(request)
        return RoleView.of(roles.get(CanonicalIds.required(roleId, "roleId")))
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_WRITE])
    @PostMapping
    public fun create(
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<RoleView> {
        pages.only(request)
        val form = JsonBody(body)
        return ResponseEntity
            .status(
                HttpStatus.CREATED,
            ).body(RoleView.of(roles.create(form.requiredText("slug"), form.requiredText("name"))))
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_WRITE])
    @PatchMapping("/{roleId}")
    public fun rename(
        @PathVariable roleId: String,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): RoleView {
        pages.only(request)
        return RoleView.of(roles.rename(CanonicalIds.required(roleId, "roleId"), JsonBody(body).requiredText("name")))
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_DELETE])
    @DeleteMapping("/{roleId}")
    public fun delete(
        @PathVariable roleId: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        roles.delete(CanonicalIds.required(roleId, "roleId"))
        return ResponseEntity.noContent().build()
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_DELETE])
    @PostMapping("/bulk-delete")
    public fun deleteAll(
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        roles.deleteAll(JsonBody(body).canonicalIds("ids"))
        return ResponseEntity.noContent().build()
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_READ])
    @GetMapping("/{roleId}/permissions")
    public fun permissions(
        @PathVariable roleId: String,
        request: HttpServletRequest,
    ): PageView<PermissionView> {
        pages.only(request, PageRequest.AFTER, PageRequest.LIMIT)
        val page =
            roles.permissionsOf(
                CanonicalIds.required(roleId, "roleId"),
                pages.after(request, CanonicalIds::parse),
                pages.limit(request),
            )
        return PageView(page.items.map(PermissionView::of), page.next?.toString())
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_WRITE])
    @PostMapping("/{roleId}/permissions")
    public fun attach(
        @PathVariable roleId: String,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        roles.attach(CanonicalIds.required(roleId, "roleId"), JsonBody(body).requiredText("permission"))
        return ResponseEntity.noContent().build()
    }

    @Access(permissions = [AccessPermissionCodes.ROLE_WRITE])
    @DeleteMapping("/{roleId}/permissions/{code}")
    public fun detach(
        @PathVariable roleId: String,
        @PathVariable code: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        roles.detach(CanonicalIds.required(roleId, "roleId"), code)
        return ResponseEntity.noContent().build()
    }
}

/** `<base>/permissions`: the catalogue, a keyset page by code at a time. */
@RestController
@RequestMapping("\${rain.access.web.base-path}/permissions")
public class PermissionController(
    private val roles: RoleAdministration,
    private val pages: PageRequest,
) {
    @Access(permissions = [AccessPermissionCodes.ROLE_READ])
    @GetMapping
    public fun list(request: HttpServletRequest): PageView<PermissionView> {
        pages.only(request, PageRequest.AFTER, PageRequest.LIMIT)
        val page = roles.permissionsPage(pages.after(request, PageRequest::code), pages.limit(request))
        return PageView(page.items.map(PermissionView::of), page.next)
    }
}

public data class HeldRoleView(
    public val roleId: UUID,
    public val slug: String,
    public val grantedAt: Instant,
) {
    public companion object {
        public fun of(role: HeldRole): HeldRoleView = HeldRoleView(role.roleId, role.slug, role.grantedAt)
    }
}

public data class GrantedPermissionView(
    public val permissionId: UUID,
    public val code: String,
    public val grantedAt: Instant,
) {
    public companion object {
        public fun of(permission: GrantedPermission): GrantedPermissionView =
            GrantedPermissionView(permission.permissionId, permission.code, permission.grantedAt)
    }
}

/** `<base>/subjects/{subjectType}/{subjectId}/…`: what one subject of any served type holds, and changing it. */
@RestController
@RequestMapping("\${rain.access.web.base-path}/subjects/{subjectType}/{subjectId}")
public class SubjectGrantController(
    private val subjects: SubjectRegistry,
    private val grants: GrantsAdministration,
    private val passwords: SetSubjectPasswordUseCase,
    private val pages: PageRequest,
) {
    @Access(permissions = [AccessPermissionCodes.GRANT_READ])
    @GetMapping("/roles")
    public fun roles(
        @PathVariable subjectType: String,
        @PathVariable subjectId: String,
        request: HttpServletRequest,
    ): PageView<HeldRoleView> {
        pages.only(request, PageRequest.AFTER, PageRequest.LIMIT)
        val page = grants.rolesOf(subject(subjectType, subjectId), pages.after(request, CanonicalIds::parse), pages.limit(request))
        return PageView(page.items.map(HeldRoleView::of), page.next?.toString())
    }

    @Access(permissions = [AccessPermissionCodes.GRANT_READ])
    @GetMapping("/permissions")
    public fun permissions(
        @PathVariable subjectType: String,
        @PathVariable subjectId: String,
        request: HttpServletRequest,
    ): PageView<GrantedPermissionView> {
        pages.only(request, PageRequest.AFTER, PageRequest.LIMIT)
        val page =
            grants.directPermissionsOf(
                subject(subjectType, subjectId),
                pages.after(request, CanonicalIds::parse),
                pages.limit(request),
            )
        return PageView(page.items.map(GrantedPermissionView::of), page.next?.toString())
    }

    @Access(permissions = [AccessPermissionCodes.GRANT_WRITE])
    @PostMapping("/roles")
    public fun grantRole(
        @PathVariable subjectType: String,
        @PathVariable subjectId: String,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        val served = subjects.resolve(subjectType, "subjectType")
        grants.grantRole(
            served,
            SubjectRef(served.type, CanonicalIds.required(subjectId, "subjectId")),
            JsonBody(body).requiredText("role"),
        )
        return ResponseEntity.noContent().build()
    }

    @Access(permissions = [AccessPermissionCodes.GRANT_WRITE])
    @DeleteMapping("/roles/{slug}")
    public fun revokeRole(
        @PathVariable subjectType: String,
        @PathVariable subjectId: String,
        @PathVariable slug: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        grants.revokeRole(subject(subjectType, subjectId), slug)
        return ResponseEntity.noContent().build()
    }

    @Access(permissions = [AccessPermissionCodes.GRANT_WRITE])
    @PostMapping("/permissions")
    public fun grantPermission(
        @PathVariable subjectType: String,
        @PathVariable subjectId: String,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        val served = subjects.resolve(subjectType, "subjectType")
        grants.grantPermission(
            served,
            SubjectRef(served.type, CanonicalIds.required(subjectId, "subjectId")),
            JsonBody(body).requiredText("permission"),
        )
        return ResponseEntity.noContent().build()
    }

    @Access(permissions = [AccessPermissionCodes.GRANT_WRITE])
    @DeleteMapping("/permissions/{code}")
    public fun revokePermission(
        @PathVariable subjectType: String,
        @PathVariable subjectId: String,
        @PathVariable code: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        grants.revokePermission(subject(subjectType, subjectId), code)
        return ResponseEntity.noContent().build()
    }

    @Access(permissions = [AccessPermissionCodes.CREDENTIAL_WRITE])
    @PutMapping("/password")
    public fun setPassword(
        @PathVariable subjectType: String,
        @PathVariable subjectId: String,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        pages.only(request)
        passwords.set(subjectType, subjectId, JsonBody(body).requiredText("password"))
        return ResponseEntity.noContent().build()
    }

    private fun subject(
        subjectType: String,
        subjectId: String,
    ): SubjectRef = SubjectRef(subjects.resolve(subjectType, "subjectType").type, CanonicalIds.required(subjectId, "subjectId"))
}
