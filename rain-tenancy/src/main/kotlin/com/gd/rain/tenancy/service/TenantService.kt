package com.gd.rain.tenancy.service

import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRuntime
import com.gd.rain.tenancy.TenantRuntimeManager
import com.gd.rain.tenancy.TenantUnit
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.StaticMethodMatcherPointcut
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.util.ClassUtils
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Opens one read-only tenant unit around a public proxied service method. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class TenantRead

/** Opens one writable tenant unit around a public proxied service method. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class TenantWrite

/** Compatibility spelling for a tenant service boundary; new code should use [TenantRead]/[TenantWrite]. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class TenantTransactional(
    public val value: TenantTransactionMode,
)

/** The two service-level transaction modes; durable/admin work remains explicit. */
public enum class TenantTransactionMode {
    READ,
    WRITE,
}

/**
 * Low-level magic orchestration reusable by both an AOP service boundary and an explicitly
 * constructed adapter. It starts no control-plane `@Transactional` work and never resolves a raw
 * tenant id: a scope must already be bound by a route, job, or explicit caller.
 */
public class TenantServiceOperations(
    private val dataPlane: TenantDataPlane,
    private val runtime: TenantRuntimeManager,
) {
    public fun <T> read(block: (TenantUnit) -> T): T = within(TenantOperation.READ, block)

    public fun <T> write(block: (TenantUnit) -> T): T = within(TenantOperation.WRITE, block)

    private fun <T> within(
        operation: TenantOperation,
        block: (TenantUnit) -> T,
    ): T {
        val scope = TenantContext.requireScope()
        val enter: ((TenantUnit) -> T) -> T =
            when (operation) {
                TenantOperation.READ -> { callback -> dataPlane.read(scope, callback) }
                TenantOperation.WRITE -> { callback -> dataPlane.write(scope, callback) }
                else -> error("only read and write are service operations")
            }
        return enter { unit ->
            check(unit.operation == operation) { "tenant data plane opened an unexpected service operation" }
            runtime.with(unit) { block(unit) }
        }
    }
}

/** Spring AOP interceptor which delegates one declared method to [TenantServiceOperations]. */
public class TenantServiceInterceptor(
    private val operations: TenantServiceOperations,
) : MethodInterceptor {
    override fun invoke(invocation: MethodInvocation): Any? {
        val operation =
            TenantServiceOperation.of(invocation.method, invocation.getThis()?.javaClass)
                ?: error("tenant service interceptor matched an undeclared method")
        return when (operation) {
            TenantOperation.READ -> operations.read { invocation.proceed() }
            TenantOperation.WRITE -> operations.write { invocation.proceed() }
            else -> error("only read and write are service operations")
        }
    }
}

/** Advisor for applications that deliberately enable Spring AOP for the tenancy service surface. */
public class TenantServiceAdvisor(
    operations: TenantServiceOperations,
) : DefaultPointcutAdvisor(TenantServicePointcut(), TenantServiceInterceptor(operations))

/** Matches only methods whose operation declaration resolves unambiguously. */
public class TenantServicePointcut : StaticMethodMatcherPointcut() {
    override fun matches(
        method: Method,
        targetClass: Class<*>,
    ): Boolean = TenantServiceOperation.of(method, targetClass) != null
}

/** Shared declaration lookup used by the advisor and start-up validators. */
public object TenantServiceOperation {
    public fun of(
        method: Method,
        targetClass: Class<*>?,
    ): TenantOperation? {
        if (method.declaringClass == Any::class.java || Modifier.isStatic(method.modifiers)) return null
        val target = targetClass?.let(ClassUtils::getUserClass)
        val methods =
            buildList {
                add(method)
                target?.let { type ->
                    runCatching { type.getMethod(method.name, *method.parameterTypes) }.getOrNull()?.let(::add)
                }
            }.distinct()
        operationFrom(methods) { "tenant service method declares conflicting operations" }?.let { return it }
        val types =
            buildList {
                add(method.declaringClass)
                target?.let(::add)
            }.distinct()
        return operationFrom(types) { "tenant service type declares conflicting operations" }
    }

    private fun operationFrom(
        elements: List<AnnotatedElement>,
        conflict: () -> String,
    ): TenantOperation? {
        val declarations = elements.flatMap(::declaredOn).toSet()
        check(declarations.size <= 1, conflict)
        return declarations.singleOrNull()
    }

    private fun declaredOn(element: AnnotatedElement): List<TenantOperation> {
        val found = mutableListOf<TenantOperation>()
        if (AnnotatedElementUtils.findMergedAnnotation(element, TenantRead::class.java) != null) found += TenantOperation.READ
        if (AnnotatedElementUtils.findMergedAnnotation(element, TenantWrite::class.java) != null) found += TenantOperation.WRITE
        AnnotatedElementUtils.findMergedAnnotation(element, TenantTransactional::class.java)?.let { annotation ->
            found +=
                when (annotation.value) {
                    TenantTransactionMode.READ -> TenantOperation.READ
                    TenantTransactionMode.WRITE -> TenantOperation.WRITE
                }
        }
        return found
    }
}
