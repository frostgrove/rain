package com.gd.rain.audit

import com.gd.rain.audit.scope.AuditScope
import com.gd.rain.audit.scope.AuditScopeContributor
import com.gd.rain.core.actor.Actor
import com.gd.rain.core.actor.CurrentActor
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.MutableClock
import com.gd.rain.test.PlanVerdict
import com.gd.rain.test.QueryPlans
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Tag("integration")
class AuditIT {
    private val closed = AuditEventType("tickets", "closed", "ticket", setOf("reason"))
    private val signIn = AuditEventType("access", "sign-in", "subject")

    private class Fixture(
        prefix: String,
        types: List<AuditEventType>,
        actor: Actor? = null,
        scopes: List<AuditScopeContributor> = emptyList(),
    ) {
        val database = RainPostgres.freshDatabase(prefix)
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        val recorder =
            JooqAuditRecorder(
                dsl,
                transactions,
                IdGenerator(UuidV7Ids::next),
                clock,
                actor?.let { fixed ->
                    CurrentActor { fixed }
                },
                types,
                scopes,
            )
        val jdbc = JdbcTemplate(dataSource)

        init {
            RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
                .migrate(
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/none")
                        .failOnMissingLocations(false)
                        .load(),
                )
        }

        fun count(): Int = jdbc.queryForObject("SELECT count(*) FROM rain_audit.audit_log", Int::class.java)!!

        fun inTransaction(block: () -> Unit) = TransactionTemplate(transactions).executeWithoutResult { block() }
    }

