package com.gd.rain.i18n.persistence.command

import com.gd.rain.boot.command.CommandOutput
import com.gd.rain.boot.command.RainCommand
import com.gd.rain.boot.runtime.CommandDeclaration
import com.gd.rain.boot.runtime.RuntimeRole
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.CatalogTrustPolicy
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.persistence.CatalogArtifactSubmission
import com.gd.rain.i18n.persistence.CatalogPersistenceLimitReason
import com.gd.rain.i18n.persistence.CatalogReleaseActor
import com.gd.rain.i18n.persistence.CatalogReleaseCommand
import com.gd.rain.i18n.persistence.CatalogReleaseOperation
import com.gd.rain.i18n.persistence.CatalogReleaseScope
import com.gd.rain.i18n.persistence.CatalogReleaseStore
import com.gd.rain.i18n.persistence.CatalogReleaseTransaction
import com.gd.rain.i18n.persistence.CatalogRollbackCommand
import com.gd.rain.i18n.persistence.DurableCatalogCurrentLoad
import com.gd.rain.i18n.persistence.DurableCatalogHead
import com.gd.rain.i18n.persistence.DurableCatalogTransition
import org.springframework.boot.ApplicationArguments
import org.springframework.transaction.support.TransactionOperations
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** `i18n-status`: print the exact durable head of the one explicitly configured catalog scope. */
public class I18nStatusCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "print the configured durable i18n catalog head, then exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = emptyMap()

    public companion object {
        public const val NAME: String = "i18n-status"
    }
}

/** `i18n-activate`: verify one local artifact and atomically publish/activate it under an explicit CAS token. */
public class I18nActivateCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "publish and activate one verified i18n artifact, then exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = emptyMap()

    public companion object {
        public const val NAME: String = "i18n-activate"
    }
}

/** `i18n-rollback`: move the configured scope to a retained release under an exact CAS token. */
public class I18nRollbackCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "roll back the durable i18n head to one retained release, then exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = emptyMap()

    public companion object {
        public const val NAME: String = "i18n-rollback"
    }
}

/** `i18n-prune`: evict only releasable retained releases for the configured scope. */
public class I18nPruneCommandDeclaration : CommandDeclaration {
    override val name: String = NAME
    override val description: String = "prune releasable retained i18n releases, then exit"
    override val roles: Set<RuntimeRole> = emptySet()
    override val properties: Map<String, String> = emptyMap()

    public companion object {
        public const val NAME: String = "i18n-prune"
    }
}

/**
 * Bounded status read. The scope is injected rather than accepted on the command line: a command
 * cannot be redirected to an arbitrary application namespace by a shell argument.
 */
public class I18nStatusCommand(
    store: CatalogReleaseStore,
    scope: CatalogReleaseScope,
    transactions: TransactionOperations,
) : CatalogReleaseRainCommand(store, scope, transactions) {
    override val name: String = I18nStatusCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int =
        execute(output) {
            commandArguments(arguments).only()
            when (val current = transaction { store.current(it, scope) }) {
                is DurableCatalogCurrentLoad.Loaded -> {
                    val head = current.current.head
                    output.out.println("$name: ${headLine(head)}")
                    0
                }

                DurableCatalogCurrentLoad.Missing -> {
                    output.out.println("$name: scope=${scope.value} head=missing")
                    0
                }

                is DurableCatalogCurrentLoad.Refused -> {
                    output.err.println("$name: scope=${scope.value} head=refused reason=${current.reason.name.lowercase()}")
                    1
                }
            }
        }
}

/**
 * Magic command surface over the durable store. It verifies only a regular non-symlink artifact,
 * requires either an explicit existing-head token or explicit `--create`, and never infers actor
 * or audit operation from the OS account/environment.
 */
public class I18nActivateCommand(
    store: CatalogReleaseStore,
    scope: CatalogReleaseScope,
    transactions: TransactionOperations,
) : CatalogReleaseRainCommand(store, scope, transactions) {
    override val name: String = I18nActivateCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int =
        execute(output) {
            val parsed = commandArguments(arguments)
            parsed.only("artifact", "create", "expected-revision", "expected-digest", "expected-version", "actor", "operation")
            val expected = parsed.expected(scope)
            val artifact = CatalogArtifactSubmission(readArtifact(parsed.required("artifact")), CatalogTrustPolicy.LOCAL_BUILD)
            val actor = CatalogReleaseActor(parsed.required("actor"))
            val operation = CatalogReleaseOperation(parsed.required("operation"))
            when (
                val transition =
                    transaction {
                        store.publishAndActivate(
                            it,
                            CatalogReleaseCommand(scope, expected, artifact, actor, operation),
                        )
                    }
            ) {
                is DurableCatalogTransition.Updated -> updated(output, transition.head)
                is DurableCatalogTransition.Conflict -> conflict(output, transition.current)
                is DurableCatalogTransition.Missing -> missing(output, transition.reference)
                is DurableCatalogTransition.Limit -> limit(output, transition.reason)
                is DurableCatalogTransition.Refused -> refused(output, transition.reason.name.lowercase())
            }
        }
}

