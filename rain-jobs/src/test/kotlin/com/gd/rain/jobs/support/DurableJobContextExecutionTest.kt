package com.gd.rain.jobs.support

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.context.DurableJobContextBinding
import com.gd.rain.jobs.context.DurableJobContextCapture
import com.gd.rain.jobs.context.DurableJobContextFragment
import com.gd.rain.jobs.context.DurableJobContextProvider
import com.gd.rain.jobs.context.DurableJobContextRequest
import com.gd.rain.jobs.context.DurableJobContextRestoreRequest
import com.gd.rain.jobs.context.DurableJobContextTerminalRequest
import com.gd.rain.jobs.context.PartitionPermit
import com.gd.rain.jobs.context.PartitionPermitRequest
import com.gd.rain.jobs.context.PartitionPermitResult
import com.gd.rain.jobs.context.TenantBindingMode
import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.context.DurableJobContexts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class DurableJobContextExecutionTest {
    @Test
    fun `worker restores before the handler and clears the binding on its reused thread`() {
        val binding = ThreadLocal<String?>()
        val terminal = mutableListOf<DurableJobContextTerminalRequest>()
        val provider =
            object : DurableJobContextProvider {
                override val id: String = "tenant"
                override val providesRequiredBinding: Boolean = true

                override fun capture(request: DurableJobContextRequest): DurableJobContextCapture =
                    DurableJobContextCapture.Captured(DurableJobContextFragment.of(1, byteArrayOf(1)))

                override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding {
                    val previous = binding.get()
                    binding.set("restored")
                    return DurableJobContextBinding {
                        if (previous == null) binding.remove() else binding.set(previous)
                    }
                }

                override fun onTerminal(request: DurableJobContextTerminalRequest) {
                    terminal += request
                }
            }
        val invocation = UUID.randomUUID()
        val stored = DurableJobContexts(listOf(provider)).capture("notes.write", invocation, "{\"text\":\"t\"}", TenantBindingMode.INHERIT)
        val ledger = ScriptedLedger(context = stored)
        var seen: String? = null
        val execution =
            ScriptedExecution(
                threads = com.gd.rain.jobs.internal.execution.VirtualAttemptThreads,
                ledger = ledger,
                contextProviders = listOf(provider),
            ) { _, _ ->
                seen = binding.get()
            }

        execution.execution.attempt(JobTaskData(invocation, 0))

        assertThat(seen).isEqualTo("restored")
        assertThat(binding.get()).isNull()
        assertThat(ledger.writes).containsExactly("finish succeeded null at ${Fixtures.START}")
        assertThat(terminal.single().state).isEqualTo(JobState.SUCCEEDED)
        assertThat(terminal.single().request.invocation).isEqualTo(invocation)
    }

    @Test
    fun `partition capacity defers without entering application code or charging a retry`() {
        var invoked = false
        val execution =
            ScriptedExecution(
                threads = com.gd.rain.jobs.internal.execution.VirtualAttemptThreads,
                partitionPermit =
                    object : PartitionPermit {
                        override fun acquire(request: PartitionPermitRequest): PartitionPermitResult =
                            PartitionPermitResult.Deferred(Duration.ofSeconds(5))
                    },
            ) { _, _ ->
                invoked = true
            }

        execution.execution.attempt(JobTaskData(UUID.randomUUID(), 0))

        assertThat(invoked).isFalse()
        assertThat(execution.ledger.writes).containsExactly("defer until ${Fixtures.START.plusSeconds(5)}")
    }
}
