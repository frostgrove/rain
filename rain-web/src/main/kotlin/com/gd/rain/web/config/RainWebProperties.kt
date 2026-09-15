package com.gd.rain.web.config

import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.boot.config.ConfigurationContributor
import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.Presence
import com.gd.rain.boot.config.SectionSpec
import com.gd.rain.boot.config.written
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.config.problems
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.util.unit.DataSize
import org.springframework.web.cors.CorsConfiguration
import java.time.Duration

/** Where the client address a request is attributed to comes from. */
public enum class ClientAddressMode(
    public val wire: String,
) {
    /** The peer of the connection; forwarding headers are not honoured (`server.forward-headers-strategy=none`). */
    DIRECT("direct"),

    /** A trusted proxy's forwarding headers, applied by the server (`server.forward-headers-strategy=native|framework`). */
    FORWARDED("forwarded"),
    ;

    public companion object {
        public val wireNames: List<String> = entries.map(ClientAddressMode::wire)
    }
}

/**
 * `rain.web` — the transport policy of a servlet application. Required: the body limit, the request
 * budget and where the client address comes from have no defaults, because each is a door.
 */
@ConfigurationProperties(RainWebProperties.PREFIX)
public data class RainWebProperties(
    /** The largest request body accepted, positive and at most `Integer.MAX_VALUE` bytes. */
    public val bodyLimit: DataSize,
    /** How long a request may be served before it is answered `503 deadline_exceeded`. */
    public val requestBudget: Duration,
    public val clientAddress: ClientAddressMode,
    public val cors: Cors = Cors(),
    /** Header name to value, set on every response. Stating any entry replaces the declared default map entirely. */
    public val securityHeaders: Map<String, String> = DEFAULT_SECURITY_HEADERS,
    public val probes: Probes = Probes(),
) : ConfigurationSection {
    /**
     * `rain.web.cors` — which cross-origin callers are allowed. No origins means no cross-origin caller:
     * every CORS request is refused with `403 cross_site`. An allowed origin needs its methods stated.
     */
    public data class Cors(
        public val allowedOrigins: List<String> = emptyList(),
        public val allowedMethods: List<String> = emptyList(),
        public val allowedHeaders: List<String> = emptyList(),
        public val exposedHeaders: List<String> = emptyList(),
        /** How long a preflight answer may be cached; absent sends no `Access-Control-Max-Age`. */
        public val maxAge: Duration? = null,
        public val allowCredentials: Boolean = false,
    ) : ConfigurationSection {
        public fun toCorsConfiguration(): CorsConfiguration {
            val configuration = CorsConfiguration()
            configuration.allowedOrigins = allowedOrigins
            configuration.allowedMethods = allowedMethods
            configuration.allowedHeaders = allowedHeaders
            configuration.exposedHeaders = exposedHeaders
            maxAge?.let(configuration::setMaxAge)
            configuration.allowCredentials = allowCredentials
            return configuration
        }
    }

    /** `rain.web.probes` — where liveness and readiness are served. */
    public data class Probes(
        public val livePath: String = DEFAULT_LIVE_PATH,
        public val readyPath: String = DEFAULT_READY_PATH,
    ) : ConfigurationSection {
        public val paths: Set<String> get() = setOf(livePath, readyPath)
    }

    public fun problems(): List<ConfigurationProblem> =
        problems {
            expect(bodyLimit.toBytes() > 0, BODY_LIMIT) { "is ${bodyLimit.written()}; it has to be positive" }
            expect(bodyLimit.toBytes() <= Int.MAX_VALUE, BODY_LIMIT) {
                "is ${bodyLimit.written()}; it is at most ${Int.MAX_VALUE} bytes, the largest body a servlet container counts"
            }
            expect(
                !requestBudget.isZero && !requestBudget.isNegative,
                REQUEST_BUDGET,
            ) { "is ${requestBudget.written()}; it has to be positive" }

            cors.allowedOrigins.forEachIndexed { index, origin ->
                expect(origin == ANY || ORIGIN.matches(origin), "$CORS.allowed-origins[$index]") {
                    "is \"$origin\"; an origin is * or scheme://host[:port], with no path"
                }
            }
            expect(!(cors.allowCredentials && ANY in cors.allowedOrigins), "$CORS.allow-credentials", ProblemCode.CONTRADICTS) {
                "is true while $CORS.allowed-origins contains *; a browser discards a credentialed answer to every origin"
            }
            expect(cors.allowedOrigins.isEmpty() || cors.allowedMethods.isNotEmpty(), "$CORS.allowed-methods", ProblemCode.REQUIRED) {
                "no value is provided; $CORS.allowed-origins names cross-origin callers, so the methods they may use are stated"
            }
            tokens(cors.allowedMethods, "$CORS.allowed-methods")
            tokens(cors.allowedHeaders, "$CORS.allowed-headers")
            tokens(cors.exposedHeaders, "$CORS.exposed-headers")
            cors.maxAge?.let { expect(!it.isNegative, "$CORS.max-age") { "is ${it.written()}; it is not negative" } }

            securityHeaders.forEach { (name, value) ->
                expect(TOKEN.matches(name), "$SECURITY_HEADERS.$name") { "is not a header name" }
                expect(value.isNotBlank() && value.none { it == '\r' || it == '\n' || it == '\u0000' }, "$SECURITY_HEADERS.$name") {
                    "has a value that is blank or contains a line break"
                }
            }

            expect(PATH.matches(probes.livePath), "$PROBES.live-path") { "is \"${probes.livePath}\"; a probe path matches ${PATH.pattern}" }
            expect(
                PATH.matches(probes.readyPath),
                "$PROBES.ready-path",
            ) { "is \"${probes.readyPath}\"; a probe path matches ${PATH.pattern}" }
            expect(
                probes.livePath != probes.readyPath,
                "$PROBES.ready-path",
                ProblemCode.CONTRADICTS,
            ) { "is the same path as $PROBES.live-path" }
        }

    private fun com.gd.rain.core.config.ProblemCollector.tokens(
        values: List<String>,
        path: String,
    ) {
        values.forEachIndexed { index, value ->
            expect(value == ANY || TOKEN.matches(value), "$path[$index]") { "is \"$value\"; it is * or an HTTP token" }
        }
    }

    public companion object {
        public const val PREFIX: String = "rain.web"
        public const val BODY_LIMIT: String = "$PREFIX.body-limit"
        public const val REQUEST_BUDGET: String = "$PREFIX.request-budget"
        public const val CLIENT_ADDRESS: String = "$PREFIX.client-address"
        public const val CORS: String = "$PREFIX.cors"
        public const val SECURITY_HEADERS: String = "$PREFIX.security-headers"
        public const val PROBES: String = "$PREFIX.probes"

        public const val DEFAULT_LIVE_PATH: String = "/live"
        public const val DEFAULT_READY_PATH: String = "/ready"

        /** An API serves no page, so nothing may load, frame, sniff, refer or downgrade. */
        public val DEFAULT_SECURITY_HEADERS: Map<String, String> =
            linkedMapOf(
                "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
                "X-Frame-Options" to "DENY",
                "X-Content-Type-Options" to "nosniff",
                "Referrer-Policy" to "no-referrer",
                "Strict-Transport-Security" to "max-age=31536000; includeSubDomains",
            )

        private const val ANY = "*"
        private val ORIGIN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#\\s]+$")
        private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private val PATH = Regex("^/[A-Za-z0-9._~!$&'()+,;=:@/-]*$")
    }
}

