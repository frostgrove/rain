package com.gd.rain.tenancy.autoconfigure

import com.gd.rain.tenancy.CompositeTenantResolver
import com.gd.rain.tenancy.TenantResolutionDirectory
import com.gd.rain.tenancy.TenantResolutionSource
import com.gd.rain.tenancy.TenantResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Assembles an application-declared, ordered source registry over a read-only control directory.
 * It creates neither a source nor a directory: in particular, no HTTP header becomes trusted by
 * merely adding this module.
 */
@AutoConfiguration
@ConditionalOnBean(TenantResolutionDirectory::class, TenantResolutionSource::class)
@ConditionalOnMissingBean(TenantResolver::class)
public class RainTenantResolutionAutoConfiguration {
    @Bean
    public fun tenantResolver(
        directory: TenantResolutionDirectory,
        sources: ObjectProvider<TenantResolutionSource>,
    ): TenantResolver = CompositeTenantResolver(sources.orderedStream().toList(), directory::lookup)
}