/** Rollback command with a separately stated target and exact current-head CAS token. */
public class I18nRollbackCommand(
    store: CatalogReleaseStore,
    scope: CatalogReleaseScope,
    transactions: TransactionOperations,
) : CatalogReleaseRainCommand(store, scope, transactions) {
    override val name: String = I18nRollbackCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int =
        execute(output) {
            val parsed = commandArguments(arguments)
            parsed.only(
                "expected-revision",
                "expected-digest",
                "expected-version",
                "target-revision",
                "target-digest",
                "actor",
                "operation",
            )
            val expected = checkNotNull(parsed.expected(scope)) { "--create is not permitted; state the exact expected head" }
            val target = parsed.reference("target-revision", "target-digest")
            val actor = CatalogReleaseActor(parsed.required("actor"))
            val operation = CatalogReleaseOperation(parsed.required("operation"))
            when (
                val transition =
                    transaction {
                        store.rollback(it, CatalogRollbackCommand(scope, expected, target, actor, operation))
                    }
            ) {
                is DurableCatalogTransition.Updated -> updated(output, transition.head)
                is DurableCatalogTransition.Conflict -> conflict(output, transition.current)
                is DurableCatalogTransition.Missing -> missing(output, transition.reference)
                is DurableCatalogTransition.Limit -> limit(output, transition.reason)
                is DurableCatalogTransition.Refused -> refused(output, transition.reason.name.lowercase())
            }
        }
}

/** Bounded prune command. It reports a count rather than an unbounded release listing. */
public class I18nPruneCommand(
    store: CatalogReleaseStore,
    scope: CatalogReleaseScope,
    transactions: TransactionOperations,
) : CatalogReleaseRainCommand(store, scope, transactions) {
    override val name: String = I18nPruneCommandDeclaration.NAME

    override fun run(
        arguments: ApplicationArguments,
        output: CommandOutput,
    ): Int =
        execute(output) {
            val parsed = commandArguments(arguments)
            parsed.only("actor", "operation")
            val actor = CatalogReleaseActor(parsed.required("actor"))
            val operation = CatalogReleaseOperation(parsed.required("operation"))
            val pruned = transaction { store.prune(it, scope, actor, operation) }
            output.out.println("$name: scope=${scope.value} pruned=${pruned.size}")
            0
        }
}

/** Shared transaction, output and argument discipline for the four operational commands. */
public abstract class CatalogReleaseRainCommand(
    protected val store: CatalogReleaseStore,
    protected val scope: CatalogReleaseScope,
    private val transactions: TransactionOperations,
) : RainCommand {
    protected fun <T> transaction(block: (CatalogReleaseTransaction) -> T): T =
        checkNotNull(transactions.execute { store.inCallerTransaction(block) }) { "the catalog transaction returned null" }

    protected fun commandArguments(arguments: ApplicationArguments): CatalogCommandArguments = CatalogCommandArguments(arguments)

    protected fun updated(
        output: CommandOutput,
        head: DurableCatalogHead,
    ): Int {
        output.out.println("$name: updated ${headLine(head)}")
        return 0
    }

    protected fun conflict(
        output: CommandOutput,
        current: DurableCatalogHead?,
    ): Int {
        output.err.println("$name: conflict ${current?.let(::headLine) ?: "head=missing"}")
        return 1
    }

    protected fun missing(
        output: CommandOutput,
        reference: CatalogRef,
    ): Int {
        output.err.println("$name: target=missing ${referenceLine(reference)}")
        return 1
    }

    protected fun limit(
        output: CommandOutput,
        reason: CatalogPersistenceLimitReason,
    ): Int {
        output.err.println("$name: refused reason=${reason.name.lowercase()}")
        return 1
    }

    protected fun refused(
        output: CommandOutput,
        reason: String,
    ): Int {
        output.err.println("$name: refused reason=$reason")
        return 1
    }

    /** Expected user-input problems are concise and never print a raw artifact, principal or exception stack. */
    protected fun execute(
        output: CommandOutput,
        action: () -> Int,
    ): Int =
        try {
            action()
        } catch (failure: CatalogCommandInputException) {
            output.err.println("$name: invalid arguments: ${failure.message}")
            1
        } catch (_: IllegalArgumentException) {
            output.err.println("$name: refused invalid input")
            1
        } catch (_: IllegalStateException) {
            output.err.println("$name: refused store state")
            1
        } catch (_: RuntimeException) {
            output.err.println("$name: failed")
            1
        }

    protected fun readArtifact(raw: String): ByteArray {
        val source =
            try {
                Path.of(raw).toAbsolutePath().normalize()
            } catch (_: RuntimeException) {
                throw CatalogCommandInputException("--artifact is not a valid path")
            }
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw CatalogCommandInputException("--artifact is not a regular non-symlink file")
        }
        val size =
            try {
                Files.size(source)
            } catch (_: RuntimeException) {
                throw CatalogCommandInputException("--artifact cannot be read")
            }
        if (size !in 1..MAX_ARTIFACT_BYTES.toLong()) {
            throw CatalogCommandInputException("--artifact is not 1..$MAX_ARTIFACT_BYTES bytes")
        }
        val bytes =
            try {
                Files.readAllBytes(source)
            } catch (_: RuntimeException) {
                throw CatalogCommandInputException("--artifact cannot be read")
            }
        if (bytes.size.toLong() != size || bytes.size !in 1..MAX_ARTIFACT_BYTES) {
            throw CatalogCommandInputException("--artifact changed while it was read or exceeds $MAX_ARTIFACT_BYTES bytes")
        }
        return bytes
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES: Int = 16 * 1024 * 1024
    }
}

