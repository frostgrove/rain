package com.gd.rain.jobs

import com.gd.rain.jobs.internal.execution.DefinitionGate
import com.gd.rain.jobs.support.Awaits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DefinitionGateTest {
    private val gate = DefinitionGate(mapOf("one" to 1, "two" to 2))

    @Test
    fun `the ceiling admits and refuses`() {
        assertThat(gate.tryEnter("two")).isTrue()
        assertThat(gate.tryEnter("two")).isTrue()
        assertThat(gate.tryEnter("two")).isFalse()

        gate.leave("two")

        assertThat(gate.tryEnter("two")).isTrue()
        assertThat(gate.inFlightOf("two")).isEqualTo(2)
    }

    @Test
    fun `definitions do not share a ceiling`() {
        assertThat(gate.tryEnter("one")).isTrue()

        assertThat(gate.tryEnter("two")).isTrue()
        assertThat(gate.tryEnter("one")).isFalse()
    }

    @Test
    fun `leaving more often than entering is a defect, not a wider gate`() {
        assertThatThrownBy { gate.leave("one") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(gate.tryEnter("one")).isTrue()
        assertThat(gate.tryEnter("one")).isFalse()
    }

    @Test
    fun `an undeclared definition is a wiring defect and not an open gate`() {
        assertThatThrownBy { gate.tryEnter("nobody") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("nobody")
        assertThatThrownBy { DefinitionGate(mapOf("zero" to 0)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `concurrent callers released together never exceed the ceiling and are answered without waiting`() {
        val wide = DefinitionGate(mapOf("wide" to 3))
        val start = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val admitted = AtomicInteger()
        val refused = AtomicInteger()
        val answered = CountDownLatch(THREADS)
        val pool = Executors.newVirtualThreadPerTaskExecutor()

        repeat(THREADS) {
            pool.submit {
                Awaits.latch(start, "the start signal")
                if (wide.tryEnter("wide")) {
                    admitted.incrementAndGet()
                    answered.countDown()
                    Awaits.latch(hold, "the release of held slots")
                    wide.leave("wide")
                } else {
                    refused.incrementAndGet()
                    answered.countDown()
                }
            }
        }
        start.countDown()
        Awaits.latch(answered, "every caller to be answered while three slots are held")

        assertThat(admitted.get()).isEqualTo(3)
        assertThat(refused.get()).isEqualTo(THREADS - 3)
        hold.countDown()
        pool.shutdown()
    }

    private companion object {
        const val THREADS = 16
    }
}
