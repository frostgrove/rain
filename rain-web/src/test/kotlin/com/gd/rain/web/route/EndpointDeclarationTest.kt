package com.gd.rain.web.route

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndpointDeclarationTest {
    @Test
    fun `every unsafe declaration shape is reported rather than inferred as public`() {
        assertThat(
            EndpointDeclaration(
                "GET",
                "/reports",
                permissions = listOf("", "report.read"),
                authenticated = true,
                public = true,
            ).problems(),
        ).containsExactly(
            "GET /reports is declared both public and authenticated",
            "GET /reports is declared public and also requires , report.read",
            "GET /reports declares an empty permission",
        )
        assertThat(EndpointDeclaration("GET", "/undeclared").problems())
            .containsExactly("GET /undeclared is mounted and declares no access")
        assertThat(EndpointDeclaration("GET", "/public", public = true).problems())
            .containsExactly("GET /public is declared public and says nothing about why")
        assertThat(EndpointDeclaration("GET", "/account", authenticated = true).problems())
            .containsExactly("GET /account is declared authenticated and says nothing about why")
    }

    @Test
    fun `annotation declaration preserves the handler's explicit access shape`() {
        val access = checkNotNull(Declared::class.java.getAnnotation(Access::class.java))

        assertThat(EndpointDeclaration.of("POST", "/reports", access))
            .isEqualTo(EndpointDeclaration("POST", "/reports", permissions = listOf("report.write")))
    }

    @Access(permissions = ["report.write"])
    private class Declared
}
