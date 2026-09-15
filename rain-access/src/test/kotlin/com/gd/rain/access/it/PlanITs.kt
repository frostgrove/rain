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
import com.gd.rain.access.support.ScalePlans
import com.gd.rain.access.support.openSession
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

private fun AccessDatabase.plan(query: Query): QueryPlan = QueryPlans.explain(dataSource, dsl.renderInlined(query), generic = false)

private fun AccessDatabase.pagePlan(query: Query): QueryPlan = ScalePlans.explain(dataSource, dsl.renderInlined(query))

private class Page(
    val name: String,
    val index: String,
    val query: Query,
    val conditioned: Boolean = true,
)

private val ACCESS_TABLES = listOf("permissions", "roles", "role_permissions", "subject_roles", "subject_permissions")

/** Gap 22: the per-request question reads the asked codes and the subject's own grants through indexes, whatever the tables hold. */
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

/** Gap 22: a subject's sessions are a keyset page of `ix_sessions_live`. */
@Tag("integration")
class SessionPagesKeysetIT {
    private val db = AccessDatabase.fresh("access_session_pages")
    private val subject = SubjectRef(AGENT, UUID.randomUUID())

    @Test
    fun `the first page and every next one is a keyset read of ix_sessions_live`() {
        listOf(null, SessionCursor(START.minusSeconds(60), UUID.randomUUID())).forEach { after ->
            PlanShape.assertKeysetPage(
                db.pagePlan(db.sessions.livePageQuery(subject, START, START.minus(IDLE_TTL), after, 51)),
                "ix_sessions_live",
            )
        }
    }

    @Test
    fun `pages list only open, unexpired, recently used sessions, newest first, each once`() {
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
        assertThat(pages).isEqualTo(3)
    }
}

/** Gap 22: every directory page rain-access serves is a keyset read of an index of its own. */
@Tag("integration")
class DirectoryPagesKeysetIT {
    private val db = AccessDatabase.fresh("access_directory_pages")
    private val subject = SubjectRef(AGENT, UUID.randomUUID())
    private val role = UUID.randomUUID()
    private val since = START.minus(ACCESS_TTL)

    @TestFactory
    fun `every page is a keyset read of its own index`(): List<DynamicTest> {
        val pages =
            listOf(
                Page("roles", "uq_roles_slug", db.grants.rolesPageQuery(null, 51), conditioned = false),
                Page("roles after a slug", "uq_roles_slug", db.grants.rolesPageQuery("support-lead", 51)),
                Page("permissions", "uq_permissions_code", db.grants.permissionsPageQuery(null, 51), conditioned = false),
                Page("permissions after a code", "uq_permissions_code", db.grants.permissionsPageQuery("ticket.read", 51)),
                Page("a role's permissions", "pk_role_permissions", db.grants.rolePermissionsPageQuery(role, null, 51)),
                Page(
                    "a role's permissions after one",
                    "pk_role_permissions",
                    db.grants.rolePermissionsPageQuery(role, UUID.randomUUID(), 51),
                ),
                Page("a subject's roles", "pk_subject_roles", db.grants.subjectRolesPageQuery(subject, null, 51)),
                Page("a subject's roles after one", "pk_subject_roles", db.grants.subjectRolesPageQuery(subject, UUID.randomUUID(), 51)),
                Page("a subject's permissions", "pk_subject_permissions", db.grants.subjectPermissionsPageQuery(subject, null, 51)),
                Page(
                    "a subject's permissions after one",
                    "pk_subject_permissions",
                    db.grants.subjectPermissionsPageQuery(subject, UUID.randomUUID(), 51),
                ),
                Page("a role's holders", "ix_subject_roles_role", db.grants.holdersPageQuery(role, null, 101)),
                Page("a role's holders after one", "ix_subject_roles_role", db.grants.holdersPageQuery(role, subject, 101)),
                Page("closed sessions to replay", "ix_sessions_revoked", db.sessions.revokedPageQuery(since, START, null, 500)),
                Page(
                    "closed sessions to replay after a watermark",
                    "ix_sessions_revoked",
                    db.sessions.revokedPageQuery(since, START, RevokedSession(UUID.randomUUID(), START.minusSeconds(60)), 500),
                ),
                Page("cutoffs to replay", "ix_subject_cutoffs_cutoff", db.sessions.cutoffPageQuery(since, START, null, 500)),
                Page(
                    "cutoffs to replay after a watermark",
                    "ix_subject_cutoffs_cutoff",
                    db.sessions.cutoffPageQuery(since, START, SubjectCutoff(subject, START.minusSeconds(60), null), 500),
                ),
            )
        return pages.map { page ->
            DynamicTest.dynamicTest(page.name) { PlanShape.assertKeysetPage(db.pagePlan(page.query), page.index, page.conditioned) }
        }
    }
}
