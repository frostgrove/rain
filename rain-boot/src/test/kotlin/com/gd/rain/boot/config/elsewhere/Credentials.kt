package com.gd.rain.boot.config.elsewhere

import com.gd.rain.boot.config.ConfigurationSection
import com.gd.rain.boot.config.RequiredFromEnvironment
import com.gd.rain.boot.runtime.DeploymentStage

/** A nested section in an unrelated package: the validator descends into it because of its type, not its package. */
data class Credentials(
    val user: String,
    @param:RequiredFromEnvironment(DeploymentStage.PROD)
    val password: String,
) : ConfigurationSection
