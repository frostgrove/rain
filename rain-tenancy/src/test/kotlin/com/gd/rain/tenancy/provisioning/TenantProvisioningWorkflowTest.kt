package com.gd.rain.tenancy.provisioning

import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.control.TenantControlSnapshot
import com.gd.rain.tenancy.control.TenantControlVersion
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

class TenantProvisioningWorkflowTest {
    private val target = TenantProvisioningTarget(TenantRef.of("acme"), TenantEpoch(1), 1)
    private val clock = MutableClock(Instant.parse("2026-09-17T12:00:00Z"))

    @Test
    fun `workflow executes its verified graph in order and becomes the activation gate only after durable success`() {
        val calls = mutableListOf<String>()
        val ledger = MemoryLedger()
        val workflow =
            TenantProvisioningWorkflow(
                listOf(
                    step("database") { calls += "database" },
                    step("bucket", after = setOf("database")) { calls += "bucket" },
                ),
                ledger,
                clock,
            )

        assertThat(workflow.allows(snapshot())).isFalse()
        assertThat(workflow.run(target, TenantProvisioningRunSpec(Duration.ofSeconds(30), 2)))
            .isEqualTo(TenantProvisioningRunResult.Complete)
        assertThat(calls).containsExactly("database", "bucket")
        assertThat(workflow.allows(snapshot())).isTrue()
    }

    @Test
    fun `retryable and closed failures retain a durable non-activatable state without automatic compensation`() {
        val retryLedger = MemoryLedger()
        var attempts = 0
        val retry =
            TenantProvisioningWorkflow(
                listOf(
                    step("probe") {
                        attempts += 1
                        if (attempts == 1) throw TenantProvisioningRetryableException("probe_unavailable")
                    },
                ),
                retryLedger,
                clock,
            )

        assertThat(retry.run(target, TenantProvisioningRunSpec(Duration.ofSeconds(30), 1)))
            .isEqualTo(TenantProvisioningRunResult.Waiting("probe"))
        assertThat(retry.allows(snapshot())).isFalse()
        assertThat(retry.run(target, TenantProvisioningRunSpec(Duration.ofSeconds(30), 1)))
            .isEqualTo(TenantProvisioningRunResult.Complete)

        val closed =
            TenantProvisioningWorkflow(
                listOf(
                    step("credential") { throw TenantProvisioningClosedException("secret_policy") },
                    step("never", after = setOf("credential")) { error("must not run") },
                ),
                MemoryLedger(),
                clock,
            )

        assertThat(closed.run(target, TenantProvisioningRunSpec(Duration.ofSeconds(30), 2)))
            .isEqualTo(TenantProvisioningRunResult.Quarantined("credential", "secret_policy"))
        assertThat(closed.allows(snapshot())).isFalse()
    }