/** Strict command-line parser shared by commands; positional and unknown arguments are always refused. */
public class CatalogCommandArguments(
    private val arguments: ApplicationArguments,
) {
    public fun only(vararg allowed: String) {
        if (arguments.nonOptionArgs.isNotEmpty()) throw CatalogCommandInputException("positional arguments are not supported")
        val unsupported = arguments.optionNames - allowed.toSet()
        if (unsupported.isNotEmpty()) throw CatalogCommandInputException("unsupported option --${unsupported.sorted().first()}")
        arguments.optionNames.forEach { option ->
            val values = arguments.getOptionValues(option).orEmpty()
            if (values.any { it.toByteArray(Charsets.UTF_8).size > MAX_OPTION_BYTES }) {
                throw CatalogCommandInputException("--$option exceeds $MAX_OPTION_BYTES UTF-8 bytes")
            }
        }
    }

    public fun required(name: String): String {
        val values = arguments.getOptionValues(name)
        if (values == null || values.size != 1 || values.single().isBlank()) {
            throw CatalogCommandInputException("state exactly one --$name value")
        }
        return values.single()
    }

    /** Parses either `--create` alone or all three exact expected-head fields, never an implicit current head. */
    public fun expected(scope: CatalogReleaseScope): DurableCatalogHead? {
        val create = arguments.containsOption("create")
        val expectedNames = setOf("expected-revision", "expected-digest", "expected-version")
        val stated = arguments.optionNames.intersect(expectedNames)
        if (create) {
            if (arguments.getOptionValues("create").orEmpty().isNotEmpty() || stated.isNotEmpty()) {
                throw CatalogCommandInputException("use --create alone or state all expected-head fields")
            }
            return null
        }
        if (stated != expectedNames) throw CatalogCommandInputException("state --create or all expected-head fields")
        val reference = reference("expected-revision", "expected-digest")
        val version =
            required("expected-version").toLongOrNull()?.takeIf { it >= 1 }
                ?: throw CatalogCommandInputException("--expected-version is a positive integer")
        return DurableCatalogHead(scope, reference, version)
    }

    public fun reference(
        revisionName: String,
        digestName: String,
    ): CatalogRef {
        val revision = required(revisionName)
        if (revision.length > MAX_REVISION_CHARS || revision.any { it.code !in 0x21..0x7e }) {
            throw CatalogCommandInputException("--$revisionName is 1..128 printable ASCII characters")
        }
        val digest =
            try {
                Digest.parse(required(digestName))
            } catch (_: IllegalArgumentException) {
                throw CatalogCommandInputException("--$digestName is not a canonical SHA-256 digest")
            }
        return CatalogRef(revision, digest)
    }

    private companion object {
        const val MAX_OPTION_BYTES: Int = 4 * 1024
        const val MAX_REVISION_CHARS: Int = 128
    }
}

/** Expected command input refusal, intentionally separate from persistence/trust failures. */
public class CatalogCommandInputException(
    message: String,
) : IllegalArgumentException(message)

private fun referenceLine(reference: CatalogRef): String = "revision=${reference.revision} digest=${reference.digest.hex}"

private fun headLine(head: DurableCatalogHead): String =
    "scope=${head.scope.value} ${referenceLine(head.reference)} version=${head.version}"
