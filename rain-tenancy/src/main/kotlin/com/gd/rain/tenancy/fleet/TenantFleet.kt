package com.gd.rain.tenancy.fleet

import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantUnit
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Stable, application-chosen identity of a resumable fleet operation. */
@JvmInline
public value class TenantFleetOperationId(
    public val value: String,
) {
    init {
        require(ID.matches(value)) { "tenant fleet operation id is not stable" }
    }

    private companion object {
        val ID: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** Opaque item identity, stable across a resume under the same grant and operation. */
public class TenantFleetItemId internal constructor(
    public val operation: TenantFleetOperationId,
    public val position: Int,
) {
    init {
        require(position >= 0) { "tenant fleet item position is not negative" }
    }

    override fun equals(other: Any?): Boolean = other is TenantFleetItemId && operation == other.operation && position == other.position

    override fun hashCode(): Int = 31 * operation.hashCode() + position

    override fun toString(): String = "tenant-fleet-item[$operation:$position]"
}

/** Opaque resumable position bound to one signed grant cohort. */
public class TenantFleetCursor internal constructor(
    grantBinding: ByteArray,
    public val position: Int,
) {
    init {
        require(grantBinding.size == BINDING_BYTES) { "tenant fleet cursor has an unknown binding" }
        require(position >= 0) { "tenant fleet cursor position is not negative" }
    }

    private val grantBinding: ByteArray = grantBinding.copyOf()

    internal fun belongsTo(grant: TenantGrant): Boolean = MessageDigest.isEqual(grantBinding, grant.binding)

    override fun toString(): String = "tenant-fleet-cursor[position=$position]"

    private companion object {
        const val BINDING_BYTES: Int = 32
    }
}

/** Mandatory finite execution limits for one resumable fleet page. */
public data class TenantFleetSpec(
    public val operation: TenantFleetOperationId,
    public val batchSize: Int,
    public val maxConcurrency: Int,
    public val timeBudget: Duration,
    public val leaseDuration: Duration,
    public val cursor: TenantFleetCursor? = null,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) { "tenant fleet batch size is out of bounds" }
        require(maxConcurrency in 1..MAX_CONCURRENCY) { "tenant fleet concurrency is out of bounds" }
        require(timeBudget.isPositiveAndAtMost(MAX_DURATION)) { "tenant fleet time budget is out of bounds" }
        require(leaseDuration.isPositiveAndAtMost(MAX_DURATION)) { "tenant fleet lease duration is out of bounds" }
    }

    private companion object {
        const val MAX_BATCH_SIZE: Int = 1_000
        const val MAX_CONCURRENCY: Int = 128
        val MAX_DURATION: Duration = Duration.ofHours(1)
    }
}

/** The durable lease request an application-owned fleet scheduler must fence. */
public data class TenantFleetLeaseRequest(
    public val operation: TenantFleetOperationId,
    public val cursor: TenantFleetCursor,
    public val duration: Duration,
)

/** A lease is always released after a finished, failed, or interrupted page. */
public fun interface TenantFleetLease : AutoCloseable {
    override fun close(): Unit
}

/** A store-backed fleet scheduler either grants one exclusive lease or reports contention. */
public fun interface TenantFleetLeaseManager {
    public fun acquire(request: TenantFleetLeaseRequest): TenantFleetLeaseClaim
}

/** Lease contention is an expected, retryable scheduling outcome rather than an unbounded wait. */
public sealed interface TenantFleetLeaseClaim {
    public data class Acquired(
        public val lease: TenantFleetLease,
    ) : TenantFleetLeaseClaim

    public data object Busy : TenantFleetLeaseClaim
}

/** The handler's one-tenant capability and an id it can use for its own idempotent command. */
public class TenantFleetItem internal constructor(
    public val id: TenantFleetItemId,
    public val unit: TenantUnit,
)

/** A page never exposes a raw tenant reference in its operator-facing outcomes. */
public enum class TenantFleetItemStatus {
    SUCCEEDED,
    FAILED,
    UNCERTAIN,
}

/** One bounded item result. A cursor advances only over a contiguous successful prefix. */
public data class TenantFleetItemOutcome(
    public val id: TenantFleetItemId,
    public val status: TenantFleetItemStatus,
)

/** A claimed page's partial result. [next] is null only after the fixed cohort is exhausted. */
public data class TenantFleetPageResult(
    public val outcomes: List<TenantFleetItemOutcome>,
    public val next: TenantFleetCursor?,
)

/** One fleet attempt either found a held lease or produced a finite, resumable page. */
public sealed interface TenantFleetRunResult {
    public data object LeaseBusy : TenantFleetRunResult

    public data class Finished(
        public val page: TenantFleetPageResult,
    ) : TenantFleetRunResult
}

