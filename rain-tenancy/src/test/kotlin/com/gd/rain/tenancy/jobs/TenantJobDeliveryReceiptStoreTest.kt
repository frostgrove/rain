package com.gd.rain.tenancy.jobs

import com.gd.rain.jobs.EnqueueOutcome
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolver
import com.gd.rain.tenancy.control.HmacTenantReferenceDigest
import com.gd.rain.tenancy.control.TenantReferenceDigest
import com.gd.rain.test.MutableClock
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
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@Tag("integration")
class TenantJobDeliveryReceiptStoreTest {
    @Test
    fun `receipt commits with one enqueue and exact replay returns its original invocation`() {
        val fixture = Fixture("tenant_job_receipt")
        var calls = 0
        val first =
            fixture.store.deliver(fixture.scope, fixture.entry(1)) {
                calls += 1
                EnqueueOutcome.Scheduled(UUID(0, 10))
            }
        val replayed =
            fixture.store.deliver(fixture.scope, fixture.entry(1)) {
                calls += 1
                EnqueueOutcome.Scheduled(UUID(0, 11))
            }

        assertThat(first).isEqualTo(TenantJobDeliveryReceiptOutcome.Dispatched(UUID(0, 10)))
        assertThat(replayed).isEqualTo(TenantJobDeliveryReceiptOutcome.Repeated(UUID(0, 10)))
        assertThat(calls).isEqualTo(1)
        assertThat(fixture.count()).isEqualTo(1)
        assertThat(fixture.jdbc.queryForObject("SELECT invocation_id FROM rain_tenancy.tenant_delivery_receipt", UUID::class.java))
            .isEqualTo(UUID(0, 10))
    }

    @Test
    fun `collision and failed enqueue leave the source unacknowledged for a later safe relay`() {
        val fixture = Fixture("tenant_job_receipt_collision")
        fixture.store.deliver(fixture.scope, fixture.entry(1)) { EnqueueOutcome.Scheduled(UUID(0, 10)) }

        assertThat(
            fixture.store.deliver(fixture.scope, fixture.entry(1, definition = "notes.delete")) {
                EnqueueOutcome.Scheduled(UUID(0, 11))
            },
        ).isEqualTo(TenantJobDeliveryReceiptOutcome.Collision)
        assertThat(fixture.count()).isEqualTo(1)

        assertThatThrownBy {
            fixture.store.deliver(fixture.scope, fixture.entry(2)) { error("jobs database unavailable") }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.count()).isEqualTo(1)
    }

    private class Fixture(
        prefix: String,
    ) {
        private val database = RainPostgres.freshDatabase(prefix)
        private val dataSource = database.dataSource()
        private val transactions = DataSourceTransactionManager(dataSource)
        private val clock = MutableClock(Instant.parse("2026-09-17T12:00:00Z"))
        private val ref = TenantRef.of("acme")
        private val resolver = Resolver(TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(1), 1))
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
        private val references: TenantReferenceDigest = HmacTenantReferenceDigest(ByteArray(32) { 3 })
        private val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        val jdbc = JdbcTemplate(dataSource)
        val scope = authority.lookup(ref, TenantOperation.ADMIN)
        val store = JooqTenantJobDeliveryReceiptStore(dsl, TransactionTemplate(transactions), references, clock)

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

        fun entry(
            id: Long,
            definition: String = "notes.write",
        ): TenantJobOutboxEntry {
            val order =
                TenantJobOutboxOrder(
                    TenantJobOutboxId(UUID(0, id)),
                    definition,
                    "{\"id\":$id}".toByteArray(),
                    com.gd.rain.jobs
                        .EnqueueOptions(
                            com.gd.rain.jobs.Dedupe.None,
                            com.gd.rain.jobs
                                .JobPriority(0),
                        ),
                )
            return TenantJobOutboxEntry(
                order.id,
                TenantEpoch(1),
                definition,
                order.serializedPayload(),
                order.payloadDigest,
                order.options,
                clock.instant(),
                clock.instant(),
            )
        }

        fun count(): Int = jdbc.queryForObject("SELECT count(*) FROM rain_tenancy.tenant_delivery_receipt", Int::class.java)!!
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
