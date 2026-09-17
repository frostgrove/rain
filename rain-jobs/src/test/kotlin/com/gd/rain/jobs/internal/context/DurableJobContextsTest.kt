package com.gd.rain.jobs.internal.context

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.context.DurableJobContextBinding
import com.gd.rain.jobs.context.DurableJobContextCapture
import com.gd.rain.jobs.context.DurableJobContextFragment
import com.gd.rain.jobs.context.DurableJobContextPermanentException
import com.gd.rain.jobs.context.DurableJobContextProvider
import com.gd.rain.jobs.context.DurableJobContextRequest
import com.gd.rain.jobs.context.DurableJobContextRequiredException
import com.gd.rain.jobs.context.DurableJobContextRestoreRequest
import com.gd.rain.jobs.context.DurableJobContextTerminalRequest
import com.gd.rain.jobs.context.JobProducerPartition
import com.gd.rain.jobs.context.TenantBindingMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

class DurableJobContextsTest {
    @Test
    fun `providers compose into one opaque envelope and bindings clean up in reverse order`() {
        val events = mutableListOf<String>()
        val contexts = DurableJobContexts(listOf(provider("i18n", events), provider("tenant", events, required = true, partition = true)))
        val invocation = UUID.fromString("00000000-0000-0000-0000-000000000001")

        val stored = contexts.capture("notes.write", invocation, "{\"text\":\"one\"}", TenantBindingMode.REQUIRED)

        assertThat(stored.version).isEqualTo(1)
        assertThat(stored.bytes).isNotNull()
        assertThat(stored.producerPartition).containsExactly(7, 8)
        assertThat(stored.payloadDigest)
            .containsExactly(*MessageDigest.getInstance("SHA-256").digest("{\"text\":\"one\"}".toByteArray()))
        assertThat(events).containsExactly("capture:i18n", "capture:tenant")

        contexts.restore("notes.write", invocation, TenantBindingMode.REQUIRED, stored).use {
            assertThat(events).containsExactly("capture:i18n", "capture:tenant", "restore:i18n", "restore:tenant")
        }

        assertThat(events).containsExactly(
            "capture:i18n",
            "capture:tenant",
            "restore:i18n",
            "restore:tenant",
            "close:tenant",
            "close:i18n",
        )
    }

    @Test
    fun `central mode never calls a provider and required mode refuses an absent authoritative binding`() {
        val events = mutableListOf<String>()
        val contexts = DurableJobContexts(listOf(provider("tenant", events, required = true)))
        val invocation = UUID.randomUUID()

        val central = contexts.capture("notes.write", invocation, "{}", TenantBindingMode.CENTRAL)

        assertThat(central.version).isNull()
        assertThat(central.bytes).isNull()
        assertThat(events).isEmpty()
        assertThatThrownBy {
            DurableJobContexts(listOf(absentProvider())).capture("notes.write", invocation, "{}", TenantBindingMode.REQUIRED)
        }.isInstanceOf(DurableJobContextRequiredException::class.java)
    }

    @Test
    fun `unknown or altered envelope fails closed before any provider can bind`() {
        val events = mutableListOf<String>()
        val contexts = DurableJobContexts(listOf(provider("tenant", events, required = true)))
        val stored = contexts.capture("notes.write", UUID.randomUUID(), "{}", TenantBindingMode.REQUIRED)
        val corrupted = checkNotNull(stored.bytes).copyOf().also { it[0] = 0 }

        assertThatThrownBy {
            contexts.restore(
                "notes.write",
                UUID.randomUUID(),
                TenantBindingMode.REQUIRED,
                stored.copy(bytes = corrupted),
            )
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
        assertThat(events).containsExactly("capture:tenant")
    }

    @Test
    fun `restore distinguishes central legacy and required context shapes without consulting a provider`() {
        val contexts = DurableJobContexts(emptyList())
        val invocation = UUID.randomUUID()
        val legacy = StoredJobContext(null, null, null, MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)))

