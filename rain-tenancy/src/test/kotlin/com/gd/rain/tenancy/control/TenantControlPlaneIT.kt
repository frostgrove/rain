package com.gd.rain.tenancy.control

import com.gd.rain.audit.JooqAuditRecorder
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.cache.TenantCacheGeneration
import com.gd.rain.tenancy.provisioning.TenantProvisioningActivationGate
import com.gd.rain.tenancy.settings.TenantSettingCodec
import com.gd.rain.tenancy.settings.TenantSettingSpec
import com.gd.rain.tenancy.settings.TenantSettingsRegistry
import com.gd.rain.tenancy.settings.TenantSettingsVersion
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
import java.time.Instant
import java.util.UUID

/** PostgreSQL proof for lifecycle fencing, operation receipts, and audit atomicity. */
@Tag("integration")
class TenantControlPlaneIT {
    @Test
    fun `a closed lifecycle transition appends receipt and audit evidence in one transaction`() {
        val fixture = Fixture("tenant_control_lifecycle")
        val ref = TenantRef.of("acme")

        val created = fixture.plane.createDraft(TenantCreateDraft(ref, operation(1), placementVersion = 3))
        val active = fixture.plane.activate(command(ref, 1, 2))

        assertThat(created).isEqualTo(
            TenantControlResult.Created(snapshot(ref, TenantLifecycle.PROVISIONING, epoch = 1, placement = 3, version = 1)),
        )
        assertThat(active).isEqualTo(
            TenantControlResult.Transitioned(snapshot(ref, TenantLifecycle.ACTIVE, epoch = 1, placement = 3, version = 2)),
        )
        assertThat(
            fixture.plane.lookup(ref),
        ).isEqualTo(snapshot(ref, TenantLifecycle.ACTIVE, epoch = 1, placement = 3, version = 2).resolution)
        assertThat(fixture.count("rain_tenancy.tenant_transition")).isEqualTo(2)
        assertThat(fixture.count("rain_tenancy.tenant_operation_receipt")).isEqualTo(2)
        assertThat(fixture.count("rain_audit.audit_log")).isEqualTo(2)
        assertThat(
            fixture.jdbc.queryForObject(
                "SELECT count(*) FROM rain_tenancy.tenant WHERE ref_digest = convert_to('acme', 'UTF8')",
                Int::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `a repeated operation returns its first result while a retargeted operation id is refused`() {
        val fixture = Fixture("tenant_control_receipt")
        val ref = TenantRef.of("acme")
        val first = TenantCreateDraft(ref, operation(1))

        val created = fixture.plane.createDraft(first)
        val replayed = fixture.plane.createDraft(first)
        val collision = fixture.plane.createDraft(first.copy(placementVersion = 2))

        assertThat(replayed).isEqualTo(created)
        assertThat(collision).isEqualTo(TenantControlResult.OperationCollision)
        assertThat(fixture.count("rain_tenancy.tenant")).isEqualTo(1)
        assertThat(fixture.count("rain_tenancy.tenant_transition")).isEqualTo(1)
        assertThat(fixture.count("rain_audit.audit_log")).isEqualTo(1)
    }

    @Test
    fun `a stale command is durably replayable without a transition and a forbidden graph edge stays closed`() {
        val fixture = Fixture("tenant_control_refusal")
        val ref = TenantRef.of("acme")
        fixture.plane.createDraft(TenantCreateDraft(ref, operation(1)))
        fixture.plane.activate(command(ref, 1, 2))

        val stale = fixture.plane.makeReadOnly(command(ref, 1, 3))
        val replayed = fixture.plane.makeReadOnly(command(ref, 1, 3))
        val forbidden = fixture.plane.finishMigration(command(ref, 2, 4))

        assertThat(stale).isEqualTo(TenantControlResult.VersionConflict(snapshot(ref, TenantLifecycle.ACTIVE, 1, 1, 2)))
        assertThat(replayed).isEqualTo(stale)
        assertThat(forbidden).isEqualTo(TenantControlResult.TransitionRefused(snapshot(ref, TenantLifecycle.ACTIVE, 1, 1, 2)))
        assertThat(fixture.count("rain_tenancy.tenant_transition")).isEqualTo(2)
        assertThat(fixture.count("rain_audit.audit_log")).isEqualTo(2)
    }

    @Test
    fun `an audit declaration failure rolls back the control mutation and its receipt`() {
        val fixture = Fixture("tenant_control_audit_rollback", declaresAudit = false)

        assertThatThrownBy { fixture.plane.createDraft(TenantCreateDraft(TenantRef.of("acme"), operation(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("is not declared")

        assertThat(fixture.count("rain_tenancy.tenant")).isZero()
        assertThat(fixture.count("rain_tenancy.tenant_operation_receipt")).isZero()
        assertThat(fixture.count("rain_tenancy.tenant_transition")).isZero()
    }

    @Test
    fun `a tombstone restores only through a new epoch and an advancing placement fence`() {
        val fixture = Fixture("tenant_control_restore")
        val ref = TenantRef.of("acme")
        fixture.plane.createDraft(TenantCreateDraft(ref, operation(1)))
        fixture.plane.activate(command(ref, 1, 2))
        fixture.plane.beginDeletion(command(ref, 2, 3))
        fixture.plane.tombstone(command(ref, 3, 4))

        val restored = fixture.plane.restoreAsNewEpoch(TenantRestoreAsNewEpoch(command(ref, 4, 5), placementVersion = 2))

        assertThat(restored).isEqualTo(
            TenantControlResult.Transitioned(snapshot(ref, TenantLifecycle.PROVISIONING, epoch = 2, placement = 2, version = 5)),
        )
        assertThat(fixture.plane.activate(command(ref, 5, 6))).isEqualTo(
            TenantControlResult.Transitioned(snapshot(ref, TenantLifecycle.ACTIVE, epoch = 2, placement = 2, version = 6)),
        )
    }

    @Test
    fun `provisioning activation gate returns a durable incomplete outcome instead of activating early`() {
        val fixture = Fixture("tenant_control_provisioning_gate", activationGate = TenantProvisioningActivationGate { false })
        val ref = TenantRef.of("acme")
        fixture.plane.createDraft(TenantCreateDraft(ref, operation(1)))

        val refused = fixture.plane.activate(command(ref, 1, 2))
        val replayed = fixture.plane.activate(command(ref, 1, 2))

        val expected = TenantControlResult.ProvisioningIncomplete(snapshot(ref, TenantLifecycle.PROVISIONING, 1, 1, 1))
        assertThat(refused).isEqualTo(expected)
        assertThat(replayed).isEqualTo(expected)
        assertThat(fixture.count("rain_tenancy.tenant_transition")).isEqualTo(1)
        assertThat(fixture.count("rain_tenancy.tenant_operation_receipt")).isEqualTo(2)
        assertThat(fixture.count("rain_audit.audit_log")).isEqualTo(1)
    }

    @Test
    fun `runtime settings and cache invalidation use separate CAS receipts and opaque generation advance`() {
        val fixture = Fixture("tenant_runtime_control")
        val ref = TenantRef.of("acme")
        fixture.plane.createDraft(TenantCreateDraft(ref, operation(1)))
        fixture.plane.activate(command(ref, 1, 2))
        val retries = TenantSettingSpec("orders.retries", IntCodec, default = 3) { require(it in 0..10) }
        val registry = TenantSettingsRegistry(listOf(retries))
        val settings =
            TenantRuntimeSettingsCommand(
                runtimeCommand(ref, controlVersion = 2, runtimeVersion = 1, operation = 3),
                registry.patch { set(retries, 4) },
            )

        val changed = fixture.plane.updateRuntimeSettings(settings)
        val replayed = fixture.plane.updateRuntimeSettings(settings)
        val collision =
            fixture.plane.updateRuntimeSettings(
                settings.copy(patch = registry.patch { set(retries, 5) }),
            )
        val invalidated = fixture.plane.invalidateTenantCache(runtimeCommand(ref, 2, 2, 4))

        assertThat(
            changed,
        ).isEqualTo(runtimeSnapshot(epoch = 1, control = 2, cache = 0, settings = 2, runtime = 2).let(TenantRuntimeControlResult::Applied))
        assertThat(replayed).isEqualTo(changed)
        assertThat(collision).isEqualTo(TenantRuntimeControlResult.OperationCollision)
        assertThat(invalidated).isEqualTo(runtimeSnapshot(1, 2, 1, 2, 3).let(TenantRuntimeControlResult::Applied))
        assertThat(fixture.jdbc.queryForObject("SELECT canonical_value FROM rain_tenancy.tenant_runtime_setting", ByteArray::class.java))
            .containsExactly(0, 0, 0, 4)
        assertThat(fixture.count("rain_tenancy.tenant_runtime_operation_receipt")).isEqualTo(2)
        assertThat(fixture.count("rain_audit.audit_log")).isEqualTo(4)
    }

    @Test
    fun `inactive tenant records a replayable runtime refusal without altering settings or cache state`() {
        val fixture = Fixture("tenant_runtime_inactive")
        val ref = TenantRef.of("acme")
        fixture.plane.createDraft(TenantCreateDraft(ref, operation(1)))
        fixture.plane.activate(command(ref, 1, 2))
        fixture.plane.makeReadOnly(command(ref, 2, 3))

        val result = fixture.plane.invalidateTenantCache(runtimeCommand(ref, 3, 1, 4))
        val replayed = fixture.plane.invalidateTenantCache(runtimeCommand(ref, 3, 1, 4))

        assertThat(result).isEqualTo(runtimeSnapshot(1, 3, 0, 1, 1).let(TenantRuntimeControlResult::Inactive))
        assertThat(replayed).isEqualTo(result)
        assertThat(fixture.count("rain_tenancy.tenant_runtime_operation_receipt")).isEqualTo(1)
        assertThat(fixture.count("rain_audit.audit_log")).isEqualTo(3)
    }

    private class Fixture(
        prefix: String,
        declaresAudit: Boolean = true,
        activationGate: TenantProvisioningActivationGate = TenantProvisioningActivationGate.NONE,
    ) {
        private val database = RainPostgres.freshDatabase(prefix)
        private val dataSource = database.dataSource()
        private val transactions = DataSourceTransactionManager(dataSource)
        private val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
        private val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        val jdbc = JdbcTemplate(dataSource)
        private val audit =
            JooqAuditRecorder(
                dsl,
                transactions,
                IdGenerator(UuidV7Ids::next),
                clock,
                currentActor = null,
                types = if (declaresAudit) listOf(TenantControlAudit.TRANSITION, TenantControlAudit.RUNTIME) else emptyList(),
            )
        val plane =
            JooqTenantControlPlane(
                dsl,
                TransactionTemplate(transactions),
                HmacTenantReferenceDigest(ByteArray(32) { 7 }),
                audit,
                IdGenerator(UuidV7Ids::next),
                clock,
                activationGate,
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

        fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
    }

    private companion object {
        fun operation(value: Long): TenantOperationId = TenantOperationId(UUID(0, value))

        fun command(
            ref: TenantRef,
            version: Long,
            operation: Long,
        ): TenantControlCommand = TenantControlCommand(ref, ExpectedTenantControlVersion(version), operation(operation))

        fun runtimeCommand(
            ref: TenantRef,
            controlVersion: Long,
            runtimeVersion: Long,
            operation: Long,
        ): TenantRuntimeControlCommand =
            TenantRuntimeControlCommand(
                ref,
                ExpectedTenantControlVersion(controlVersion),
                ExpectedTenantRuntimeVersion(runtimeVersion),
                operation(operation),
            )

        fun runtimeSnapshot(
            epoch: Long,
            control: Long,
            cache: Long,
            settings: Long,
            runtime: Long,
        ): TenantRuntimeControlSnapshot =
            TenantRuntimeControlSnapshot(
                TenantEpoch(epoch),
                TenantControlVersion(control),
                TenantCacheGeneration(cache),
                TenantSettingsVersion(settings),
                TenantRuntimeVersion(runtime),
            )

        fun snapshot(
            ref: TenantRef,
            lifecycle: TenantLifecycle,
            epoch: Long,
            placement: Long,
            version: Long,
        ): TenantControlSnapshot =
            TenantControlSnapshot(
                com.gd.rain.tenancy
                    .TenantResolution(ref, lifecycle, TenantEpoch(epoch), placement),
                TenantControlVersion(version),
            )
    }

    private object IntCodec : TenantSettingCodec<Int> {
        override val maximumBytes: Int = 4

        override fun encode(value: Int): ByteArray = byteArrayOf(0, 0, 0, value.toByte())

        override fun decode(value: ByteArray): Int = error("not read by control command test")
    }
}
