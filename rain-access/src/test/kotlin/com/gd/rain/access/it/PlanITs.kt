package com.gd.rain.access.it

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.store.RevokedSession
import com.gd.rain.access.internal.store.SessionCursor
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.internal.usecase.SessionsQuery
import com.gd.rain.access.support.ACCESS_TTL
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AccessDatabase
import com.gd.rain.access.support.IDLE_TTL
import com.gd.rain.access.support.PlanShape
import com.gd.rain.access.support.START
import com.gd.rain.access.support.openSession
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlan
import com.gd.rain.test.QueryPlans
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Query
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.util.UUID

private const val SCHEMA = "rain_access"

private fun AccessDatabase.plan(query: Query): QueryPlan = QueryPlans.explain(dataSource, dsl.renderInlined(query), generic = false)

/** One statement to prove: the index it is written for, when it names one, and every table it reads. */
private class Statement(
    val name: String,
    val query: Query,
    val index: String?,
    vararg relations: String,
) {
    val relations: List<String> = relations.toList()
}

/**
 * Plan criterion v3 ([QueryPlan.boundedScan]) for every table [statement] reads, and the index it is written for: a
 * statement whose plan passes reads the same number of rows from a table of ten rows and of ten million.
 */
private fun AccessDatabase.assertBounded(statement: Statement) {
    val plan = plan(statement.query)
    statement.index?.let { assertThat(plan.usesIndex(it)).describedAs("${statement.name} uses $it:\n$plan").isTrue() }
    statement.relations.forEach { relation ->
        assertThat(
            plan.boundedScan(SCHEMA, relation),
        ).describedAs("${statement.name} reads $SCHEMA.$relation:\n$plan").isEqualTo(PlanVerdict.Bounded)
    }
}

private val ACCESS_TABLES = listOf("permissions", "roles", "role_permissions", "subject_roles", "subject_permissions")

/**
 * Gap 22: the per-request question reads the asked codes and the subject's own grants through indexes, whatever the tables hold.
 *
 * Plan criterion v3 does not decide this statement: each asked code's row answers a correlated `EXISTS`, a SubPlan run
 * once per row of the unique code lookup, and v3 accepts a scan only when it runs once or is the inner input of a nested
 * loop. The bound — the asked codes times the subject's roles — is read from the plan's shape instead: every scan is an
 * index scan by condition, and the only `Filter` is that `EXISTS` on the rows the unique code index returned.
 */
@Tag("integration")
class PermissionCheckBoundedCostIT {
    private val db = AccessDatabase.fresh("access_permission_cost")

    @Test
    fun `a permission question is answered by index conditions over the asked codes and the subject's own grants`() {
        val subject = SubjectRef(AGENT, UUID.randomUUID())

        val plan = db.plan(db.grants.heldCodesQuery(subject, setOf("ticket.read", "ticket.close")))

        ACCESS_TABLES.forEach { assertThat(plan.scansSequentially(it)).describedAs("a sequential scan of $it:\n$plan").isFalse() }
        listOf("uq_permissions_code", "pk_subject_permissions", "pk_subject_roles").forEach {
            assertThat(plan.usesIndex(it)).describedAs("uses $it:\n$plan").isTrue()
        }
        val scans = PlanShape.nodes(plan).filter { it.has("Index Name") }
        scans.forEach { scan ->
            val index = scan.get("Index Name").asString()
            assertThat(scan.has("Index Cond")).describedAs("$index reads by condition:\n$plan").isTrue()
            val filter = scan.get("Filter")?.asString() ?: return@forEach
            // The one filter allowed: a correlated EXISTS over the subject's own grants, evaluated on the rows a lookup of the
            // unique code index by the asked codes returned — at most one per asked code. The scans inside that SubPlan are
            // themselves in `scans` and read by index conditions only (the subject's roles, at most max-roles-per-subject).
            assertThat(index).describedAs("$index filters rows it read:\n$plan").isEqualTo("uq_permissions_code")
            assertThat(scan.get("Index Cond").asString()).describedAs("the lookup is by the asked codes:\n$plan").contains("= ANY")
            assertThat(filter).describedAs("only an EXISTS over the subject's grants:\n$plan").matches("EXISTS\\(SubPlan \\d+\\)")
        }
    }
}