        contexts.restore("notes.write", invocation, TenantBindingMode.INHERIT, legacy).use { }
        contexts.restore("notes.write", invocation, TenantBindingMode.CENTRAL, legacy).use { }
        assertThatThrownBy { contexts.restore("notes.write", invocation, TenantBindingMode.REQUIRED, legacy) }
            .isInstanceOf(DurableJobContextPermanentException::class.java)
        assertThatThrownBy {
            contexts.restore("notes.write", invocation, TenantBindingMode.CENTRAL, legacy.copy(producerPartition = byteArrayOf(1)))
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
        assertThatThrownBy {
            contexts.restore("notes.write", invocation, TenantBindingMode.INHERIT, legacy.copy(version = 1))
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
    }

    @Test
    fun `restore refuses missing authority malformed stored fields and an uninstalled provider`() {
        val capturedByTenant = DurableJobContexts(listOf(provider("tenant", mutableListOf(), required = true)))
        val invocation = UUID.randomUUID()
        val stored = capturedByTenant.capture("notes.write", invocation, "{}", TenantBindingMode.REQUIRED)

        assertThatThrownBy {
            DurableJobContexts(emptyList()).restore("notes.write", invocation, TenantBindingMode.INHERIT, stored)
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
        assertThatThrownBy {
            capturedByTenant.restore("notes.write", invocation, TenantBindingMode.INHERIT, stored.copy(payloadDigest = null))
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
        assertThatThrownBy {
            DurableJobContexts(listOf(provider("i18n", mutableListOf()))).restore(
                "notes.write",
                invocation,
                TenantBindingMode.REQUIRED,
                stored,
            )
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
    }

    @Test
    fun `capture validates provider topology and restoration unwinds a partial binding`() {
        val invocation = UUID.randomUUID()
        val events = mutableListOf<String>()
        val first = provider("first", events)
        val failing =
            object : DurableJobContextProvider {
                override val id: String = "second"

                override fun capture(request: DurableJobContextRequest): DurableJobContextCapture =
                    DurableJobContextCapture.Captured(DurableJobContextFragment.of(1, byteArrayOf(2)))

                override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding {
                    events += "restore:second"
                    throw IllegalStateException("second failed")
                }
            }
        val contexts = DurableJobContexts(listOf(first, failing))
        val stored = contexts.capture("notes.write", invocation, "{}", TenantBindingMode.INHERIT)

        assertThatThrownBy { contexts.restore("notes.write", invocation, TenantBindingMode.INHERIT, stored) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("second failed")
        assertThat(events).containsSubsequence("capture:first", "restore:first", "restore:second", "close:first")
        assertThatThrownBy {
            DurableJobContexts(
                listOf(provider("one", mutableListOf(), partition = true), provider("two", mutableListOf(), partition = true)),
            ).capture("notes.write", invocation, "{}", TenantBindingMode.INHERIT)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `envelope version and framing defects are permanent and absent inheritance remains central`() {
        val provider = provider("tenant", mutableListOf(), required = true)
        val contexts = DurableJobContexts(listOf(provider))
        val invocation = UUID.randomUUID()
        val stored = contexts.capture("notes.write", invocation, "{}", TenantBindingMode.INHERIT)

        assertThatThrownBy {
            contexts.restore("notes.write", invocation, TenantBindingMode.INHERIT, stored.copy(version = 2))
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
        assertThatThrownBy {
            contexts.restore("notes.write", invocation, TenantBindingMode.INHERIT, stored.copy(bytes = ByteArray(0)))
        }.isInstanceOf(DurableJobContextPermanentException::class.java)
        assertThatThrownBy {
            contexts.restore(
                "notes.write",
                invocation,
                TenantBindingMode.INHERIT,
                stored.copy(bytes = checkNotNull(stored.bytes).plus(0)),
            )
        }.isInstanceOf(DurableJobContextPermanentException::class.java)

        val absent =
            object : DurableJobContextProvider {
                override val id: String = "ambient"

                override fun capture(request: DurableJobContextRequest): DurableJobContextCapture = DurableJobContextCapture.Absent

                override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding = DurableJobContextBinding.NONE
            }
        val inherited = DurableJobContexts(listOf(absent)).capture("notes.write", invocation, "{}", TenantBindingMode.INHERIT)
        assertThat(inherited.version).isNull()
        assertThatThrownBy { DurableJobContexts(listOf(absent, absent)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cleanup propagates a close failure and preserves later cleanup failure as suppressed`() {
        val events = mutableListOf<String>()

        fun closingProvider(
            id: String,
            failure: String,
        ): DurableJobContextProvider =
            object : DurableJobContextProvider {
                override val id: String = id

                override fun capture(request: DurableJobContextRequest): DurableJobContextCapture =
                    DurableJobContextCapture.Captured(DurableJobContextFragment.of(1, id.toByteArray()))

                override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding =
                    DurableJobContextBinding {
                        events += "close:$id"
                        throw IllegalStateException(failure)
                    }
            }
        val contexts = DurableJobContexts(listOf(closingProvider("first", "first failed"), closingProvider("second", "second failed")))
        val stored = contexts.capture("notes.write", UUID.randomUUID(), "{}", TenantBindingMode.INHERIT)

        val failure = catchThrowable { contexts.restore("notes.write", UUID.randomUUID(), TenantBindingMode.INHERIT, stored).use { } }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessage("second failed")
        assertThat(checkNotNull(failure).suppressed.map(Throwable::message)).containsExactly("first failed")
        assertThat(events).containsExactly("close:second", "close:first")
    }

    @Test
    fun `terminal cleanup receives only persisted provider fragments after a terminal receipt`() {
        val callbacks = mutableListOf<DurableJobContextTerminalRequest>()
        val provider =
            object : DurableJobContextProvider {
                override val id: String = "i18n"

                override fun capture(request: DurableJobContextRequest): DurableJobContextCapture =
                    DurableJobContextCapture.Captured(DurableJobContextFragment.of(1, byteArrayOf(7)))

                override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding = DurableJobContextBinding.NONE

                override fun onTerminal(request: DurableJobContextTerminalRequest) {
                    callbacks += request
                }
            }
        val contexts = DurableJobContexts(listOf(provider))
        val invocation = UUID.randomUUID()
        val stored = contexts.capture("email.deliver", invocation, "{}", TenantBindingMode.INHERIT)

        contexts.terminal("email.deliver", invocation, TenantBindingMode.INHERIT, stored, JobState.SUCCEEDED)
        contexts.terminal("email.deliver", invocation, TenantBindingMode.CENTRAL, stored, JobState.SUCCEEDED)

        assertThat(callbacks).hasSize(1)
        assertThat(callbacks.single().request.invocation).isEqualTo(invocation)
        assertThat(callbacks.single().fragment.copy()).containsExactly(7)
        assertThat(callbacks.single().state).isEqualTo(JobState.SUCCEEDED)
    }

    private fun provider(
        id: String,
        events: MutableList<String>,
        required: Boolean = false,
        partition: Boolean = false,
    ): DurableJobContextProvider =
        object : DurableJobContextProvider {
            override val id: String = id
            override val providesRequiredBinding: Boolean = required

            override fun capture(request: DurableJobContextRequest): DurableJobContextCapture {
                events += "capture:$id"
                return DurableJobContextCapture.Captured(
                    DurableJobContextFragment.of(1, id.toByteArray()),
                    JobProducerPartition.of(byteArrayOf(7, 8)).takeIf { partition },
                )
            }

            override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding {
                events += "restore:$id"
                return DurableJobContextBinding { events += "close:$id" }
            }
        }

    private fun absentProvider(): DurableJobContextProvider =
        object : DurableJobContextProvider {
            override val id: String = "tenant"
            override val providesRequiredBinding: Boolean = true

            override fun capture(request: DurableJobContextRequest): DurableJobContextCapture = DurableJobContextCapture.Absent

            override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding = DurableJobContextBinding.NONE
        }
}
