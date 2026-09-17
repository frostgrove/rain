package com.gd.rain.tenancy.event

import com.gd.rain.event.AppendResult
import com.gd.rain.event.EncodedChanges
import com.gd.rain.event.EncodedFact
import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventMetadata
import com.gd.rain.event.FactType
import com.gd.rain.event.OperationKey
import com.gd.rain.event.StreamRef
import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.tenancy.CompositeTenantResolver
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantGrantVerifier
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.control.HmacTenantReferenceDigest
import com.gd.rain.tenancy.sharedrow.SharedRowTenantDataPlane
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Instant

/** The tenancy-event adapter must use a tenant unit's transaction instead of a global event backing. */
@Tag("integration")
class TenantEventStoreFactoryIT {
    @Test
    fun `same aggregate key in two shared-row tenant units creates separate event streams`() {
        val fixture = Fixture()
        val factory = TenantEventStoreFactory()
        val namespaces = listOf("acme", "other").map { ref -> fixture.append(factory, ref) }

        assertThat(namespaces).doesNotHaveDuplicates()
        assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("rain_event", "event_stream")))).isEqualTo(2)
    }

    @Test
    fun `event store captured from a completed tenant unit cannot attach to a later ordinary transaction`() {
        val fixture = Fixture()
        lateinit var store: PostgresEventStore

        fixture.plane.write(fixture.scope("acme")) { unit ->
            store = TenantEventStoreFactory().forUnit(unit).store
        }

        assertThatThrownBy { fixture.ordinary { store.inCallerTransaction { } } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no longer current")
    }

    private class Fixture {
        private val database = RainPostgres.freshDatabase("tenant_event")
        private val dataSource = database.dataSource()
        private val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        private val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
        private val refs =
            listOf("acme", "other").associate { ref ->
                TenantRef.of(ref) to TenantResolution(TenantRef.of(ref), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
            }
        private val authority =
            HmacTenantAuthority(
                "test",
                CompositeTenantResolver(emptyList()) { refs[it] },
                TenantAdmission.DEFAULT,
                ByteArray(32) { 1 },
                clock = clock,
                random = SecureRandom(),
            )
        val plane =
            SharedRowTenantDataPlane(
                dsl,
                transactions,
                authority,
                HmacTenantReferenceDigest(ByteArray(32) { 2 }),
                TenantGrantVerifier { _, _, _ -> error("this test has no fleet grant") },
                clock,
            )

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

        fun append(
            factory: TenantEventStoreFactory,
            ref: String,
        ) = plane.write(authority.lookup(TenantRef.of(ref), TenantOperation.WRITE)) { unit ->
            val tenantEvent = factory.forUnit(unit)
            val stream = StreamRef(tenantEvent.namespace, "counter", "same")
            val result =
                tenantEvent.store.inCallerTransaction { transaction ->
                    tenantEvent.store.append(
                        transaction,
                        expectedVersion = 0,
                        changes = EncodedChanges(stream, listOf(EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("1")))),
                        metadata = EventMetadata(OperationKey.of("tenant-$ref")),
                    )
                }
            assertThat(result).isInstanceOf(AppendResult.Committed::class.java)
            tenantEvent.namespace
        }

        fun scope(ref: String) = authority.lookup(TenantRef.of(ref), TenantOperation.WRITE)

        fun <T> ordinary(block: () -> T): T = checkNotNull(TransactionTemplate(transactions).execute { block() })
    }
}
