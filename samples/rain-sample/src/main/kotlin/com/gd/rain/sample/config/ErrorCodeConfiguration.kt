package com.gd.rain.sample.config

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.ErrorCodeCatalog
import com.gd.rain.core.error.ErrorCodeRegistry
import com.gd.rain.web.problem.ErrorCodeRegistrar
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * WORKAROUND for a rain defect. rain-access (`AccessErrorCodes`) and rain-crud (`RainCrudErrorCodes`) both declare the
 * error code `invalid_cursor`, and rain-web's registry refuses a value two owners declare, so no application that uses
 * both modules starts — every process, `config-check` included, is refused with
 * `error-code:invalid_cursor [contradicts]: is declared by rain-access, rain-crud`.
 *
 * rain-web lets an application state its own registry (its `errorCodeRegistry` bean is `@ConditionalOnMissingBean`).
 * This one registers every catalog through rain-web's own `ErrorCodeRegistrar`, except that the codes in
 * [SHARED_WITH_CRUD] are registered once, under rain-crud, while — and only while — both modules declare them. Codes are
 * equal by value, so a fault either module raises with one renders the same way. Every other duplicate is still refused.
 * The bean goes when rain declares the code once.
 */
@Configuration(proxyBeanMethods = false)
class ErrorCodeConfiguration {
    @Bean
    fun errorCodeRegistry(catalogs: ObjectProvider<ErrorCodeCatalog>): ErrorCodeRegistry {
        val declared = catalogs.orderedStream().toList()
        val crud =
            declared
                .filter { it.owner == CRUD }
                .flatMap { it.codes }
                .map(ErrorCode::value)
                .toSet()
        val shared = SHARED_WITH_CRUD.filter { it in crud }.toSet()
        return ErrorCodeRegistrar.register(declared.map { if (it.owner == ACCESS) Without(it, shared) else it })
    }

    /** [catalog] without the codes whose values are in [values]. */
    private class Without(
        private val catalog: ErrorCodeCatalog,
        private val values: Set<String>,
    ) : ErrorCodeCatalog {
        override val owner: String get() = catalog.owner
        override val codes: List<ErrorCode> get() = catalog.codes.filterNot { it.value in values }
    }

    companion object {
        const val ACCESS = "rain-access"
        const val CRUD = "rain-crud"

        /** The values rain-access and rain-crud both declare, as of this revision of rain. */
        val SHARED_WITH_CRUD: Set<String> = setOf("invalid_cursor")
    }
}
