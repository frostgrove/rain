package com.gd.rain.persistence

import com.gd.rain.core.lock.Exclusively
import com.gd.rain.core.lock.LockKey
import com.gd.rain.core.lock.Sharing
import com.gd.rain.core.lock.keyOf
import com.gd.rain.persistence.id.UuidV7Ids
import com.gd.rain.persistence.jdbc.StatementTimeout
import com.gd.rain.persistence.jdbc.StatementTimeoutAgreementCheck
import com.gd.rain.persistence.lock.AdvisoryLockStore
import com.gd.rain.persistence.lock.AdvisoryLocks
import com.gd.rain.persistence.lock.NestedTransactionNotAllowed
import com.gd.rain.persistence.tx.TransactionRetry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

class PersistenceUnitTest {
    @Test
    fun `every id is a version 7 UUID and ids never go back in time`() {
        val ids = List(2_000) { UuidV7Ids.next() }

        assertThat(ids.map { it.version() }).containsOnly(7)
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(ids.map { it.mostSignificantBits ushr 16 }).isSorted()
    }

    @Test
    fun `a statement timeout is rounded up to whole seconds and never down to unbounded`() {
        assertThat(StatementTimeout(Duration.ofMillis(1)).seconds).isEqualTo(1)
        assertThat(StatementTimeout(Duration.ofMillis(1_001)).seconds).isEqualTo(2)
        assertThat(StatementTimeout(Duration.ofSeconds(30)).seconds).isEqualTo(30)
        assertThatThrownBy { StatementTimeout(Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Boot's own JdbcTemplate timeout has to agree with rain's`() {
        val timeout = StatementTimeout(Duration.ofSeconds(5))

        assertThat(StatementTimeoutAgreementCheck(MockEnvironment(), timeout).problems()).isEmpty()
        assertThat(
            StatementTimeoutAgreementCheck(MockEnvironment().withProperty("spring.jdbc.template.query-timeout", "5s"), timeout).problems(),
        ).isEmpty()
        val contradiction =
            StatementTimeoutAgreementCheck(
                MockEnvironment().withProperty("spring.jdbc.template.query-timeout", "9s"),
                timeout,
            ).problems()
        assertThat(contradiction.single().path).isEqualTo("spring.jdbc.template.query-timeout")
    }

    @Test
    fun `a lock outside a transaction is refused and issues no statement`() {
        val store = RecordingStore()

        assertThatThrownBy { locks(store).take(Exclusively(keyOf("scope", "x"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("outside a transaction")
        assertThat(store.calls).isEmpty()
    }

    @Test
    fun `the lock timeout is set before each wait, in the order the guards are given`() {
        val store = RecordingStore()

        inTransaction { locks(store).take(listOf(Sharing(LockKey(1)), Exclusively(LockKey(2)))) }

        assertThat(store.calls).containsExactly("lock_timeout=250", "shared=1", "lock_timeout=250", "exclusive=2")
    }

    @Test
    fun `guarded inside a transaction is refused before any statement`() {
        val store = RecordingStore()

        assertThatThrownBy { inTransaction { locks(store).guarded(listOf(Exclusively(LockKey(1)))) { } } }
            .isInstanceOf(NestedTransactionNotAllowed::class.java)
        assertThat(store.calls).isEmpty()
    }

    private fun locks(store: AdvisoryLockStore) =
        AdvisoryLocks(
            RefusingTransactionManager,
            store,
            TransactionRetry(1, Duration.ofMillis(1), Duration.ofMillis(1)),
            Duration.ofMillis(250),
        )

    private fun inTransaction(block: () -> Unit) {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            block()
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    private class RecordingStore : AdvisoryLockStore {
        val calls = mutableListOf<String>()

        override fun lockTimeout(timeout: Duration) {
            calls += "lock_timeout=${timeout.toMillis()}"
        }

        override fun statementTimeout(timeout: Duration) {
            calls += "statement_timeout=${timeout.toMillis()}"
        }

        override fun exclusive(key: LockKey) {
            calls += "exclusive=${key.value}"
        }

        override fun shared(key: LockKey) {
            calls += "shared=${key.value}"
        }
    }

    private object RefusingTransactionManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = error("no transaction is opened here")

        override fun commit(status: TransactionStatus) = error("no transaction is opened here")

        override fun rollback(status: TransactionStatus) = error("no transaction is opened here")
    }
}
