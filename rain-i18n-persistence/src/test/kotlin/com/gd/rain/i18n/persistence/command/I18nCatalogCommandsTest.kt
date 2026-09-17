package com.gd.rain.i18n.persistence.command

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.persistence.CatalogReleaseCommand
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.CatalogReleaseStore
import com.gd.rain.i18n.persistence.CatalogReleaseTransaction
import com.gd.rain.i18n.persistence.DurableCatalogCurrentLoad
import com.gd.rain.i18n.persistence.DurableCatalogHead
import com.gd.rain.i18n.persistence.DurableCatalogTransition
import com.gd.rain.persistence.tx.BackingIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.UUID

class I18nCatalogCommandsTest {
    private val scope = CatalogReleaseScope("application")
    private val reference = CatalogRef("release-1", Digest.parse("a".repeat(64)))
    private val head = DurableCatalogHead(scope, reference, 7)
    private val transaction = CatalogReleaseTransaction(UUID.randomUUID(), BackingIdentity.named("test", "i18n-command"), UUID.randomUUID())
    private val transactions = ImmediateTransactions

    @Test
    fun `status reports a missing configured scope as a successful bounded observation`() {
        val store = mockk<CatalogReleaseStore>()
        every { store.inCallerTransaction<DurableCatalogCurrentLoad>(any()) } answers {
            firstArg<(CatalogReleaseTransaction) -> DurableCatalogCurrentLoad>()(transaction)
        }
        every { store.current(transaction, scope) } returns DurableCatalogCurrentLoad.Missing
        val output = capturedOutput()

        val exit = I18nStatusCommand(store, scope, transactions).run(arguments(), output.command)

        assertThat(exit).isZero()
        assertThat(output.stdout()).isEqualTo("i18n-status: scope=application head=missing\n")
        assertThat(output.stderr()).isEmpty()
    }

    @Test
    fun `activate refuses a partial expected token before it reads or mutates anything`() {
        val output = capturedOutput()

        val exit =
            I18nActivateCommand(mockk(), scope, transactions).run(
                arguments("--artifact=/not-read", "--expected-revision=release-1", "--actor=operator", "--operation=deploy"),
                output.command,
            )

        assertThat(exit).isEqualTo(1)
        assertThat(output.stdout()).isEmpty()
        assertThat(output.stderr()).contains("state --create or all expected-head fields")
    }

    @Test
    fun `activate forwards an explicit create audit command and only publishes a regular artifact`() {
        val artifact = Files.createTempFile("rain-i18n-command-", ".json")
        Files.write(artifact, byteArrayOf(1, 2, 3))
        try {
            val store = mockk<CatalogReleaseStore>()
            val submitted = slot<CatalogReleaseCommand>()
            every { store.inCallerTransaction<DurableCatalogTransition>(any()) } answers {
                firstArg<(CatalogReleaseTransaction) -> DurableCatalogTransition>()(transaction)
            }
            every { store.publishAndActivate(transaction, capture(submitted)) } returns DurableCatalogTransition.Updated(head)
            val output = capturedOutput()

            val exit =
                I18nActivateCommand(store, scope, transactions).run(
                    arguments("--artifact=$artifact", "--create", "--actor=operator", "--operation=deploy"),
                    output.command,
                )

            assertThat(exit).isZero()
            assertThat(submitted.captured.scope).isEqualTo(scope)
            assertThat(submitted.captured.expected).isNull()
            assertThat(submitted.captured.actor.value).isEqualTo("operator")
            assertThat(submitted.captured.operation.value).isEqualTo("deploy")
            assertThat(submitted.captured.artifact.artifactBytes()).containsExactly(1, 2, 3)
            assertThat(
                output.stdout(),
            ).isEqualTo("i18n-activate: updated scope=application revision=release-1 digest=${reference.digest.hex} version=7\n")
            assertThat(output.stderr()).isEmpty()
        } finally {
            Files.deleteIfExists(artifact)
        }
    }

    @Test
    fun `rollback sends exact current and target tokens rather than loading an ambient head`() {
        val target = CatalogRef("release-0", Digest.parse("b".repeat(64)))
        val store = mockk<CatalogReleaseStore>()
        val submitted = slot<com.gd.rain.i18n.persistence.CatalogRollbackCommand>()
        every { store.inCallerTransaction<DurableCatalogTransition>(any()) } answers {
            firstArg<(CatalogReleaseTransaction) -> DurableCatalogTransition>()(transaction)
        }
        every { store.rollback(transaction, capture(submitted)) } returns
            DurableCatalogTransition.Updated(DurableCatalogHead(scope, target, 8))
        val output = capturedOutput()

        val exit =
            I18nRollbackCommand(store, scope, transactions).run(
                arguments(
                    "--expected-revision=release-1",
                    "--expected-digest=${reference.digest.hex}",
                    "--expected-version=7",
                    "--target-revision=release-0",
                    "--target-digest=${target.digest.hex}",
                    "--actor=operator",
                    "--operation=rollback-42",
                ),
                output.command,
            )

        assertThat(exit).isZero()
        assertThat(submitted.captured.expected).isEqualTo(head)
        assertThat(submitted.captured.target).isEqualTo(target)
        assertThat(output.stdout()).contains("revision=release-0", "version=8")
    }

    private fun arguments(vararg values: String): DefaultApplicationArguments = DefaultApplicationArguments(*values)

    private fun capturedOutput(): CapturedOutput {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        return CapturedOutput(CommandOutput(PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8)), out, err)
    }

    private data class CapturedOutput(
        val command: CommandOutput,
        val out: ByteArrayOutputStream,
        val err: ByteArrayOutputStream,
    ) {
        fun stdout(): String = out.toString(Charsets.UTF_8)

        fun stderr(): String = err.toString(Charsets.UTF_8)
    }

    private object ImmediateTransactions : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T = action.doInTransaction(SimpleTransactionStatus())
    }
}