/** Declares the `rain.web` section to rain's configuration validation. */
public class RainWebConfigurationContributor : ConfigurationContributor {
    override val sections: List<SectionSpec<*>> =
        listOf(SectionSpec(RainWebProperties.PREFIX, RainWebProperties::class, Presence.REQUIRED) { section, _ -> section.problems() })
}

/**
 * `rain.web.client-address` agrees with how the server treats forwarding headers.
 *
 * `forwarded` needs `server.forward-headers-strategy` stated as `native` or `framework`; `direct` needs
 * it stated as `none`, because an unstated strategy lets Spring Boot decide from the detected cloud
 * platform, and a server that honours `X-Forwarded-For` from anyone attributes requests to an address
 * any client can write.
 */
public class ForwardHeadersCheck(
    private val mode: ClientAddressMode,
    private val environment: Environment,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> {
        val stated = Binder.get(environment).bind(STRATEGY, String::class.java).orElse(null)
        val strategy = stated?.lowercase()
        if (stated != null && strategy !in STRATEGIES) {
            return listOf(
                ConfigurationProblem(STRATEGY, ProblemCode.INVALID, "is \"$stated\"; it is one of ${STRATEGIES.joinToString(", ")}"),
            )
        }
        return problems {
            when (mode) {
                ClientAddressMode.DIRECT -> {
                    expect(strategy == NONE, RainWebProperties.CLIENT_ADDRESS, ProblemCode.CONTRADICTS) {
                        "is direct, but $STRATEGY is ${stated ?: "not stated"}; state $STRATEGY=none so no forwarding header is honoured"
                    }
                }

                ClientAddressMode.FORWARDED -> {
                    expect(strategy == NATIVE || strategy == FRAMEWORK, RainWebProperties.CLIENT_ADDRESS, ProblemCode.CONTRADICTS) {
                        "is forwarded, but $STRATEGY is ${stated ?: "not stated"}; state native or framework for the proxy in front of this server"
                    }
                }
            }
        }
    }

    public companion object {
        public const val STRATEGY: String = "server.forward-headers-strategy"
        private const val NONE = "none"
        private const val NATIVE = "native"
        private const val FRAMEWORK = "framework"
        private val STRATEGIES = listOf(NATIVE, FRAMEWORK, NONE)
    }
}
