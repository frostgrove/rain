package com.gd.rain.boot.runtime

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import org.springframework.core.env.Environment

/**
 * Which deployment this is, stated in `rain.deployment.stage`.
 *
 * It is required and independent of Spring profiles: a process that forgot to say it runs in
 * production must not start as a development one and skip every production rule.
 */
public enum class DeploymentStage(
    public val wire: String,
) {
    DEV("dev"),
    TEST("test"),
    PROD("prod"),
    ;

    public companion object {
        public const val PROPERTY: String = "rain.deployment.stage"

        public val wireNames: List<String> = entries.map(DeploymentStage::wire)

        public fun fromWire(value: String): DeploymentStage? = entries.firstOrNull { it.wire == value }

        public fun resolve(environment: Environment): StageResolution {
            val stated =
                environment.getProperty(PROPERTY)
                    ?: return StageResolution.Invalid(
                        ConfigurationProblem(
                            PROPERTY,
                            ProblemCode.REQUIRED,
                            "no value is provided; state one of ${wireNames.joinToString(", ")}",
                        ),
                    )
            val stage =
                fromWire(stated)
                    ?: return StageResolution.Invalid(
                        ConfigurationProblem(PROPERTY, ProblemCode.INVALID, "is \"$stated\"; it is one of ${wireNames.joinToString(", ")}"),
                    )
            return StageResolution.Resolved(stage)
        }
    }
}

public sealed interface StageResolution {
    public data class Resolved(
        public val stage: DeploymentStage,
    ) : StageResolution

    public data class Invalid(
        public val problem: ConfigurationProblem,
    ) : StageResolution
}
