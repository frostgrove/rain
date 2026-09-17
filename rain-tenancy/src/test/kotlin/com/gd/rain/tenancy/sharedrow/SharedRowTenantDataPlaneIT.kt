package com.gd.rain.tenancy.sharedrow

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
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Instant

/** The shared-row boundary must install and clear tenant GUCs before a caller sees its DSL context. */
@Tag("integration")
class SharedRowTenantDataPlaneIT {
    @Test
    fun `one scoped unit installs transaction-local tenant settings and exposes its transaction authority`() {
        val fixture = Fixture("shared_row_settings")
        val scope = fixture.scope("acme")
        val hints = mutableListOf<String>()

        fixture.plane.write(scope) { unit ->
            assertThat(unit.operation).isEqualTo(TenantOperation.WRITE)
            assertThat(unit.transactions.backing).isEqualTo(unit.backing)
            assertThat(setting(unit.dsl, SharedRowTenantDataPlane.REF_DIGEST_GUC)).isEqualTo(fixture.digest("acme"))
            assertThat(setting(unit.dsl, SharedRowTenantDataPlane.EPOCH_GUC)).isEqualTo("1")
            assertThat(setting(unit.dsl, SharedRowTenantDataPlane.OPERATION_GUC)).isEqualTo("write")
            unit.afterCommit { hints += "committed" }
        }

        assertThat(hints).containsExactly("committed")
        assertThat(setting(fixture.dsl, SharedRowTenantDataPlane.REF_DIGEST_GUC)).isNull()
    }

    @Test
    fun `a second tenant cannot switch an entered unit and an ordinary transaction cannot become tenant work`() {
        val fixture = Fixture("shared_row_pin")
        val first = fixture.scope("acme")
        val second = fixture.scope("other")

        fixture.plane.write(first) {
            assertThatThrownBy { fixture.plane.write(second) { } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("pinned")
        }
        fixture.transactions.executeWithoutResult {
            assertThatThrownBy { fixture.plane.write(first) { } }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("unscoped transaction")
        }
    }

    private class Fixture(
        prefix: String,
    ) {
        private val database = RainPostgres.freshDatabase(prefix)
        private val dataSource = database.dataSource()
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        private val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
        private val refs =
            mapOf(
                TenantRef.of("acme") to TenantResolution(TenantRef.of("acme"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1),
                TenantRef.of("other") to TenantResolution(TenantRef.of("other"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1),
            )
        private val authority =
            HmacTenantAuthority(
                "test",
                CompositeTenantResolver(emptyList()) { refs[it] },
                TenantAdmission.DEFAULT,
                ByteArray(32) { 1 },
                clock = clock,
                random = SecureRandom(),
            )
        private val index = HmacTenantReferenceDigest(ByteArray(32) { 7 })
        val plane =
            SharedRowTenantDataPlane(
                dsl,
                requireNotNull(transactions.transactionManager),
                authority,
                index,
                TenantGrantVerifier { _, _, _ -> error("this test has no fleet grant") },
                clock,
            )

        fun scope(ref: String) = authority.lookup(TenantRef.of(ref), TenantOperation.WRITE)

        fun digest(ref: String): String =
            java.util.Base64
                .getEncoder()
                .withoutPadding()
                .encodeToString(index.digest(TenantRef.of(ref)).copy())
    }

    private companion object {
        fun setting(
            dsl: org.jooq.DSLContext,
            name: String,
        ): String? = dsl.fetchValue("SELECT current_setting('$name', true)", String::class.java) as String?
    }
}
