package com.gd.rain.tenancy.jobs

import com.gd.rain.jobs.Dedupe
import com.gd.rain.jobs.EnqueueOptions
import com.gd.rain.jobs.EnqueueOutcome
import com.gd.rain.jobs.JobDefinition
import com.gd.rain.jobs.JobPayloadCodec
import com.gd.rain.jobs.JobPriority
import com.gd.rain.jobs.WorkQueue
import com.gd.rain.jobs.context.TenantBindingMode
import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantDigest
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantGrantVerifier
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.TenantUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TenantJobOutboxRelayTest {
    private val tenant = TenantRef.of("acme")
    private val scope =
        TenantScope(
            tenant,
            TenantEpoch(1),
            TenantLifecycle.ACTIVE,
            1,
            "test",
            ByteArray(32),
            Instant.parse("2026-09-17T12:00:00Z"),
            ByteArray(16),
            TenantDigest(ByteArray(32)),
        )
    private val grant =
        TenantGrant(
            "ops",
            "outbox-relay",
            setOf(TenantOperation.ADMIN),
            Instant.parse("2026-09-18T00:00:00Z"),
            setOf(tenant),
            ByteArray(32),
        )

    @Test
    fun `relay crosses source control source boundaries and advances only acknowledged prefix`() {
        val first = entry(1)
        val second = entry(2)
        val outbox = RecordingOutbox(listOf(first, second))
        val dispatched = mutableListOf<TenantJobOutboxId>()
        val relay =
            TenantJobOutboxRelay(
                RecordingDataPlane(scope),
                TenantGrantVerifier { _, requested, _ ->
                    check(requested == tenant)
                    scope
                },
                outbox,
                TenantJobDeliveryReceiptStore { restored, entry, dispatch ->
                    assertThat(restored).isSameAs(scope)
                    assertThat(
                        com.gd.rain.tenancy.TenantContext
                            .requireScope(),
                    ).isSameAs(scope)
                    dispatch().let { TenantJobDeliveryReceiptOutcome.Dispatched(it.invocation) }
                },
                TenantJobOutboxDispatcher { entry ->
                    dispatched += entry.id
                    EnqueueOutcome.Scheduled(UUID(0, entry.id.value.leastSignificantBits + 100))
                },
            )

        val result = relay.relay(TenantJobOutboxRelayRequest(grant, tenant, TenantJobOutboxPage(10)))

        assertThat(result).isEqualTo(TenantJobOutboxRelayResult.Delivered(2, TenantJobOutboxCursor(second.createdAt, second.id)))
        assertThat(dispatched).containsExactly(first.id, second.id)
        assertThat(outbox.acknowledged).containsExactly(first.id, second.id)
    }

    @Test
    fun `receipt collision halts without acknowledging or skipping the conflicting source row`() {
        val first = entry(1)
        val second = entry(2)
        val outbox = RecordingOutbox(listOf(first, second))
        val relay =
            TenantJobOutboxRelay(
                RecordingDataPlane(scope),
                TenantGrantVerifier { _, _, _ -> scope },
                outbox,
                TenantJobDeliveryReceiptStore { _, entry, dispatch ->
                    if (entry.id == second.id) {
                        TenantJobDeliveryReceiptOutcome.Collision
                    } else {
                        dispatch().let { TenantJobDeliveryReceiptOutcome.Dispatched(it.invocation) }
                    }
                },
                TenantJobOutboxDispatcher { EnqueueOutcome.Scheduled(UUID(0, 100)) },
            )

        val result = relay.relay(TenantJobOutboxRelayRequest(grant, tenant, TenantJobOutboxPage(10)))

        assertThat(result).isEqualTo(TenantJobOutboxRelayResult.Halted(1, TenantJobOutboxCursor(first.createdAt, first.id)))
        assertThat(outbox.acknowledged).containsExactly(first.id)
    }

    @Test
    fun `jobs dispatcher preserves source not-before and refuses non-required definitions`() {
        val clock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC)
        val definition = JobDefinition.of<String>("notes.write", "standard", TenantBindingMode.REQUIRED)
        val queue = RecordingQueue()
        val dispatcher = JobsTenantJobOutboxDispatcher(queue, StringCodec, listOf(definition), clock)
        val delayed = entry(1, notBefore = clock.instant().plusSeconds(30))

        assertThat(dispatcher.dispatch(delayed)).isEqualTo(EnqueueOutcome.Scheduled(UUID(0, 20)))
        assertThat(queue.payload).isEqualTo("{\"id\":1}")
        assertThat(queue.options?.after).isEqualTo(Duration.ofSeconds(30))

        assertThatThrownBy {
            JobsTenantJobOutboxDispatcher(
                queue,
                StringCodec,
                listOf(JobDefinition.of<String>("notes.central", "standard", TenantBindingMode.INHERIT)),
                clock,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun entry(
        id: Long,
        notBefore: Instant = Instant.parse("2026-09-17T12:00:00Z"),
    ): TenantJobOutboxEntry {
        val order =
            TenantJobOutboxOrder(
                TenantJobOutboxId(UUID(0, id)),
                "notes.write",
                "{\"id\":$id}".toByteArray(),
                EnqueueOptions(Dedupe.None, JobPriority(3)),
            )
        return TenantJobOutboxEntry(
            order.id,
            TenantEpoch(1),
            order.definition,
            order.serializedPayload(),
            order.payloadDigest,
            order.options,
            Instant.parse("2026-09-17T12:00:00Z"),
            notBefore,
        )
    }

    private class RecordingOutbox(
        private val entries: List<TenantJobOutboxEntry>,
    ) : TenantJobOutbox {
        val acknowledged: MutableList<TenantJobOutboxId> = mutableListOf()

        override fun enqueue(
            unit: TenantUnit,
            order: TenantJobOutboxOrder,
        ): TenantJobOutboxEnqueueResult = error("not used by relay")

        override fun pending(
            unit: TenantUnit,
            page: TenantJobOutboxPage,
        ): TenantJobOutboxBatch = TenantJobOutboxBatch(entries, entries.lastOrNull()?.let { TenantJobOutboxCursor(it.createdAt, it.id) })

        override fun acknowledge(
            unit: TenantUnit,
            entry: TenantJobOutboxEntry,
            invocation: UUID,
        ): TenantJobOutboxAcknowledgeResult {
            acknowledged += entry.id
            return TenantJobOutboxAcknowledgeResult.Acknowledged
        }
    }

    private class RecordingDataPlane(
        private val scope: TenantScope,
    ) : TenantDataPlane {
        override fun <T> read(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T = error("not used by relay")

        override fun <T> write(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T = error("not used by relay")

        override fun <T> durable(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T = error("not used by relay")

        override fun <T> admin(
            grant: TenantGrant,
            tenant: TenantRef,
            block: (TenantUnit) -> T,
        ): T = block(RelayUnit(scope))
    }

    private class RelayUnit(
        override val scope: TenantScope,
    ) : TenantUnit {
        override val operation: TenantOperation = TenantOperation.ADMIN
        override val dsl: DSLContext get() = error("not used by relay")
        override val backing: BackingIdentity get() = error("not used by relay")
        override val transactions: TransactionAuthority get() = error("not used by relay")
        override val clock: Clock get() = error("not used by relay")

        override fun requireCurrentTransaction(): TransactionAuthority = error("not used by relay")

        override fun afterCommit(hint: () -> Unit): Unit = error("not used by relay")
    }

    private class RecordingQueue : WorkQueue {
        var payload: String? = null
        var options: EnqueueOptions? = null

        override fun <P : Any> enqueue(
            definition: JobDefinition<P>,
            payload: P,
            options: EnqueueOptions,
        ): EnqueueOutcome {
            this.payload = payload as String
            this.options = options
            return EnqueueOutcome.Scheduled(UUID(0, 20))
        }
    }

    private object StringCodec : JobPayloadCodec {
        override fun encode(payload: Any): String = payload as String

        override fun <P : Any> decode(
            json: String,
            type: Class<P>,
        ): P = type.cast(json)
    }
}