/** Gap 22: a subject's sessions are a keyset page of `ix_sessions_live`, bounded by plan criterion v3. */
@Tag("integration")
class SessionPagesKeysetIT {
    private val db = AccessDatabase.fresh("access_session_pages")
    private val subject = SubjectRef(AGENT, UUID.randomUUID())

    @Test
    fun `the first page and every next one is a keyset read of ix_sessions_live`() {
        listOf(null, SessionCursor(START.minusSeconds(60), UUID.randomUUID())).forEach { after ->
            db.assertBounded(
                Statement(
                    "a page of live sessions",
                    db.sessions.livePageQuery(subject, after, 51),
                    "ix_sessions_live",
                    "sessions",
                ),
            )
        }
    }

    @Test
    fun `pages list only open, unexpired, recently used sessions, newest first, each once, reading no more than a page each`() {
        val live = List(5) { db.openSession(subject, START.minus(Duration.ofHours(10L - it))) }
        db.openSession(subject, START.minus(Duration.ofDays(40)), START.minusSeconds(1))
        db.openSession(subject, START.minus(Duration.ofDays(8)))
        db.openSession(subject, START.minusSeconds(30)).also { db.sessions.close(it, START.minusSeconds(10), "signed-out") }
        db.openSession(SubjectRef(AGENT, UUID.randomUUID()), START.minusSeconds(20))
        val query = SessionsQuery(db.sessions, IDLE_TTL, db.clock)

        val seen = mutableListOf<UUID>()
        var after: SessionCursor? = null
        var pages = 0
        do {
            val page = query.page(subject, after, 2)
            seen.addAll(page.items.map { it.id })
            after = page.next
            pages++
            check(pages <= 10) { "paging never ended" }
        } while (after != null)

        assertThat(seen).containsExactlyElementsOf(live.reversed())
        // Five usable sessions, then the idle and the expired one read by pages of their own: the last page lists nothing.
        assertThat(pages).isEqualTo(4)
    }
}

/** Gap 22: every directory page rain-access serves is a keyset read of an index of its own, bounded by plan criterion v3. */
@Tag("integration")
class DirectoryPagesKeysetIT {
    private val db = AccessDatabase.fresh("access_directory_pages")
    private val subject = SubjectRef(AGENT, UUID.randomUUID())
    private val role = UUID.randomUUID()
    private val since = START.minus(ACCESS_TTL)

    @TestFactory
    fun `every page is a keyset read of its own index`(): List<DynamicTest> {
        val grants = db.grants
        val sessions = db.sessions
        val pages =
            listOf(
                Statement("roles", grants.rolesPageQuery(null, 51), "uq_roles_slug", "roles"),
                Statement("roles after a slug", grants.rolesPageQuery("support-lead", 51), "uq_roles_slug", "roles"),
                Statement("permissions", grants.permissionsPageQuery(null, 51), "uq_permissions_code", "permissions"),
                Statement("permissions after a code", grants.permissionsPageQuery("ticket.read", 51), "uq_permissions_code", "permissions"),
                Statement(
                    "a role's permissions",
                    grants.rolePermissionsPageQuery(role, null, 51),
                    "pk_role_permissions",
                    "role_permissions",
                    "permissions",
                ),
                Statement(
                    "a role's permissions after one",
                    grants.rolePermissionsPageQuery(role, UUID.randomUUID(), 51),
                    "pk_role_permissions",
                    "role_permissions",
                    "permissions",
                ),
                Statement(
                    "a subject's roles",
                    grants.subjectRolesPageQuery(subject, null, 51),
                    "pk_subject_roles",
                    "subject_roles",
                    "roles",
                ),
                Statement(
                    "a subject's roles after one",
                    grants.subjectRolesPageQuery(subject, UUID.randomUUID(), 51),
                    "pk_subject_roles",
                    "subject_roles",
                    "roles",
                ),
                Statement(
                    "a subject's permissions",
                    grants.subjectPermissionsPageQuery(subject, null, 51),
                    "pk_subject_permissions",
                    "subject_permissions",
                    "permissions",
                ),
                Statement(
                    "a subject's permissions after one",
                    grants.subjectPermissionsPageQuery(subject, UUID.randomUUID(), 51),
                    "pk_subject_permissions",
                    "subject_permissions",
                    "permissions",
                ),
                Statement("a role's holders", grants.holdersPageQuery(role, null, 101), "ix_subject_roles_role", "subject_roles"),
                Statement(
                    "a role's holders after one",
                    grants.holdersPageQuery(role, subject, 101),
                    "ix_subject_roles_role",
                    "subject_roles",
                ),
                Statement(
                    "closed sessions to replay",
                    sessions.revokedPageQuery(since, START, null, 500),
                    "ix_sessions_revoked",
                    "sessions",
                ),
                Statement(
                    "closed sessions to replay after a watermark",
                    sessions.revokedPageQuery(since, START, RevokedSession(UUID.randomUUID(), START.minusSeconds(60)), 500),
                    "ix_sessions_revoked",
                    "sessions",
                ),
                Statement(
                    "cutoffs to replay",
                    sessions.cutoffPageQuery(since, START, null, 500),
                    "ix_subject_cutoffs_cutoff",
                    "subject_cutoffs",
                ),
                Statement(
                    "cutoffs to replay after a watermark",
                    sessions.cutoffPageQuery(since, START, SubjectCutoff(subject, START.minusSeconds(60), null), 500),
                    "ix_subject_cutoffs_cutoff",
                    "subject_cutoffs",
                ),
            )
        return pages.map { page -> DynamicTest.dynamicTest(page.name) { db.assertBounded(page) } }
    }
}

