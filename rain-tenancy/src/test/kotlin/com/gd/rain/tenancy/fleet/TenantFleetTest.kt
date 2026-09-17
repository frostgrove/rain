package com.gd.rain.tenancy.fleet

import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.TenantUnit
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TenantFleetTest {
    private val tenants = listOf(TenantRef.of("zeta"), TenantRef.of("alpha"), TenantRef.of("beta"))
    private val grant =
        TenantGrant("ops", "reindex", setOf(TenantOperation.ADMIN), Instant.parse("2026-09-18T00:00:00Z"), tenants.toSet(), ByteArray(32))

    @Test
    fun `a fleet page uses a leased fixed cohort and returns a resumable cursor`() {
        Executors.newFixedThreadPool(4).use { executor ->
            val plane = RecordingDataPlane()
            val leases = RecordingLeases()
            val runner = TenantFleet(plane, leases, executor)
            val seen = Collections.synchronizedList(mutableListOf<TenantFleetItemId>())

            val result = runner.run(grant, spec(batchSize = 2, maxConcurrency = 2)) { seen += it.id }

            val page = (result as TenantFleetRunResult.Finished).page
            assertThat(plane.invoked).containsExactlyInAnyOrder(tenants[1], tenants[2])
            assertThat(seen.map(TenantFleetItemId::position).sorted()).containsExactly(0, 1)
            assertThat(page.outcomes.map(TenantFleetItemOutcome::status)).containsExactly(
                TenantFleetItemStatus.SUCCEEDED,
                TenantFleetItemStatus.SUCCEEDED,
            )
            assertThat(page.next?.position).isEqualTo(2)
            assertThat(leases.requests).singleElement().extracting(TenantFleetLeaseRequest::operation).isEqualTo(spec().operation)
            assertThat(leases.releases).isEqualTo(1)
        }
    }

    @Test
    fun `a failed item stops cursor advance and preserves its stable id on resume`() {
        Executors.newFixedThreadPool(2).use { executor ->
            val plane = RecordingDataPlane()
            val runner = TenantFleet(plane, RecordingLeases(), executor)
            val seen = Collections.synchronizedList(mutableListOf<TenantFleetItemId>())
            var failing: Boolean = true

            val first =
                (
                    runner.run(grant, spec(batchSize = 3)) { item ->
                        seen += item.id
                        if (failing && item.id.position == 2) error("simulated tenant failure")
                    } as TenantFleetRunResult.Finished
                ).page

            assertThat(first.outcomes.map(TenantFleetItemOutcome::status)).containsExactly(
                TenantFleetItemStatus.SUCCEEDED,
                TenantFleetItemStatus.SUCCEEDED,
                TenantFleetItemStatus.FAILED,
            )
            assertThat(first.next?.position).isEqualTo(2)

            failing = false
            val resumed =
                (runner.run(grant, spec(batchSize = 3, cursor = first.next)) { seen += it.id } as TenantFleetRunResult.Finished).page

            assertThat(resumed.next).isNull()
            assertThat(seen.map(TenantFleetItemId::position).sorted()).containsExactly(0, 1, 2, 2)
        }
    }

    @Test
    fun `lease contention starts no tenant unit`() {
        Executors.newSingleThreadExecutor().use { executor ->
            val plane = RecordingDataPlane()

            val result = TenantFleet(plane, RecordingLeases(busy = true), executor).run(grant, spec()) {}

            assertThat(result).isEqualTo(TenantFleetRunResult.LeaseBusy)
            assertThat(plane.invoked).isEmpty()
        }
    }

    @Test
    fun `concurrency never exceeds the declared page budget`() {
        Executors.newFixedThreadPool(4).use { executor ->
            val plane = RecordingDataPlane()
            val runner = TenantFleet(plane, RecordingLeases(), executor)
            val active = AtomicInteger()
            val maximum = AtomicInteger()

            val result =
                runner.run(grant, spec(batchSize = 3, maxConcurrency = 2)) {
                    val now = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, now) }
                    try {
                        Thread.sleep(50)
                    } finally {
                        active.decrementAndGet()
                    }
                }

            assertThat((result as TenantFleetRunResult.Finished).page.outcomes.map(TenantFleetItemOutcome::status))
                .containsOnly(TenantFleetItemStatus.SUCCEEDED)
            assertThat(maximum.get()).isEqualTo(2)
        }
    }

    @Test
    fun `expired time budget preserves the cursor and makes every unfinished item uncertain`() {
        Executors.newSingleThreadExecutor().use { executor ->
            val runner = TenantFleet(RecordingDataPlane(), RecordingLeases(), executor)
            val timed =
                TenantFleetSpec(
                    TenantFleetOperationId("tenant.reindex"),
                    3,
                    1,
                    Duration.ofMillis(10),
                    Duration.ofSeconds(30),
                )

            val result =
                runner.run(grant, timed) {
                    Thread.sleep(Duration.ofSeconds(1).toMillis())
                } as TenantFleetRunResult.Finished

            assertThat(result.page.outcomes.map(TenantFleetItemOutcome::status))
                .containsOnly(TenantFleetItemStatus.UNCERTAIN)
            assertThat(result.page.next?.position).isZero()
        }
    }

    private fun spec(
        batchSize: Int = 3,
        maxConcurrency: Int = 1,
        cursor: TenantFleetCursor? = null,
    ): TenantFleetSpec =
        TenantFleetSpec(
            TenantFleetOperationId("tenant.reindex"),
            batchSize,
            maxConcurrency,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            cursor,
        )

    private class RecordingLeases(
        private val busy: Boolean = false,
    ) : TenantFleetLeaseManager {
        val requests = mutableListOf<TenantFleetLeaseRequest>()
        var releases: Int = 0

        override fun acquire(request: TenantFleetLeaseRequest): TenantFleetLeaseClaim {
            requests += request
            return if (busy) TenantFleetLeaseClaim.Busy else TenantFleetLeaseClaim.Acquired(TenantFleetLease { releases += 1 })
        }
    }

    private class RecordingDataPlane(
        var fail: ((TenantRef) -> Boolean)? = null,
    ) : TenantDataPlane {
        val invoked: MutableList<TenantRef> = Collections.synchronizedList(mutableListOf())

        override fun <T> read(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T = error("not used by tenant fleet")

        override fun <T> write(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T = error("not used by tenant fleet")

        override fun <T> durable(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T = error("not used by tenant fleet")

        override fun <T> admin(
            grant: TenantGrant,
            tenant: TenantRef,
            block: (TenantUnit) -> T,
        ): T {
            invoked += tenant
            if (fail?.invoke(tenant) == true) error("simulated tenant failure")
            return block(UnusedUnit)
        }
    }

    private data object UnusedUnit : TenantUnit {
        override val scope: TenantScope get() = error("not used by tenant fleet")
        override val operation: TenantOperation get() = TenantOperation.ADMIN
        override val dsl: DSLContext get() = error("not used by tenant fleet")
        override val backing: BackingIdentity get() = error("not used by tenant fleet")
        override val transactions: TransactionAuthority get() = error("not used by tenant fleet")
        override val clock: Clock get() = error("not used by tenant fleet")

        override fun requireCurrentTransaction(): TransactionAuthority = error("not used by tenant fleet")

        override fun afterCommit(hint: () -> Unit): Unit = error("not used by tenant fleet")
    }
}
