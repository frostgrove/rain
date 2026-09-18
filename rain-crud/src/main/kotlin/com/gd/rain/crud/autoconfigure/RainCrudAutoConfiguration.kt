package com.gd.rain.crud.autoconfigure

import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.error.CrudDatabaseViolationMapper
import com.gd.rain.crud.error.RainCrudErrorCodes
import com.gd.rain.persistence.fault.DatabaseViolationMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * Registers rain-crud's error codes, so its refusals render with their own codes. Resources themselves are
 * the application's beans: a [com.gd.rain.crud.CrudResource] is declared where its table is.
 */
@AutoConfiguration
public class RainCrudAutoConfiguration {
    @Bean
    public fun rainCrudErrorCodes(): ErrorCodeCatalog = RainCrudErrorCodes

    @Bean
    public fun crudDatabaseViolationMapper(resources: ObjectProvider<CrudResource<*>>): DatabaseViolationMapper =
        CrudDatabaseViolationMapper { resources.orderedStream().map(CrudResource<*>::schema).toList() }
}
