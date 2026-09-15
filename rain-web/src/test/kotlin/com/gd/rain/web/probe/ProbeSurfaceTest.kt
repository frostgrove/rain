package com.gd.rain.web.probe

import com.gd.rain.web.route.EndpointDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The probe routes are functional, so they are declared on the surface here rather than by an annotation. */
class ProbeSurfaceTest {
    @Test
    fun `both probes are declared public with the reason, and the declarations are well formed`() {
        val declarations = ProbeSurface("/live", "/ready").mountedDeclarations()

        assertThat(declarations).containsExactly(
            EndpointDeclaration("GET", "/live", public = true, why = ProbeSurface.WHY),
            EndpointDeclaration("GET", "/ready", public = true, why = ProbeSurface.WHY),
        )
        assertThat(declarations.flatMap(EndpointDeclaration::problems)).isEmpty()
    }
}
