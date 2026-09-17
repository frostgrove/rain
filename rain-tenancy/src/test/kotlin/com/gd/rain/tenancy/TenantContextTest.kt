package com.gd.rain.tenancy

import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.SecureRandom
import java.time.Instant

class TenantContextTest {
    @Test
    fun `a transaction remains pinned after the lexical binding closes and releases only on completion`() {
        val authority = authority()
        val first = authority.lookup(TenantRef.of("one"), TenantOperation.WRITE)
        val second = authority.lookup(TenantRef.of("two"), TenantOperation.WRITE)

        syntheticTransaction {
            TenantContext.bind(first).use {}

            assertThatThrownBy { TenantContext.bind(second) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("tenant scope is pinned to another transaction tenant")
        }

        TenantContext.bind(second).use {}
    }

    private fun authority(): TenantAuthority =
        HmacTenantAuthority(
            "test",
            object : TenantResolver {
                private val resolutions =
                    listOf("one", "two").associate { id ->
                        TenantRef.of(id) to TenantResolution(TenantRef.of(id), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
                    }

                override fun resolveCurrent(context: TenantRequestContext): TenantCandidate = TenantCandidate.Absent

                override fun lookup(ref: TenantRef): TenantResolution? = resolutions[ref]
            },
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            identityKey = ByteArray(32) { 2 },
            clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            random = SecureRandom(),
        )

    private fun syntheticTransaction(block: () -> Unit) {
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            block()
        } finally {
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED,
                )
            }
            TransactionSynchronizationManager.clear()
        }
    }
}
