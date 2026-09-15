package com.gd.rain.sample.minimal

import com.gd.rain.boot.command.runRain
import com.gd.rain.core.error.ErrorCodeCatalog
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@SpringBootApplication
@EnableConfigurationProperties(GreetingProperties::class)
class MinimalApplication {
    @Bean
    fun sampleErrorCodes(): ErrorCodeCatalog = SampleErrorCodes
}

fun main(args: Array<String>) {
    runRain<MinimalApplication>(args)
}
