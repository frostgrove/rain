package com.gd.rain.tenancy.provisioning

import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.control.HmacTenantReferenceDigest
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Tag("integration")
class JooqTenantProvisioningLedgerIT {
    @Test
    fun `lease claims fence stale completion and workflow fingerprints cannot drift within an epoch`() {
        val fixture = Fixture("tenant_provision_ledger")
        val fingerprint = ByteArray(32) { 1 }
        fixture.ledger.ensure(fixture.target, fingerprint, listOf("database"), fixture.clock.instant())
        val first = fixture.ledger.claim(fixture.target, "database", Duration.ofSeconds(1), fixture.clock.instant())
        val busy = fixture.ledger.claim(fixture.target, "database", Duration.ofSeconds(1), fixture.clock.instant())

        assertThat(busy).isEqualTo(TenantProvisioningClaim.Busy("database"))
        fixture.clock.advance(Duration.ofSeconds(2))
        val second = fixture.ledger.claim(fixture.target, "database", Duration.ofSeconds(1), fixture.clock.instant())

        assertThat(
            fixture.ledger.succeed(fixture.target, "database", (first as TenantProvisioningClaim.Acquired).fence, fixture.clock.instant()),
        ).isFalse()
        assertThat(
            fixture.ledger.succeed(fixture.target, "database", (second as TenantProvisioningClaim.Acquired).fence, fixture.clock.instant()),
        ).isTrue()
        assertThat(fixture.ledger.allSucceeded(fixture.target, fingerprint, listOf("database"))).isTrue()
        assertThatThrownBy {
            fixture.ledger.ensure(fixture.target, ByteArray(32) { 2 }, listOf("database"), fixture.clock.instant())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `closed failure is durable and cannot be taken by a later worker`() {
        val fixture = Fixture("tenant_provision_quarantine")
        val fingerprint = ByteArray(32) { 1 }
        fixture.ledger.ensure(fixture.target, fingerprint, listOf("database"), fixture.clock.instant())
        val claim =
            fixture.ledger.claim(
                fixture.target,
                "database",
                Duration.ofSeconds(1),
                fixture.clock.instant(),
            ) as TenantProvisioningClaim.Acquired

        assertThat(fixture.ledger.quarantine(fixture.target, "database", claim.fence, "secret_policy", fixture.clock.instant())).isTrue()
        assertThat(fixture.ledger.claim(fixture.target, "database", Duration.ofSeconds(1), fixture.clock.instant()))
            .isEqualTo(TenantProvisioningClaim.Quarantined("database", "secret_policy"))
        assertThat(fixture.ledger.allSucceeded(fixture.target, fingerprint, listOf("database"))).isFalse()
    }

    private class Fixture(
        prefix: String,
    ) {
        private val database = RainPostgres.freshDatabase(prefix)
        private val dataSource = database.dataSource()
        private val transactions = DataSourceTransactionManager(dataSource)
        val clock = MutableClock(Instant.parse("2026-09-17T12:00:00Z"))
        private val references = HmacTenantReferenceDigest(ByteArray(32) { 7 })
        val target = TenantProvisioningTarget(TenantRef.of("acme"), TenantEpoch(1), 1)
        private val jdbc = JdbcTemplate(dataSource)
        val ledger =
            JooqTenantProvisioningLedger(
                DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
                TransactionTemplate(transactions),
                references,
                tokens = SequenceTokens(),
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
            jdbc.update(
                """
                INSERT INTO rain_tenancy.tenant (
                  ref_digest, lifecycle, epoch, placement_version, row_version, created_at, updated_at
                ) VALUES (?, 'provisioning', 1, 1, 1, ?, ?)
                """.trimIndent(),
                references.digest(target.ref).copy(),
                java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()),
            )
        }
    }

    private class SequenceTokens : () -> UUID {
        private var next: Long = 1

        override fun invoke(): UUID = UUID(0, next++)
    }
}
