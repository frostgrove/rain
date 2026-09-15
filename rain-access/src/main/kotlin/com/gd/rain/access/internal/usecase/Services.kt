package com.gd.rain.access.internal.usecase

import com.gd.rain.access.AccessPrincipal
import com.gd.rain.access.AccessProvisioning
import com.gd.rain.access.Enrolment
import com.gd.rain.access.GrantsLookup
import com.gd.rain.access.HolderSearch
import com.gd.rain.access.PermissionPage
import com.gd.rain.access.SubjectDirectory
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.audit.AuditTrail
import com.gd.rain.access.internal.password.HashingBulkhead
import com.gd.rain.access.internal.password.PasswordHasher
import com.gd.rain.access.internal.store.CredentialInsert
import com.gd.rain.access.internal.store.CredentialStore
import com.gd.rain.access.internal.store.GrantStore
import com.gd.rain.audit.AuditDetail
import com.gd.rain.audit.AuditEvent
import com.gd.rain.audit.AuditOutcome
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.id.IdGenerator
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import java.time.Clock
import java.util.UUID

public class ProvisioningService(
    private val subjects: SubjectRegistry,
    private val credentials: CredentialStore,
    private val grants: GrantStore,
    private val administration: GrantsAdministration,
    private val hasher: PasswordHasher,
    private val bulkhead: HashingBulkhead,
    private val rules: PasswordRules,
    private val audit: AuditTrail,
    private val transactions: AccessTransactions,
    private val ids: IdGenerator,
    private val holderPageSize: Int,
    private val holderPageBudget: Int,
    private val clock: Clock,
) : AccessProvisioning {
    override fun ensureRole(
        slug: String,
        name: String,
        permissions: Set<String>,
    ): UUID {
        require(SystemRoleDeclaration.isWellFormedSlug(slug)) { "a role slug matches ${SystemRoleDeclaration.SLUG_PATTERN}, got \"$slug\"" }
        require(name.isNotBlank()) { "role $slug has a name" }
        return transactions.inTransaction {
            val now = clock.instant()
            val role =
                grants.roleBySlug(slug) ?: run {
                    val id = ids.next()
                    grants.createRole(id, slug, name, now)
                    val created = grants.roleBySlug(slug) ?: error("role $slug was created and cannot be read back")
                    if (created.id == id) roleChanged(created.id, "created", slug, null)
                    created
                }
            permissions.sorted().forEach { code ->
                val permission = grants.permissionByCode(code) ?: throw AccessFaults.unknownPermission("permissions")
                if (grants.attach(role.id, permission.id, now) == 1) roleChanged(role.id, "attached", slug, code)
            }
            role.id
        }
    }

    override fun setDefaultRole(
        type: SubjectType,
        slug: String,
    ) {
        subjects.served(type) ?: throw AccessFaults.unknownSubjectType(type.name, "type")
        transactions.inTransaction {
            val role = grants.roleBySlug(slug) ?: throw AccessFaults.unknownRole("slug")
            grants.bindDefaultRole(type, role.id, clock.instant())
            audit.record(AuditEvent(AccessAuditTypes.DEFAULT_ROLE_CHANGED, AuditOutcome.OK, type.name, AuditDetail.of("slug" to slug)))
        }
    }

    override fun grantRole(
        subject: SubjectRef,
        slug: String,
    ) {
        val served = subjects.served(subject.type) ?: throw AccessFaults.unknownSubjectType(subject.type.name, "subject")
        administration.grantRole(served, subject, slug)
    }

    override fun enrolPassword(
        subject: SubjectRef,
        identifier: String,
        password: String,
    ): Enrolment {
        val served = subjects.served(subject.type) ?: throw AccessFaults.unknownSubjectType(subject.type.name, "subject")
        if (credentials.findBySubject(subject) != null) return Enrolment.AlreadyEnrolled
        val normalized = served.mounted.normalization.normalize(identifier)
        rules.checkIdentifier(normalized, "identifier")
        rules.checkNewPassword(password, "password")
        transactions.requireOutside("enrolling a password")
        val hash = bulkhead.run { hasher.hash(password) }
        return transactions.inTransaction {
            when (credentials.insert(ids.next(), subject, normalized, hash, clock.instant())) {
                CredentialInsert.INSERTED -> {
                    audit.record(
                        AuditEvent(
                            AccessAuditTypes.PASSWORD_ENROLLED,
                            AuditOutcome.OK,
                            subject.resourceId,
                            AuditDetail.of("by" to "provisioning"),
                        ),
                    )
                    Enrolment.Enrolled
                }

                CredentialInsert.SUBJECT_ENROLLED -> {
                    Enrolment.AlreadyEnrolled
                }

                CredentialInsert.IDENTIFIER_TAKEN -> {
                    throw AccessFaults.identifierTaken()
                }
            }
        }
    }

    override fun hasPassword(subject: SubjectRef): Boolean = credentials.findBySubject(subject) != null

    override fun usableHolderOf(slug: String): HolderSearch {
        val role = grants.roleBySlug(slug) ?: throw AccessFaults.unknownRole("slug")
        var after: SubjectRef? = null
        repeat(holderPageBudget) { scanned ->
            val page = grants.holdersPage(role.id, after, holderPageSize)
            page
                .groupBy { it.type }
                .forEach { (type, holders) ->
                    val served = subjects.served(type) ?: return@forEach
                    val withPassword = credentials.withPassword(type, holders.map { it.id })
                    holders.firstOrNull { it.id in withPassword && served.directory.isActive(it.id) }?.let { return HolderSearch.Found(it) }
                }
            if (page.size < holderPageSize) return HolderSearch.NoneFound
            after = page.last()
            if (scanned + 1 == holderPageBudget) return HolderSearch.NotEvaluated(holderPageBudget)
        }
        return HolderSearch.NotEvaluated(holderPageBudget)
    }

    private fun roleChanged(
        role: UUID,
        change: String,
        slug: String,
        permission: String?,
    ) {
        val detail = mutableListOf<Pair<String, Any>>("change" to change, "slug" to slug)
        permission?.let { detail += "permission" to it }
        audit.record(AuditEvent(AccessAuditTypes.ROLE_CHANGED, AuditOutcome.OK, role.toString(), AuditDetail.of(*detail.toTypedArray())))
    }
}

