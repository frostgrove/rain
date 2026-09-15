package com.gd.rain.web.probe

import com.gd.rain.web.route.EndpointDeclaration
import com.gd.rain.web.route.MountsItsOwnSurface

/** The probes on the verified HTTP surface: `GET` on the live and ready paths, public, because an orchestrator holds no credential. */
public class ProbeSurface(
    private val livePath: String,
    private val readyPath: String,
) : MountsItsOwnSurface {
    override fun mountedDeclarations(): List<EndpointDeclaration> =
        listOf(livePath, readyPath).map { EndpointDeclaration("GET", it, public = true, why = WHY) }

    public companion object {
        public const val WHY: String = "an orchestrator probes the process without a credential"
    }
}
