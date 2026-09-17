package com.gd.rain.jobs.context

import com.gd.rain.jobs.JobState
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * The tenant relationship a job declaration asks the durable-context pipeline to preserve.
 *
 * The jobs kernel deliberately owns only this declaration, not a tenant id, authority, resolver,
 * or data source. A tenancy adapter is one [DurableJobContextProvider] among potentially several
 * independent providers.
 */
public enum class TenantBindingMode {
    /** Capture an admitted ambient binding when a provider has one; otherwise run as central work. */
    INHERIT,

    /** Refuse enqueue and delivery unless a provider supplies an eligible durable binding. */
    REQUIRED,

    /** Do not capture or restore any ambient durable binding. */
    CENTRAL,
}

/** SHA-256 of the exact bytes supplied by [com.gd.rain.jobs.JobPayloadCodec.encode] before enqueue. */
public class JobPayloadDigest internal constructor(
    private val value: ByteArray,
) {
    init {
        require(value.size == BYTES) { "a job payload digest has ${value.size} bytes, not $BYTES" }
    }

    /** A defensive copy for a signing adapter; this value never exposes the serialized payload itself. */
    public fun copy(): ByteArray = value.copyOf()

    public override fun toString(): String = "job-payload-digest[redacted]"

    internal fun sameAs(other: ByteArray): Boolean = MessageDigest.isEqual(value, other)

    public companion object {
        internal const val BYTES: Int = 32

        /** Calculates the protocol digest from the exact serialized bytes an enqueue will store. */
        public fun of(serializedPayload: ByteArray): JobPayloadDigest =
            JobPayloadDigest(MessageDigest.getInstance("SHA-256").digest(serializedPayload.copyOf()))

        internal fun stored(value: ByteArray): JobPayloadDigest = JobPayloadDigest(value.copyOf())
    }
}

/** Opaque partition key a provider may attach for a bounded per-producer concurrency policy. */
public class JobProducerPartition private constructor(
    private val value: ByteArray,
) {
    /** A defensive copy for a storage or permit adapter. It is never a raw tenant identifier. */
    public fun copy(): ByteArray = value.copyOf()

    public override fun toString(): String = "job-producer-partition[redacted]"

    public companion object {
        public const val MAX_BYTES: Int = 128

        public fun of(value: ByteArray): JobProducerPartition {
            require(value.size in 1..MAX_BYTES) { "a job producer partition has 1..$MAX_BYTES bytes" }
            return JobProducerPartition(value.copyOf())
        }
    }
}

