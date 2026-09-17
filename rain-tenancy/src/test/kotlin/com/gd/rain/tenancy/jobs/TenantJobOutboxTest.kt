package com.gd.rain.tenancy.jobs

import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.persistence.tx.TransactionPlacement
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolver
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.TenantUnit
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Tag("integration")
class TenantJobOutboxTest {
    @Test
    fun `source row is idempotent, bounded, and delayed from its original tenant transaction`() {
        val fixture = Fixture("tenant_job_outbox_source")
        val order = fixture.order(1, "{\"id\":1}", after = Duration.ofMinutes(1))

        fixture.inUnit(TenantOperation.WRITE) { unit ->
            assertThat(fixture.outbox.enqueue(unit, order)).isInstanceOf(TenantJobOutboxEnqueueResult.Queued::class.java)
            assertThat(fixture.outbox.enqueue(unit, order)).isInstanceOf(TenantJobOutboxEnqueueResult.Repeated::class.java)
            assertThat(fixture.outbox.enqueue(unit, fixture.order(1, "{\"id\":2}")))
                .isEqualTo(TenantJobOutboxEnqueueResult.Collision)
            assertThat(fixture.outbox.pending(unit, TenantJobOutboxPage(10)).entries).isEmpty()
        }

        fixture.clock.advance(Duration.ofMinutes(1))
        fixture.inUnit(TenantOperation.WRITE) { unit ->
            val batch = fixture.outbox.pending(unit, TenantJobOutboxPage(10))

            val entry = batch.entries.single()
            assertThat(entry.id).isEqualTo(order.id)
            assertThat(entry.definition).isEqualTo("notes.write")
            assertThat(entry.notBefore).isEqualTo(fixture.clock.instant())
            assertThat(batch.next).isNotNull()
            assertThat(fixture.outbox.pending(unit, TenantJobOutboxPage(10, batch.next)).entries).isEmpty()
        }
    }

    @Test
    fun `acknowledgement is source-fenced and cannot redirect a delivered outbox row`() {
        val fixture = Fixture("tenant_job_outbox_ack")
        val order = fixture.order(1, "{\"id\":1}")
        lateinit var entry: TenantJobOutboxEntry
        val invocation = UUID(0, 7)

        fixture.inUnit(TenantOperation.DURABLE) { unit ->
            entry = (fixture.outbox.enqueue(unit, order) as TenantJobOutboxEnqueueResult.Queued).entry
            assertThat(fixture.outbox.acknowledge(unit, entry, invocation)).isEqualTo(TenantJobOutboxAcknowledgeResult.Acknowledged)
            assertThat(fixture.outbox.acknowledge(unit, entry, invocation)).isEqualTo(TenantJobOutboxAcknowledgeResult.Repeated)
            assertThat(fixture.outbox.acknowledge(unit, entry, UUID(0, 8))).isEqualTo(TenantJobOutboxAcknowledgeResult.Collision)
        }

        fixture.inUnit(TenantOperation.READ) { unit ->
            assertThatThrownBy { fixture.outbox.pending(unit, TenantJobOutboxPage(1)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("writable")
        }
    }

    private class Fixture(
        prefix: String,
    ) {
        private val database = RainPostgres.freshDatabase(prefix)
        private val dataSource = database.dataSource()
        private val transactions = DataSourceTransactionManager(dataSource)
        val clock = MutableClock(Instant.parse("2026-09-17T12:00:00Z"))
        private val resolver = Resolver(TenantResolution(TenantRef.of("acme"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1))
        private val authority =
            HmacTenantAuthority(
                "test",
                resolver,
                TenantAdmission.DEFAULT,
                ByteArray(32) { 1 },
                identityKey = ByteArray(32) { 2 },
                clock = clock,
                random = SecureRandom(),
            )
        private val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        val outbox: TenantJobOutbox = PostgresTenantJobOutbox()

        init {
            TenantJobOutboxSchema.install(dsl)
        }

        fun order(
            id: Long,
            payload: String,
            after: Duration = Duration.ZERO,
        ): TenantJobOutboxOrder =
            TenantJobOutboxOrder(
                TenantJobOutboxId(UUID(0, id)),
                "notes.write",
                payload.toByteArray(),
                com.gd.rain.jobs
                    .EnqueueOptions(
                        com.gd.rain.jobs.Dedupe
                            .Unique("note:$id"),
                        com.gd.rain.jobs
                            .JobPriority(3),
                        after,
                    ),
            )

        fun inUnit(
            operation: TenantOperation,
            block: (TenantUnit) -> Unit,
        ) {
            checkNotNull(
                TransactionTemplate(transactions).execute {
                    block(JdbcTenantUnit(authority.lookup(TenantRef.of("acme"), operation), operation, dsl, transactions, clock))
                },
            )
        }
    }

    private class JdbcTenantUnit(
        override val scope: TenantScope,
        override val operation: TenantOperation,
        override val dsl: DSLContext,
        private val manager: PlatformTransactionManager,
        override val clock: Clock,
    ) : TenantUnit {
        private val placement: TransactionPlacement get() = TransactionPlacement.inspect(dsl, manager)

        override val backing: BackingIdentity get() = placement.backing
        override val transactions: TransactionAuthority get() = placement.requireAuthority()

        override fun requireCurrentTransaction(): TransactionAuthority = placement.requireAuthority()

        override fun afterCommit(hint: () -> Unit): Unit = error("not used by tenant job outbox tests")
    }

    private class Resolver(
        private val resolution: TenantResolution,
    ) : TenantResolver {
        override fun resolveCurrent(context: com.gd.rain.tenancy.TenantRequestContext): com.gd.rain.tenancy.TenantCandidate =
            com.gd.rain.tenancy.TenantCandidate
                .Present(resolution, "test")

        override fun lookup(ref: TenantRef): TenantResolution? = resolution.takeIf { it.ref == ref }
    }
}
