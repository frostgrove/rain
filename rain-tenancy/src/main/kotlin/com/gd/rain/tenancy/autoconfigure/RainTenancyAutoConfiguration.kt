package com.gd.rain.tenancy.autoconfigure

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantDataPlane
import com.gd.rain.tenancy.TenantRuntimeManager
import com.gd.rain.tenancy.TenantRuntimeTask
import com.gd.rain.tenancy.service.TenantServiceAdvisor
import com.gd.rain.tenancy.service.TenantServiceOperations
import com.gd.rain.tenancy.service.TenantServiceSurfaceVerifier
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * The optional magic layer over an application-provided authority and data plane. It creates no
 * resolver, datasource, or tenant placement by itself. AspectJ weaving is a module-local runtime
 * dependency solely so Boot can install its normal advisor proxy creator for this explicit advisor.
 */
@AutoConfiguration(after = [AopAutoConfiguration::class])
@ConditionalOnBean(TenantAuthority::class, TenantDataPlane::class)
public class RainTenancyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun tenantRuntimeManager(
        authority: TenantAuthority,
        tasks: ObjectProvider<TenantRuntimeTask>,
    ): TenantRuntimeManager = TenantRuntimeManager(authority, tasks.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    public fun tenantServiceOperations(
        dataPlane: TenantDataPlane,
        runtime: TenantRuntimeManager,
    ): TenantServiceOperations = TenantServiceOperations(dataPlane, runtime)

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(TenantServiceAdvisor::class)
    public fun tenantServiceAdvisor(operations: TenantServiceOperations): TenantServiceAdvisor = TenantServiceAdvisor(operations)

    @Bean
    public fun tenantServiceSurfaceVerifier(beans: ListableBeanFactory): ConfigurationCheck = TenantServiceSurfaceVerifier(beans)
}