/**
 * [GrantsLookup] over the grant store. Within a servlet request, what a subject was found to hold is remembered for the
 * rest of the request, so asking about the same code twice costs one statement.
 */
public class GrantsService(
    private val subjects: SubjectRegistry,
    private val grants: GrantStore,
    private val maxPageSize: Int,
) : GrantsLookup {
    override fun principalOf(): AccessPrincipal? = SecurityContextHolder.getContext().authentication?.principal as? AccessPrincipal

    override fun heldBy(
        subject: SubjectRef,
        codes: Set<String>,
    ): Set<String> {
        if (codes.isEmpty()) return emptySet()
        val request = RequestContextHolder.getRequestAttributes() ?: return grants.heldCodes(subject, codes)
        val attribute = MEMO_PREFIX + subject.resourceId

        @Suppress("UNCHECKED_CAST")
        val memo =
            request.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST) as? MutableMap<String, Boolean>
                ?: HashMap<String, Boolean>().also { request.setAttribute(attribute, it, RequestAttributes.SCOPE_REQUEST) }
        val unknown = codes - memo.keys
        if (unknown.isNotEmpty()) {
            val held = grants.heldCodes(subject, unknown)
            unknown.forEach { memo[it] = it in held }
        }
        return codes.filterTo(HashSet()) { memo.getValue(it) }
    }

    override fun directPermissionsOf(
        subject: SubjectRef,
        after: UUID?,
        limit: Int,
    ): PermissionPage {
        if (limit !in 1..maxPageSize) {
            throw AccessFaults.invalid("limit", RainErrorCodes.OUT_OF_RANGE, "a page holds 1..$maxPageSize items")
        }
        val page = pageOf(grants.subjectPermissionsPage(subject, after, limit + 1), limit) { it.permissionId }
        return PermissionPage(page.items, page.next)
    }

    override fun directoryOf(type: SubjectType): SubjectDirectory? = subjects.served(type)?.directory

    private companion object {
        const val MEMO_PREFIX = "com.gd.rain.access.held."
    }
}
