package com.gd.rain.persistence.tx

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.Configuration
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.ResourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Transaction-placement proofs are opaque, backing-fenced, and available only in a caller-owned unit. */
class TransactionPlacementTest {
    @Test
    fun `named backings are deterministic, namespaced, and bounded without exposing their names`() {
        val primary = BackingIdentity.named("catalog", "primary")

        assertThat(primary)
            .isEqualTo(BackingIdentity.named("catalog", "primary"))
            .isNotEqualTo(BackingIdentity.named("catalog", "replica"))
            .isNotEqualTo(BackingIdentity.named("tenant", "primary"))
        assertThat(primary.diagnosticDigest).hasSize(16).doesNotContain("primary")
        assertThat(primary.toString()).isEqualTo("backing:${primary.diagnosticDigest}")

        assertThatThrownBy { BackingIdentity.named("Bad", "primary") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { BackingIdentity.named("catalog", " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { BackingIdentity.named("catalog", "я".repeat(257)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `same transaction authority requires one active holder on one backing`() {
        val primary = BackingIdentity.named("catalog", "primary")
        val replica = BackingIdentity.named("catalog", "replica")
        val holder = Any()
        val authority = TransactionAuthority.of(primary, holder)
        val sameAuthority = TransactionAuthority.of(primary, holder)
        val otherAuthority = TransactionAuthority.of(primary, Any())

        assertThat(authority).isEqualTo(sameAuthority).isNotEqualTo(otherAuthority).isNotEqualTo("authority")
        assertThat(authority.toString()).isEqualTo("transaction:${primary.diagnosticDigest}")
        assertThat(SameTransactionAuthority.require(authority, sameAuthority)).isEqualTo(authority)
        assertThatThrownBy { SameTransactionAuthority.require(authority, otherAuthority) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("transaction authorities differ")

        val attached = TransactionPlacement(primary, authority)
        assertThat(SameTransactionAuthority.require(attached, TransactionPlacement(primary, sameAuthority))).isEqualTo(authority)
        assertThatThrownBy { TransactionPlacement(primary, null).requireAuthority() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("a caller-owned transaction is required")
        assertThatThrownBy { SameTransactionAuthority.require(attached, TransactionPlacement(replica, authority)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("transaction backings differ")
        assertThatThrownBy { SameTransactionAuthority.require(attached, TransactionPlacement(primary, otherAuthority)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("transaction authorities differ")
    }

    @Test
    fun `inspection reads only the caller bound Spring resource`() {
        val dsl = configuredDsl()
        val transactions = SyntheticTransactions()

        val outside = TransactionPlacement.inspect(dsl, transactions)
        assertThat(outside.authority).isNull()
        assertThat(outside.backing).isEqualTo(TransactionPlacement.inspect(dsl, transactions).backing)

        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThat(TransactionPlacement.inspect(dsl, transactions).authority).isNull()

            TransactionSynchronizationManager.bindResource(transactions.resource, transactions.holder)
            val inside = TransactionPlacement.inspect(dsl, transactions)
            assertThat(inside.authority).isNotNull()
            assertThat(inside.authority).isEqualTo(TransactionPlacement.inspect(dsl, transactions).authority)
            assertThat(inside.requireAuthority().backing).isEqualTo(outside.backing)
        } finally {
            TransactionSynchronizationManager.clear()
        }
    }

    @Test
    fun `inspection refuses a manager without a resource factory`() {
        assertThatThrownBy { TransactionPlacement.inspect(configuredDsl(), RefusingTransactions) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("transaction manager does not expose a resource factory")
    }

    private fun configuredDsl(): DSLContext =
        mockk {
            every { configuration() } returns mockk<Configuration>()
        }

    private class SyntheticTransactions : ResourceTransactionManager {
        val resource: Any = Any()
        val holder: Any = Any()

        override fun getResourceFactory(): Any = resource

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = error("a caller owns the transaction")

        override fun commit(status: TransactionStatus) = error("a caller owns the transaction")

        override fun rollback(status: TransactionStatus) = error("a caller owns the transaction")
    }

    private object RefusingTransactions : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = error("not called")

        override fun commit(status: TransactionStatus) = error("not called")

        override fun rollback(status: TransactionStatus) = error("not called")
    }
}
