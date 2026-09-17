package com.gd.rain.tenancy.autoconfigure

import com.gd.rain.tenancy.TenantCandidate
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantRequestContext
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantResolutionDirectory
import com.gd.rain.tenancy.TenantResolutionSource
import com.gd.rain.tenancy.TenantResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RainTenantResolutionAutoConfigurationTest {
    private val resolution = TenantResolution(TenantRef.of("acme"), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
    private val source =
        object : TenantResolutionSource {
            override val id: String = "principal"

            override fun resolve(context: TenantRequestContext): TenantCandidate = TenantCandidate.Present(resolution, id)
        }
    private val directory = TenantResolutionDirectory { ref -> resolution.takeIf { it.ref == ref } }
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainTenantResolutionAutoConfiguration::class.java))

    @Test
    fun `declared sources and a directory assemble the composite resolver`() {
        runner
            .withBean(TenantResolutionDirectory::class.java, { directory })
            .withBean(TenantResolutionSource::class.java, { source })
            .run { context ->
                val resolver = context.getBean(TenantResolver::class.java)

                assertThat(resolver.resolveCurrent(EmptyContext)).isEqualTo(TenantCandidate.Present(resolution, "principal"))
                assertThat(resolver.lookup(TenantRef.of("acme"))).isEqualTo(resolution)
            }
    }

    @Test
    fun `an application resolver is never replaced or reordered`() {
        val application =
            object : TenantResolver {
                override fun resolveCurrent(context: TenantRequestContext): TenantCandidate = TenantCandidate.Absent

                override fun lookup(ref: TenantRef): TenantResolution? = null
            }

        runner
            .withBean(TenantResolutionDirectory::class.java, { directory })
            .withBean(TenantResolutionSource::class.java, { source })
            .withBean(TenantResolver::class.java, { application })
            .run { context ->
                assertThat(context.getBean(TenantResolver::class.java)).isSameAs(application)
            }
    }

    private data object EmptyContext : TenantRequestContext {
        override fun attribute(name: String): String? = null
    }
}