/**
 * Explicit cross-tenant runner. It never enumerates tenants itself: the signed [TenantGrant]
 * supplies a fixed cohort; every callback enters through [TenantDataPlane.admin]. Work after a
 * failed or timed-out item may already have started, so handlers must use [TenantFleetItem.id] as
 * their downstream idempotency key before a caller resumes [TenantFleetPageResult.next].
 */
public class TenantFleet(
    private val dataPlane: TenantDataPlane,
    private val leases: TenantFleetLeaseManager,
    private val executor: ExecutorService,
) {
    public fun run(
        grant: TenantGrant,
        spec: TenantFleetSpec,
        block: (TenantFleetItem) -> Unit,
    ): TenantFleetRunResult {
        require(TenantOperation.ADMIN in grant.operations) { "tenant fleet grant does not allow administrative work" }
        val cursor = spec.cursor ?: TenantFleetCursor(grant.binding.copyOf(), 0)
        require(cursor.belongsTo(grant)) { "tenant fleet cursor belongs to another grant" }
        val ordered = grant.cohort.sortedWith(compareByBytes())
        require(cursor.position <= ordered.size) { "tenant fleet cursor is outside its cohort" }

        return when (val claim = leases.acquire(TenantFleetLeaseRequest(spec.operation, cursor, spec.leaseDuration))) {
            TenantFleetLeaseClaim.Busy -> {
                TenantFleetRunResult.LeaseBusy
            }

            is TenantFleetLeaseClaim.Acquired -> {
                claim.lease.use {
                    val members = ordered.drop(cursor.position).take(spec.batchSize)
                    TenantFleetRunResult.Finished(execute(grant, spec, cursor, members, ordered.size, block))
                }
            }
        }
    }

    private fun execute(
        grant: TenantGrant,
        spec: TenantFleetSpec,
        cursor: TenantFleetCursor,
        members: List<com.gd.rain.tenancy.TenantRef>,
        cohortSize: Int,
        block: (TenantFleetItem) -> Unit,
    ): TenantFleetPageResult {
        if (members.isEmpty()) return TenantFleetPageResult(emptyList(), null)
        val permits = Semaphore(spec.maxConcurrency)
        val futures =
            try {
                executor.invokeAll(
                    members.mapIndexed { offset, tenant ->
                        java.util.concurrent.Callable {
                            permits.acquire()
                            try {
                                dataPlane.admin(grant, tenant) { unit ->
                                    block(TenantFleetItem(TenantFleetItemId(spec.operation, cursor.position + offset), unit))
                                }
                                TenantFleetItemStatus.SUCCEEDED
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                TenantFleetItemStatus.UNCERTAIN
                            } catch (_: Exception) {
                                TenantFleetItemStatus.FAILED
                            } finally {
                                permits.release()
                            }
                        }
                    },
                    spec.timeBudget.toNanos(),
                    TimeUnit.NANOSECONDS,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return unresolved(cursor, spec, members.size)
            }
        val outcomes =
            futures.mapIndexed { offset, future ->
                val status =
                    if (future.isCancelled) {
                        TenantFleetItemStatus.UNCERTAIN
                    } else {
                        try {
                            future.get()
                        } catch (_: CancellationException) {
                            TenantFleetItemStatus.UNCERTAIN
                        } catch (_: ExecutionException) {
                            TenantFleetItemStatus.FAILED
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            TenantFleetItemStatus.UNCERTAIN
                        }
                    }
                TenantFleetItemOutcome(TenantFleetItemId(spec.operation, cursor.position + offset), status)
            }
        val nextPosition =
            cursor.position +
                outcomes.indexOfFirst { it.status != TenantFleetItemStatus.SUCCEEDED }.let {
                    if (it < 0) outcomes.size else it
                }
        return TenantFleetPageResult(
            outcomes,
            TenantFleetCursor(grant.binding.copyOf(), nextPosition).takeUnless { nextPosition == cohortSize },
        )
    }

    private fun unresolved(
        cursor: TenantFleetCursor,
        spec: TenantFleetSpec,
        memberCount: Int,
    ): TenantFleetPageResult =
        TenantFleetPageResult(
            (0 until memberCount).map { offset ->
                TenantFleetItemOutcome(TenantFleetItemId(spec.operation, cursor.position + offset), TenantFleetItemStatus.UNCERTAIN)
            },
            cursor,
        )

    private companion object {
        fun compareByBytes(): Comparator<com.gd.rain.tenancy.TenantRef> =
            Comparator { left, right ->
                val leftBytes = left.bytes()
                val rightBytes = right.bytes()
                leftBytes.zip(rightBytes).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.toInt().compareTo(b.toInt()) }
                    ?: leftBytes.size.compareTo(rightBytes.size)
            }
    }
}

private fun Duration.isPositiveAndAtMost(maximum: Duration): Boolean = !isNegative && !isZero && this <= maximum
