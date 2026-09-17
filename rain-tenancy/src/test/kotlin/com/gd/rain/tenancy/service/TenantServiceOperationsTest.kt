package com.gd.rain.tenancy.service

import com.gd.rain.persistence.tx.BackingIdentity
import com.gd.rain.persistence.tx.TransactionAuthority
import com.gd.rain.persistence.tx.TransactionPlacement
import com.gd.rain.tenancy.CompositeTenantResolver
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantGrant
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantRuntime
import com.gd.rain.tenancy.TenantRuntimeManager
import com.gd.rain.tenancy.TenantScope
import com.gd.rain.tenancy.TenantUnit
import com.gd.rain.tenancy.autoconfigure.RainTenancyAutoConfiguration
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.ResourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

class TenantServiceOperationsTest {
    private val authority =
        HmacTenantAuthority(
            "test",
            CompositeTenantResolver(emptyList()) {
                TenantResolution(TenantRef.of("acme"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
            },
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            clock = MutableClock(Instant.parse("2026-09-17T00:00:00Z")),
            random = SecureRandom(),
        )
    private val scope = authority.lookup(TenantRef.of("acme"), TenantOperation.READ)

    @Test
    fun `read advice opens exactly one matching unit and exposes it only through the runtime`() {
        val plane = RecordingPlane()
        val service = proxy(AnnotatedService(), plane)

        val observed = TenantContext.bind(scope).use { service.read() }

        assertThat(observed).isSameAs(plane.lastUnit)
        assertThat(plane.reads).isEqualTo(1)
        assertThat(plane.writes).isZero()
        assertThat(plane.currentProofs).isEqualTo(1)
        assertThatThrownBy(TenantRuntime::current).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `write advice never degrades to a read unit and missing scope is refused before opening`() {
        val plane = RecordingPlane()
        val service = proxy(AnnotatedService(), plane)

        assertThatThrownBy { service.write() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(plane.writes).isZero()

        TenantContext.bind(authority.lookup(TenantRef.of("acme"), TenantOperation.WRITE)).use {
            assertThat(service.write()).isEqualTo(TenantOperation.WRITE)
        }
        assertThat(plane.writes).isEqualTo(1)
        assertThat(plane.reads).isZero()
    }

    @Test
    fun `ordinary Spring transactional declaration remains outside the tenant data plane`() {
        val plane = RecordingPlane()
        val service = proxy(AnnotatedService(), plane)

        assertThat(service.controlPlane()).isEqualTo("control-plane")
        assertThat(plane.reads).isZero()
        assertThat(plane.writes).isZero()
    }

    @Test
    fun `conflicting method declarations refuse before a proxy can treat either operation as default`() {
        val method = ConflictingService::class.java.getMethod("ambiguous")

        assertThatThrownBy { TenantServiceOperation.of(method, ConflictingService::class.java) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("tenant service method declares conflicting operations")
    }

    @Test
    fun `method declaration overrides a class default without widening it`() {
        val inherited = ClassReadService::class.java.getMethod("inherited")
        val override = ClassReadService::class.java.getMethod("overridden")

        assertThat(TenantServiceOperation.of(inherited, ClassReadService::class.java)).isEqualTo(TenantOperation.READ)
        assertThat(TenantServiceOperation.of(override, ClassReadService::class.java)).isEqualTo(TenantOperation.WRITE)
    }

    @Test
    fun `auto configuration contributes the advisor only when authority and data plane are explicit beans`() {
        val plane = RecordingPlane()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration::class.java, RainTenancyAutoConfiguration::class.java))
            .withBean(com.gd.rain.tenancy.TenantAuthority::class.java, { authority })
            .withBean(TenantDataPlane::class.java, { plane })
            .withBean(AnnotatedService::class.java)
            .run { context ->
                val service = context.getBean(AnnotatedService::class.java)

                assertThat(AopUtils.isAopProxy(service)).isTrue()
                assertThat(context).hasSingleBean(TenantServiceAdvisor::class.java)
                TenantContext.bind(scope).use {
                    assertThat(service.read()).isSameAs(plane.lastUnit)
                }
                assertThat(plane.reads).isEqualTo(1)
            }
    }

    private fun proxy(
        target: AnnotatedService,
        plane: RecordingPlane,
    ): AnnotatedService =
        ProxyFactory(target)
            .apply {
                isProxyTargetClass = true
                addAdvisor(TenantServiceAdvisor(TenantServiceOperations(plane, TenantRuntimeManager(authority, emptyList()))))
            }.proxy as AnnotatedService

    private open class AnnotatedService {
        @TenantRead
        open fun read(): TenantUnit = TenantRuntime.current().unit()

        @TenantWrite
        open fun write(): TenantOperation = TenantRuntime.current().unit().operation

        @Transactional
        open fun controlPlane(): String = "control-plane"
    }

    private open class ConflictingService {
        @TenantRead
        @TenantWrite
        open fun ambiguous(): String = "never"
    }

    @TenantRead
    private open class ClassReadService {
        open fun inherited(): String = "read"

        @TenantWrite
        open fun overridden(): String = "write"
    }

    private class RecordingPlane : TenantDataPlane {
        private val transactions = SyntheticTransactions()
        private val dsl = DSL.using(SQLDialect.POSTGRES)
        var reads: Int = 0
        var writes: Int = 0
        var currentProofs: Int = 0
        lateinit var lastUnit: TenantUnit

        override fun <T> read(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T {
            reads += 1
            return within(scope, TenantOperation.READ, block)
        }

        override fun <T> write(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T {
            writes += 1
            return within(scope, TenantOperation.WRITE, block)
        }

        override fun <T> durable(
            scope: TenantScope,
            block: (TenantUnit) -> T,
        ): T = error("not used by service advice")

        override fun <T> admin(
            grant: TenantGrant,
            tenant: TenantRef,
            block: (TenantUnit) -> T,
        ): T = error("not used by service advice")

        private fun <T> within(
            scope: TenantScope,
            operation: TenantOperation,
            block: (TenantUnit) -> T,
        ): T {
            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.bindResource(transactions.resource, Any())
            try {
                return TenantContext.bind(scope).use {
                    val unit = SyntheticUnit(scope, operation)
                    lastUnit = unit
                    block(unit)
                }
            } finally {
                TransactionSynchronizationManager.getSynchronizations().forEach {
                    it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
                }
                TransactionSynchronizationManager.clear()
            }
        }

        private inner class SyntheticUnit(
            override val scope: TenantScope,
            override val operation: TenantOperation,
        ) : TenantUnit {
            private val placement: TransactionPlacement get() = TransactionPlacement.inspect(dsl, this@RecordingPlane.transactions)

            override val dsl: DSLContext = this@RecordingPlane.dsl
            override val backing: BackingIdentity get() = placement.backing
            override val transactions: TransactionAuthority get() = placement.requireAuthority()
            override val clock: Clock = Clock.systemUTC()

            override fun requireCurrentTransaction(): TransactionAuthority {
                currentProofs += 1
                return placement.requireAuthority()
            }

            override fun afterCommit(hint: () -> Unit) {
                error("not used by service advice")
            }
        }
    }

    private class SyntheticTransactions : ResourceTransactionManager {
        val resource: Any = Any()

        override fun getResourceFactory(): Any = resource

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = error("synthetic unit already owns it")

        override fun commit(status: TransactionStatus) = error("synthetic unit already owns it")

        override fun rollback(status: TransactionStatus) = error("synthetic unit already owns it")
    }
}
