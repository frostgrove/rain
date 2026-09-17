package com.gd.rain.tenancy.async

import com.gd.rain.tenancy.CompositeTenantResolver
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.task.TaskExecutor
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TenantTaskDecoratorTest {
    private val scope =
        HmacTenantAuthority(
            "test",
            CompositeTenantResolver(emptyList()) {
                TenantResolution(TenantRef.of("acme"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
            },
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            random = SecureRandom(),
        ).lookup(TenantRef.of("acme"), TenantOperation.READ)

    @Test
    fun `explicit tenant executor transports a minted scope then clears a reused worker`() {
        Executors.newSingleThreadExecutor().use { pool ->
            val executor = TenantTaskExecutor(TaskExecutor(pool::execute))
            val observed = AtomicReference<Any?>()
            val first = CountDownLatch(1)

            TenantContext.bind(scope).use {
                executor.execute {
                    observed.set(TenantContext.requireScope())
                    first.countDown()
                }
            }
            assertThat(first.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(observed.get()).isSameAs(scope)

            val second = CountDownLatch(1)
            val missing = AtomicReference<Throwable?>()
            executor.execute {
                missing.set(runCatching(TenantContext::requireScope).exceptionOrNull())
                second.countDown()
            }
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(missing.get()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `unregistered executor stays central even when submitted from a tenant scope`() {
        Executors.newSingleThreadExecutor().use { pool ->
            val missing = AtomicReference<Throwable?>()
            val task =
                TenantContext.bind(scope).use {
                    pool.submit {
                        missing.set(runCatching(TenantContext::requireScope).exceptionOrNull())
                    }
                }
            task.get(5, TimeUnit.SECONDS)
            assertThat(missing.get()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `leaked nested binding fails the task and cannot poison the next worker use`() {
        Executors.newSingleThreadExecutor().use { pool ->
            val decorator = TenantTaskDecorator()
            val leaked =
                TenantContext.bind(scope).use {
                    pool.submit(decorator.decorate(Runnable { TenantContext.bind(scope) }))
                }

            assertThatThrownBy { leaked.get(5, TimeUnit.SECONDS) }
                .hasCauseInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("tenant scope leaked from async task")

            val next = pool.submit<Boolean> { runCatching(TenantContext::requireScope).isFailure }
            assertThat(next.get(5, TimeUnit.SECONDS)).isTrue()
        }
    }
}
