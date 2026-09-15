package com.gd.rain.crud.autoconfigure

import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.crud.error.RainCrudErrorCodes
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
}
