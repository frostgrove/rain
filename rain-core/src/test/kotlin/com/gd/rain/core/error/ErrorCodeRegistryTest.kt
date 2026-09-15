package com.gd.rain.core.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ErrorCodeRegistryTest {
    private fun catalog(
        owner: String,
        vararg values: String,
    ): ErrorCodeCatalog =
        object : ErrorCodeCatalog {
            override val owner = owner
            override val codes = values.map { ErrorCode.of(it, "message") }
        }

    @Test
    fun `distinct codes from several owners register together`() {
        val registration = ErrorCodeRegistry.of(listOf(RainErrorCodes, catalog("sample", "ticket_closed")))

        assertThat(registration).isInstanceOf(ErrorCodeRegistration.Registered::class.java)
        val registry = (registration as ErrorCodeRegistration.Registered).registry
        assertThat(ErrorCode.of("ticket_closed", "x") in registry).isTrue()
        assertThat(registry.ownerOf(RainErrorCodes.UNIQUE)).isEqualTo("rain-core")
        assertThat(registry.size).isEqualTo(RainErrorCodes.codes.size + 1)
    }

    @Test
    fun `an undeclared code is not in the registry`() {
        val registry = (ErrorCodeRegistry.of(listOf(RainErrorCodes)) as ErrorCodeRegistration.Registered).registry

        assertThat(ErrorCode.of("invented_here", "x") in registry).isFalse()
    }

    @Test
    fun `a code declared by two owners is refused, naming both`() {
        val registration = ErrorCodeRegistry.of(listOf(catalog("a", "shared", "own_a"), catalog("b", "shared")))

        assertThat(registration).isEqualTo(
            ErrorCodeRegistration.Refused(listOf("error code \"shared\" is declared by a, b")),
        )
    }

    @Test
    fun `a code declared twice by one owner is refused too`() {
        val registration = ErrorCodeRegistry.of(listOf(catalog("a", "twice", "twice")))

        assertThat(registration).isEqualTo(ErrorCodeRegistration.Refused(listOf("error code \"twice\" is declared by a, a")))
    }

    @Test
    fun `every duplicate is reported, in code order`() {
        val registration = ErrorCodeRegistry.of(listOf(catalog("a", "zeta", "alpha"), catalog("b", "zeta", "alpha")))

        assertThat((registration as ErrorCodeRegistration.Refused).problems).containsExactly(
            "error code \"alpha\" is declared by a, b",
            "error code \"zeta\" is declared by a, b",
        )
    }

    @Test
    fun `rain-core declares no code twice`() {
        assertThat(ErrorCodeRegistry.of(listOf(RainErrorCodes))).isInstanceOf(ErrorCodeRegistration.Registered::class.java)
    }
}
