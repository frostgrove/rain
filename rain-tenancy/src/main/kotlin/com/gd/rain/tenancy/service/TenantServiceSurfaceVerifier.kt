package com.gd.rain.tenancy.service

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.beans.factory.ListableBeanFactory
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Refuses annotated singleton service methods that Spring cannot advise. A tenant annotation on a
 * private or final method is worse than no annotation: it visually promises a data-plane boundary
 * while executing without one, so it is a start-up error rather than a best-effort warning.
 */
public class TenantServiceSurfaceVerifier(
    private val beans: ListableBeanFactory,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        beans.beanDefinitionNames
            .asSequence()
            .mapNotNull { name -> beans.getType(name, false)?.let { name to it } }
            .flatMap { (name, type) -> problems(name, type).asSequence() }
            .distinct()
            .sortedBy(ConfigurationProblem::path)
            .toList()

    private fun problems(
        name: String,
        type: Class<*>,
    ): List<ConfigurationProblem> {
        val methods = methodsOf(type)
        return methods.flatMap { method ->
            val operation =
                try {
                    TenantServiceOperation.of(method, type)
                } catch (failure: IllegalStateException) {
                    return@flatMap listOf(problem(name, method, failure.message ?: "has an invalid tenant operation declaration"))
                }
            if (operation == null) {
                emptyList()
            } else {
                proxyabilityProblems(name, type, method)
            }
        }
    }

    private fun proxyabilityProblems(
        name: String,
        type: Class<*>,
        method: Method,
    ): List<ConfigurationProblem> =
        buildList {
            if (!Modifier.isPublic(method.modifiers)) {
                add(problem(name, method, "is tenant-annotated but is not public and cannot be proxied"))
            }
            if (Modifier.isFinal(method.modifiers)) {
                add(problem(name, method, "is tenant-annotated but is final and cannot be proxied"))
            }
            if (Modifier.isFinal(type.modifiers)) {
                add(problem(name, method, "belongs to final ${type.name} and cannot be class-proxied"))
            }
        }

    private fun methodsOf(type: Class<*>): List<Method> {
        val found = linkedMapOf<String, Method>()

        fun add(from: Class<*>) {
            from.declaredMethods.forEach { method ->
                if (!method.isSynthetic && method.declaringClass != Any::class.java) {
                    found.putIfAbsent("${method.name}(${method.parameterTypes.joinToString { it.name }})", method)
                }
            }
            from.interfaces.forEach(::add)
        }
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            add(current)
            current = current.superclass
        }
        return found.values.toList()
    }

    private fun problem(
        bean: String,
        method: Method,
        message: String,
    ): ConfigurationProblem = ConfigurationProblem("tenant.service.$bean.${method.name}", ProblemCode.INVALID, message)
}