    @Test
    fun `evidence rolls back with the transaction that made the change`() {
        val fixture = Fixture("audit_rollback", listOf(closed))

        assertThatThrownBy {
            fixture.inTransaction {
                fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1", AuditDetail.of("reason" to "done")))
                error("the change failed after the evidence was written")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.count()).isZero()

        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) }
        assertThat(fixture.count()).isEqualTo(1)
    }

    @Test
    fun `a record outside a transaction is refused and writes nothing`() {
        val fixture = Fixture("audit_notx", listOf(closed))

        assertThatThrownBy {
            fixture.recorder.record(
                AuditEvent(closed, AuditOutcome.OK, "t1"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.count()).isZero()
    }

    @Test
    fun `an independent record survives the rollback of the transaction it was made in`() {
        val fixture = Fixture("audit_independent", listOf(signIn))

        assertThatThrownBy {
            fixture.inTransaction {
                fixture.recorder.recordIndependently(AuditEvent(signIn, AuditOutcome.REFUSED, actor = Actor("service", "importer")))
                error("the sign-in was refused")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(fixture.count()).isEqualTo(1)
    }

    @Test
    fun `any kind of subject is recorded as the actor, with no subject table behind it`() {
        val fixture = Fixture("audit_actor", listOf(closed), actor = Actor("service", "nightly-sweep"))

        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t9")) }

        val page = fixture.recorder.ofActor(Actor("service", "nightly-sweep"), after = null, limit = 10)
        assertThat(page.entries.single().actor).isEqualTo(Actor("service", "nightly-sweep"))
        assertThat(page.next).isNull()
    }

    @Test
    fun `keyset pages over one resource are complete, disjoint and newest first, even with equal timestamps`() {
        val fixture = Fixture("audit_pages", listOf(closed))
        fixture.inTransaction { repeat(7) { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) } }
        fixture.clock.advance(java.time.Duration.ofSeconds(1))
        fixture.inTransaction { repeat(3) { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) } }
        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t2")) }

        val seen = mutableListOf<AuditEntry>()
        var cursor: AuditCursor? = null
        do {
            val page = fixture.recorder.ofResource("ticket", "t1", cursor, limit = 4)
            seen += page.entries
            cursor = page.next
        } while (cursor != null)

        assertThat(seen).hasSize(10)
        assertThat(seen.map { it.id }).doesNotHaveDuplicates()
        assertThat(
            seen.map {
                it.occurredAt to it.id
            },
        ).isSortedAccordingTo(
            compareByDescending<Pair<Instant, java.util.UUID>> { it.first }.thenByDescending { it.second.toString() },
        )
    }

    @Test
    fun `a page limit outside the declared bound is refused`() {
        val fixture = Fixture("audit_limit", listOf(closed))

        assertThatThrownBy {
            fixture.recorder.ofResource(
                "ticket",
                "t1",
                null,
                limit = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            fixture.recorder.ofResource("ticket", "t1", null, limit = AuditRecorder.MAX_PAGE + 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `current scoped audit records and reads are narrowed by opaque epoch-fenced scope`() {
        val current = ThreadLocal<AuditScope?>()
        val contributor = AuditScopeContributor { current.get() }
        val fixture = Fixture("audit_scope", listOf(closed), scopes = listOf(contributor))
        val first = AuditScope.of("tenant", ByteArray(32) { 1 }, 1)
        val second = AuditScope.of("tenant", ByteArray(32) { 2 }, 1)

        current.set(first)
        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) }
        current.set(second)
        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) }
        current.set(first)

        val page = fixture.recorder.ofResource("ticket", "t1", null, 10)

        assertThat(page.entries).hasSize(1)
        assertThat(page.entries.single().scope).isEqualTo(first)
        current.remove()
    }

    @Test
    fun `a configured scope source keeps central evidence separate from scoped evidence`() {
        val current = ThreadLocal<AuditScope?>()
        val fixture = Fixture("audit_central_scope", listOf(closed), scopes = listOf(AuditScopeContributor { current.get() }))
        val scope = AuditScope.of("tenant", ByteArray(32) { 1 }, 1)

        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) }
        current.set(scope)
        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) }

        assertThat(
            fixture.recorder
                .ofResource("ticket", "t1", null, 10)
                .entries
                .map(AuditEntry::scope),
        ).containsExactly(scope)
        current.remove()
        assertThat(
            fixture.recorder
                .ofResource("ticket", "t1", null, 10)
                .entries
                .map(AuditEntry::scope),
        ).containsExactly(null)
    }

    @Test
    fun `equal scope contributors cohere while conflicting contributors refuse the operation`() {
        val first = ThreadLocal<AuditScope?>()
        val second = ThreadLocal<AuditScope?>()
        val fixture =
            Fixture(
                "audit_scope_conflict",
                listOf(closed),
                scopes = listOf(AuditScopeContributor { first.get() }, AuditScopeContributor { second.get() }),
            )
        val scope = AuditScope.of("tenant", ByteArray(32) { 1 }, 1)
        first.set(scope)
        second.set(AuditScope.of("tenant", ByteArray(32) { 1 }, 1))

        fixture.inTransaction { fixture.recorder.record(AuditEvent(closed, AuditOutcome.OK, "t1")) }
        second.set(AuditScope.of("tenant", ByteArray(32) { 2 }, 1))

        assertThatThrownBy { fixture.recorder.ofResource("ticket", "t1", null, 10) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("more than one audit scope is active")
        first.remove()
        second.remove()
    }

    @Test
    fun `a resource page reads through the resource index under a limit`() {
        val fixture = Fixture("audit_plan", listOf(closed))
        val query =
            fixture.recorder.pageQuery(
                com.gd.rain.audit.jooq.Tables.AUDIT_LOG.RESOURCE_KIND
                    .eq(
                        "ticket",
                    ).and(
                        com.gd.rain.audit.jooq.Tables.AUDIT_LOG.RESOURCE_ID
                            .eq("t1"),
                    ),
                AuditCursor(Instant.parse("2026-09-15T10:00:00Z"), java.util.UUID(0, 1)),
                limit = 50,
            )

        val plan = QueryPlans.explain(fixture.dataSource, fixture.dsl.renderInlined(query), generic = false)

        assertThat(plan.usesIndex("ix_audit_log_resource")).describedAs(plan.json).isTrue()
        assertThat(plan.boundedScan("rain_audit", "audit_log")).describedAs(plan.json).isEqualTo(PlanVerdict.Bounded)
    }
}