/** Immutable input signed or otherwise bound by a durable-context provider. */
public data class DurableJobContextRequest(
    /** Stable namespace of Rain's jobs protocol, separate from an application definition name. */
    public val namespace: String,
    public val definition: String,
    public val invocation: UUID,
    public val payloadDigest: JobPayloadDigest,
    public val bindingMode: TenantBindingMode,
) {
    init {
        require(NAMESPACE.matches(namespace)) { "a durable job namespace is not stable" }
        require(NAMESPACE.matches(definition)) { "a durable job definition is not stable" }
    }

    public companion object {
        /** The protocol namespace covered by a tenant token; changing it is a wire incompatibility. */
        public const val JOBS_NAMESPACE: String = "rain.jobs"

        private val NAMESPACE: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** A provider-owned, versioned fragment. Its bytes remain opaque to the jobs kernel. */
public class DurableJobContextFragment private constructor(
    public val version: Int,
    private val value: ByteArray,
) {
    init {
        require(version in 1..MAX_VERSION) { "a durable job context fragment version is 1..$MAX_VERSION" }
    }

    public fun copy(): ByteArray = value.copyOf()

    public override fun toString(): String = "durable-job-context-fragment[v$version, redacted]"

    public companion object {
        public const val MAX_BYTES: Int = 8 * 1024
        public const val MAX_VERSION: Int = Short.MAX_VALUE.toInt()

        public fun of(
            version: Int,
            value: ByteArray,
        ): DurableJobContextFragment {
            require(value.size <= MAX_BYTES) { "a durable job context fragment is at most $MAX_BYTES bytes" }
            return DurableJobContextFragment(version, value.copyOf())
        }
    }
}

/** One provider's capture decision for a newly allocated durable invocation. */
public sealed interface DurableJobContextCapture {
    /** This provider has no ambient state to carry for this enqueue. */
    public data object Absent : DurableJobContextCapture

    public data class Captured(
        public val fragment: DurableJobContextFragment,
        public val producerPartition: JobProducerPartition? = null,
    ) : DurableJobContextCapture
}

/** One provider's independently restoreable part of the stored context envelope. */
public data class DurableJobContextRestoreRequest(
    public val request: DurableJobContextRequest,
    public val fragment: DurableJobContextFragment,
    public val producerPartition: JobProducerPartition?,
)

/**
 * A terminal durable job receipt plus exactly one provider-owned persisted fragment.
 *
 * Jobs invokes the provider only after its own terminal ledger write has succeeded. The callback
 * is cleanup/observation work: it must be idempotent and cannot reopen or downgrade the terminal
 * job state when an external authority is unavailable.
 */
public data class DurableJobContextTerminalRequest(
    public val request: DurableJobContextRequest,
    public val fragment: DurableJobContextFragment,
    public val state: JobState,
) {
    init {
        require(state.terminal) { "a durable context terminal callback requires a terminal job state" }
    }
}

/** A one-shot binding that must restore the worker thread's previous state. */
public fun interface DurableJobContextBinding : AutoCloseable {
    override fun close(): Unit

    public companion object {
        public val NONE: DurableJobContextBinding = DurableJobContextBinding {}
    }
}

/**
 * Optional contributor to the jobs durable-context envelope.
 *
 * Providers must use stable [id] values and must not encode a second provider's concerns. The
 * kernel canonicalizes the independent fragments into one bounded stored envelope; that is what
 * keeps adding i18n, event, or tenant behavior linear rather than creating pairwise modules.
 */
public interface DurableJobContextProvider {
    public val id: String

    /**
     * Whether this provider is the authority that can satisfy [TenantBindingMode.REQUIRED]. This is
     * application configuration, not an untrusted bit in the persisted envelope: the provider still
     * has to validate its own fragment successfully before a handler can run.
     */
    public val providesRequiredBinding: Boolean get() = false

    public fun capture(request: DurableJobContextRequest): DurableJobContextCapture

    /**
     * Restores one fragment before the payload is decoded or the handler runs. Throw
     * [DurableJobContextPermanentException] for invalid/stale/revoked context and
     * [DurableJobContextUnavailableException] when retrying could make the authority available.
     */
    public fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding

    /**
     * Observes a successful terminal jobs receipt for this provider's fragment.
     *
     * The default preserves providers that own only capture/restore. Implementations must make
     * release/cleanup idempotent and use their own finite expiry sweep for a process crash between
     * the jobs receipt and this callback.
     */
    public fun onTerminal(request: DurableJobContextTerminalRequest): Unit = Unit
}

/** The declaration required a durable binding but no authoritative provider captured one. */
public class DurableJobContextRequiredException : IllegalStateException("this job requires a durable context binding")

/** A stored context is malformed, unknown, revoked, stale, or otherwise unsafe to execute. */
public class DurableJobContextPermanentException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The context authority is temporarily unavailable; the normal attempt retry policy applies. */
public class DurableJobContextUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Optional concurrency bulkhead over an opaque producer partition. It is acquired only after the
 * invocation lease and durable context have both been validated, and it never receives a raw
 * tenant reference.
 */
public interface PartitionPermit {
    public fun acquire(request: PartitionPermitRequest): PartitionPermitResult

    public companion object {
        /** Preserves existing single-tenant jobs behavior when no bulkhead adapter is installed. */
        public val NONE: PartitionPermit =
            object : PartitionPermit {
                override fun acquire(request: PartitionPermitRequest): PartitionPermitResult =
                    PartitionPermitResult.Granted(PartitionPermitLease.NONE)
            }
    }
}

/** The only input a permit authority receives; [producerPartition] is an opaque digest. */
public data class PartitionPermitRequest(
    public val definition: String,
    public val invocation: UUID,
    public val producerPartition: JobProducerPartition?,
)

/** A permit either protects the handler body or says when an uncharged deferral may be attempted. */
public sealed interface PartitionPermitResult {
    public data class Granted(
        public val lease: PartitionPermitLease,
    ) : PartitionPermitResult

    public data class Deferred(
        public val after: Duration,
    ) : PartitionPermitResult {
        init {
            require(after.isPositive) { "a partition permit deferral is positive, got $after" }
        }
    }
}

/** One acquired capacity unit. The worker always closes it before completing its attempt. */
public fun interface PartitionPermitLease : AutoCloseable {
    override fun close(): Unit

    public companion object {
        public val NONE: PartitionPermitLease = PartitionPermitLease {}
    }
}