/**
 * The statements that closed a subject's sessions, removed a role's holders and permissions, found which holders sign in
 * with a password and read the catalogue's permission ids had no plan proof. Each is now proven by plan criterion v3; two
 * failed it and were rewritten: closing a batch of sessions excluded the kept session with `id <> ?`, a `Filter` on the
 * limited range (the page is now read without it, and without the row lock that kept the partial index's predicate as a
 * `Filter`, and the kept session is left out of a close by primary key); a role's holders and permissions were deleted by
 * their key and their role, which PostgreSQL merged with every holder of the role (they are now deleted by the whole key
 * alone, each row looked up from the limited page); and the password question read a two-column unique key by `= ANY`
 * with no `LIMIT` (it now reads at most one row per id).
 */
@Tag("integration")
class WriteAndLookupStatementsBoundedIT {
    private val db = AccessDatabase.fresh("access_statement_plans")
    private val subject = SubjectRef(AGENT, UUID.randomUUID())
    private val role = UUID.randomUUID()
    private val ids = List(100) { UUID(0, it.toLong()) }
    private val codes = List(500) { "module.permission-$it" }

    @TestFactory
    fun `every statement is bounded by plan criterion v3`(): List<DynamicTest> {
        val sessions = db.sessions
        val statements =
            listOf(
                Statement(
                    "a batch of sessions to close",
                    sessions.revokePageQuery(subject, START, null, 500),
                    "ix_sessions_live",
                    "sessions",
                ),
                Statement(
                    "the next batch of sessions to close",
                    sessions.revokePageQuery(subject, START, SessionCursor(START.minusSeconds(60), UUID.randomUUID()), 500),
                    "ix_sessions_live",
                    "sessions",
                ),
                Statement(
                    "closing a batch of sessions",
                    sessions.closeAllQuery(ids.take(500), START, "signed-out-everywhere"),
                    null,
                    "sessions",
                ),
                Statement("a batch of a role's holders removed", db.grants.revokeHoldersBatchQuery(role, 500), null, "subject_roles"),
                Statement(
                    "a batch of a role's permissions detached",
                    db.grants.detachPermissionsBatchQuery(role, 500),
                    null,
                    "role_permissions",
                ),
                Statement(
                    "which holders have a password",
                    db.credentials.withPasswordQuery(AGENT, ids),
                    "uq_credentials_subject_password",
                    "credentials",
                ),
                Statement("the ids of declared codes", db.catalogue.permissionIdsQuery(codes), "uq_permissions_code", "permissions"),
            )
        return statements.map { statement -> DynamicTest.dynamicTest(statement.name) { db.assertBounded(statement) } }
    }
}
