package com.gd.rain.tenancy.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.support.GenericApplicationContext

class TenantServiceSurfaceVerifierTest {
    @Test
    fun `annotated non-proxyable methods are reported before the application serves`() {
        applicationContext(FinalMethodService::class.java, PrivateMethodService::class.java).use { context ->
            val problems = TenantServiceSurfaceVerifier(context).problems()

            assertThat(problems.map { it.path to it.message })
                .contains(
                    "tenant.service.finalMethodService.notProxyable" to "is tenant-annotated but is final and cannot be proxied",
                    "tenant.service.privateMethodService.notProxyable" to "is tenant-annotated but is not public and cannot be proxied",
                )
        }
    }

    @Test
    fun `open public annotated method is a valid proxy surface`() {
        applicationContext(OpenService::class.java).use { context ->
            assertThat(TenantServiceSurfaceVerifier(context).problems()).isEmpty()
        }
    }

    private fun applicationContext(vararg types: Class<*>): GenericApplicationContext =
        GenericApplicationContext().also { context ->
            types.forEach { type ->
                context.registerBeanDefinition(type.simpleName.replaceFirstChar(Char::lowercase), RootBeanDefinition(type))
            }
            context.refresh()
        }

    private open class FinalMethodService {
        @TenantRead
        fun notProxyable(): String = "no"
    }

    private open class PrivateMethodService {
        @TenantRead
        private fun notProxyable(): String = "no"
    }

    private open class OpenService {
        @TenantRead
        open fun proxyable(): String = "yes"
    }
}