    @Test
    fun `lease expiry fences a stale worker and graph drift never inherits an old epoch completion`() {
        val ledger = MemoryLedger()
        val original = TenantProvisioningWorkflow(listOf(step("database") {}), ledger, clock)
        val fingerprint = original.fingerprint()
        ledger.ensure(target, fingerprint, listOf("database"), clock.instant())
        val first = ledger.claim(target, "database", Duration.ofSeconds(1), clock.instant()) as TenantProvisioningClaim.Acquired
        clock.advance(Duration.ofSeconds(2))
        val second = ledger.claim(target, "database", Duration.ofSeconds(1), clock.instant()) as TenantProvisioningClaim.Acquired

        assertThat(ledger.succeed(target, "database", first.fence, clock.instant())).isFalse()
        assertThat(ledger.succeed(target, "database", second.fence, clock.instant())).isTrue()
        assertThat(original.allows(snapshot())).isTrue()

        val changed = TenantProvisioningWorkflow(listOf(step("database", revision = 2) {}), ledger, clock)
        assertThat(changed.allows(snapshot())).isFalse()
        assertThatThrownBy { changed.run(target, TenantProvisioningRunSpec(Duration.ofSeconds(1), 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("changed")
    }

    @Test
    fun `unknown dependencies and cycles are startup failures`() {
        assertThatThrownBy {
            TenantProvisioningWorkflow(listOf(step("one", after = setOf("missing")) {}), MemoryLedger(), clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            TenantProvisioningWorkflow(
                listOf(step("one", after = setOf("two")) {}, step("two", after = setOf("one")) {}),
                MemoryLedger(),
                clock,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun snapshot(): TenantControlSnapshot =
        TenantControlSnapshot(
            TenantResolution(target.ref, TenantLifecycle.PROVISIONING, target.epoch, target.placementVersion),
            TenantControlVersion(1),
        )

    private fun step(
        id: String,
        revision: Int = 1,
        after: Set<String> = emptySet(),
        run: (TenantProvisioningContext) -> Unit,
    ): TenantProvisioningStep =
        object : TenantProvisioningStep {
            override val id: String = id
            override val revision: Int = revision
            override val after: Set<String> = after

            override fun run(context: TenantProvisioningContext): Unit = run(context)
        }

    private class MemoryLedger : TenantProvisioningLedger {
        private val workflows: MutableMap<Key, ByteArray> = mutableMapOf()
        private val states: MutableMap<Key, MutableMap<String, State>> = mutableMapOf()

        override fun ensure(
            target: TenantProvisioningTarget,
            fingerprint: ByteArray,
            steps: List<String>,
            now: Instant,
        ) {
            val key = Key(target)
            val prior = workflows.putIfAbsent(key, fingerprint.copyOf())
            require(
                prior == null || MessageDigest.isEqual(prior, fingerprint),
            ) { "tenant provisioning workflow changed during this tenant epoch" }
            val state = states.getOrPut(key) { mutableMapOf() }
            steps.forEach { state.putIfAbsent(it, State()) }
        }

        override fun claim(
            target: TenantProvisioningTarget,
            step: String,
            leaseDuration: Duration,
            now: Instant,
        ): TenantProvisioningClaim {
            val state = checkNotNull(states[Key(target)]?.get(step))
            if (state.completed) return TenantProvisioningClaim.Complete
            if (state.quarantined != null) return TenantProvisioningClaim.Quarantined(step, state.quarantined!!)
            if (state.token != null && checkNotNull(state.until).isAfter(now)) return TenantProvisioningClaim.Busy(step)
            state.fence += 1
            state.token = UUID(0, state.fence)
            state.until = now.plus(leaseDuration)
            return TenantProvisioningClaim.Acquired(TenantProvisioningFence(state.fence, state.token!!))
        }

        override fun succeed(
            target: TenantProvisioningTarget,
            step: String,
            fence: TenantProvisioningFence,
            now: Instant,
        ): Boolean = complete(target, step, fence) { it.completed = true }

        override fun retry(
            target: TenantProvisioningTarget,
            step: String,
            fence: TenantProvisioningFence,
            failureCode: String,
            now: Instant,
        ): Boolean = complete(target, step, fence) { }

        override fun quarantine(
            target: TenantProvisioningTarget,
            step: String,
            fence: TenantProvisioningFence,
            failureCode: String,
            now: Instant,
        ): Boolean = complete(target, step, fence) { it.quarantined = failureCode }

        override fun allSucceeded(
            target: TenantProvisioningTarget,
            fingerprint: ByteArray,
            steps: List<String>,
        ): Boolean =
            workflows[Key(target)]?.let { MessageDigest.isEqual(it, fingerprint) } == true &&
                steps.all { states[Key(target)]?.get(it)?.completed == true }

        private fun complete(
            target: TenantProvisioningTarget,
            step: String,
            fence: TenantProvisioningFence,
            change: (State) -> Unit,
        ): Boolean {
            val state = checkNotNull(states[Key(target)]?.get(step))
            if (state.fence != fence.value() || state.token != fence.token()) return false
            state.token = null
            state.until = null
            change(state)
            return true
        }

        private data class Key(
            val ref: TenantRef,
            val epoch: TenantEpoch,
        ) {
            constructor(target: TenantProvisioningTarget) : this(target.ref, target.epoch)
        }

        private class State(
            var fence: Long = 0,
            var token: UUID? = null,
            var until: Instant? = null,
            var completed: Boolean = false,
            var quarantined: String? = null,
        )
    }
}
